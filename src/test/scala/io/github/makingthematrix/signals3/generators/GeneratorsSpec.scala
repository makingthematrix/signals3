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
    val stream = GeneratorEventStream.heartbeat(100.millis)
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
    val stream = GeneratorEventStream.generate(100.millis) {
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
    signal.map(_._2).foreach { builder.addOne }
    waitForResult(signal.map(_._2), 8)
    signal.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(1, 2, 3, 5, 8))
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