package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.testutils.{awaitAllTasks, waitForResult}
import io.github.makingthematrix.signals3.{EventContext, Signal, Threading}

import scala.collection.mutable
import scala.concurrent.duration.*

class GeneratorStreamSpec extends munit.FunSuite {
  import EventContext.Implicits.global
  import Threading.defaultContext

  test("heartbeat stream") {
    var counter = 0
    val isSuccess = Signal(false)
    val stream = GeneratorStream.heartbeat(200.millis)
    stream.foreach { _ =>
      counter += 1
      if counter == 3 then isSuccess ! true
    }
    waitForResult(isSuccess, true)
    stream.close()
    awaitAllTasks
    assert(stream.isClosed)
  }

  test("closing the stream twice returns false") {
    val stream = GeneratorStream.heartbeat(200.millis)
    assert(stream.closeAndCheck())
    assert(stream.isClosed)
    assert(!stream.closeAndCheck())
  }

  test("a made-up variant heartbeat stream") {
    def interval(): Long = 200L
    var counter = 0
    val isSuccess = Signal(false)
    val stream = GeneratorStream.repeatVariant((), interval)
    stream.foreach { _ =>
      counter += 1
      if counter == 3 then isSuccess ! true
    }
    waitForResult(isSuccess, true)
    stream.close()
    awaitAllTasks
    assert(stream.isClosed)
  }

  test("fibonacci stream with generate") {
    var a = 0
    var b = 1
    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    val stream = GeneratorStream.generate(200.millis) {
      val res = b
      val t = a + b
      a = b
      b = t
      res
    }
    stream.foreach { t =>
      builder.addOne(t)
      isSuccess ! (t == 8)
    }
    waitForResult(isSuccess, true)
    stream.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(1, 1, 2, 3, 5, 8))
  }

  test("fibonacci stream with delays also in fibonacci") {
    var a = 0
    var b = 1

    def fibDelay(): Long = b * 200L

    val builder = mutable.ArrayBuilder.make[Int]
    val now = System.currentTimeMillis
    val isSuccess = Signal(false)

    val stream = GeneratorStream.generateVariant(fibDelay) {
      val res = b
      val t = a + b
      a = b
      b = t
      res
    }
    stream.foreach { t =>
      builder.addOne(t)
      isSuccess ! (t == 5)
    }
    waitForResult(isSuccess, true)

    val totalTime = System.currentTimeMillis - now
    stream.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(1, 1, 2, 3, 5))
    // it should take (100 + 100 + 200 + 300 + 500)ms * 2 + 100ms for a buffer, but still it can be flaky
    assert(totalTime >= 2400L && totalTime <= 2500L, s"total time: $totalTime")
  }

  test("add pause to heartbeat") {
    var counter = 0
    val isSuccess = Signal(false)
    val stream1 = GeneratorStream.heartbeat(200.millis)
    stream1.foreach { _ =>
      counter += 1
      isSuccess ! (counter == 4)
    }
    val t1 = System.currentTimeMillis
    waitForResult(isSuccess, true)
    val timePassed1 = System.currentTimeMillis - t1
    stream1.close()
    awaitAllTasks

    // restart, this time with pause for one beat when counter == 2
    isSuccess ! false
    waitForResult(isSuccess, false)
    counter = 0

    var pausedOn = 0L

    def paused(): Boolean =
      if counter == 2 && pausedOn == 0L then {
        pausedOn = System.currentTimeMillis
        true
      }
      else
        System.currentTimeMillis - pausedOn < 300L

    val stream2 = GeneratorStream(() => (), 200.millis, paused)
    stream2.foreach { _ =>
      counter += 1
      isSuccess ! (counter == 4)
    }

    val t2 = System.currentTimeMillis
    waitForResult(isSuccess, true)
    val timePassed2 = System.currentTimeMillis - t2
    stream2.close()
    awaitAllTasks
    assert(timePassed2 - timePassed1 >= 200, timePassed2)
    assert(pausedOn > 0L)
  }

  test("Generate a stream from a sequence of numbers") {
    val numbers = Seq(1,2,3,4,5,6,7,8,9,10)
    val t = System.currentTimeMillis()
    val buffer = mutable.ArrayBuffer[(Int, Long)]()
    val isSuccess = Signal.done()
    val generator: FiniteGeneratorStream[Int] = GeneratorStream.fromIterable(numbers, 100.millis)
    generator.onClose(isSuccess.done())
    generator.map(n => (n, System.currentTimeMillis() - t)).foreach((n,l) => buffer.addOne((n, l)))

    waitForResult(isSuccess, true)

    val res = buffer.toSeq
    assertEquals(res.size, numbers.size)
    assertEquals(res.map(_._1), numbers)
    assert(res.map(_._2).zip(res.tail.map(_._2)).forall((a, b) => b >= a))
  }
}
