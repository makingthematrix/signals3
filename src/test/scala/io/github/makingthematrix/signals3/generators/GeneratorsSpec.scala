package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.testutils.{awaitAllTasks, waitForResult}
import io.github.makingthematrix.signals3.{EventContext, Signal, Threading}

import scala.collection.mutable
import scala.concurrent.duration.*

class GeneratorsSpec extends munit.FunSuite:
  import EventContext.Implicits.global
  import Threading.defaultContext

  test("test heartbeat event stream") {
    var counter = 0
    val isSuccess = Signal(false)
    val stream = GeneratorStream.heartbeat(100.millis)
    stream.foreach { _ =>
      counter += 1
      if counter == 3 then isSuccess ! true
    }
    waitForResult(isSuccess, true)
    stream.close()
    awaitAllTasks
    assert(stream.isClosed)
  }

  test("test fibonacci event stream with generate") {
    var a = 0
    var b = 1
    val builder = mutable.ArrayBuilder.make[Int]
    val stream = GeneratorStream.generate(100.millis) {
      val t = a + b
      a = b
      b = t
      t
    }
    stream.foreach { builder.addOne }
    waitForResult(stream, 8)
    stream.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(1, 2, 3, 5, 8))
  }

  test("test fibonacci signal with unfold") {
    val builder = mutable.ArrayBuilder.make[Int]
    val signal = GeneratorSignal.unfold((0, 1), 100.millis) { case (a, b) => (b, a + b) }

    signal.foreach { case (_, b) => builder.addOne(b) }
    waitForResult(signal.map(_._2), 8)
    signal.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(1, 1, 2, 3, 5, 8))
  }

  test("test fibonacci signal with delays also in fibonacci") {
    def fibDelay(t: (Int, Int)): FiniteDuration = (t._2 * 100L).millis

    val builder = mutable.ArrayBuilder.make[Int]
    val now = System.currentTimeMillis

    val signal = GeneratorSignal.unfoldWithMod[(Int, Int)]((0, 1), fibDelay) { case (a, b) => (b, a + b) }
    signal.foreach { case (_, b) => builder.addOne(b) }

    waitForResult(signal.map(_._2), 8)

    val totalTime = System.currentTimeMillis - now
    signal.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(1, 1, 2, 3, 5, 8))
    // it should take (100 + 200 + 300 + 500 + 800)ms + 100ms for a buffer, but still it can be flaky
    assert(totalTime >= 1200 && totalTime <= 1300L, totalTime)
  }

  test("test fibonacci event stream with delays also in fibonacci") {
    var a = 0
    var b = 1

    def fibDelay(): Long = b * 100L

    val builder = mutable.ArrayBuilder.make[Int]
    val now = System.currentTimeMillis
    val stream = GeneratorStream.generateWithMod(fibDelay) {
      val t = a + b
      a = b
      b = t
      t
    }
    stream.foreach { builder.addOne }

    waitForResult(stream, 8)

    val totalTime = System.currentTimeMillis - now
    stream.close()
    awaitAllTasks
    // in case of an event stream, the first element in the sequence is the resut of the first computation, so
    // the initial `1` is ignored
    assertEquals(builder.result().toSeq, Seq(1, 2, 3, 5, 8))
    // it should take (100 + 200 + 300 + 500 + 800)ms + 100ms for a buffer, but still it can be flaky
    assert(totalTime <= 1300L)
  }

  /** 
    * TODO: After extracting the Closeable trait, create unfoldLeft that would look something like this:
    * ```scala 
    * inline def unfoldLeft[V, Z](init: V, interval: FiniteDuration, map: V => Z)(body: V => V)
    *                            (using ec: ExecutionContext = Threading.defaultContext): Signal[Z] with Closeable =
    *   new GeneratorSignal[V](init, body, Left(interval), () => false).map(map)
    * ```
    * and then the test above could look like this:
    * ```scala
    * val builder = mutable.ArrayBuilder.make[Int]
    * val signal = GeneratorSignal.unfoldLeft((0, 1), 100.millis, _._2) { case (a, b) => (b, a + b) }
    * signal.foreach { builder.addOne }
    * waitForResult(signal, 8)
    * signal.close()
    * awaitAllTasks
    * assertEquals(builder.result().toSeq, Seq(1, 2, 3, 5, 8))
    * ```
    * Right now this is impossible because `.map(...)` on `GeneratorSignal` returns a non-closeable `Signal`
    * and so we lose the ability to close the original generator signal. 
    */
