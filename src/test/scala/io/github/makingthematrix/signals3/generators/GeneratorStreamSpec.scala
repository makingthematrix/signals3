package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.testutils.{awaitAllTasks, waitForResult}
import io.github.makingthematrix.signals3.{EventContext, FlagSignal, Signal, Threading}

import java.util.concurrent.TimeUnit
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
      if (counter == 3) isSuccess ! true
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
    var counter = 0
    val isSuccess = Signal(false)
    val stream = GeneratorStream.repeat((), () => FiniteDuration(200L, TimeUnit.MILLISECONDS))
    stream.foreach { _ =>
      counter += 1
      if (counter == 3) isSuccess ! true
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

    val builder = mutable.ArrayBuilder.make[Int]
    val now = System.currentTimeMillis
    val isSuccess = Signal(false)

    val stream = GeneratorStream.generate(() => FiniteDuration(b * 200L, TimeUnit.MILLISECONDS)) {
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
      if (counter == 2 && pausedOn == 0L) {
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
    val generator: FiniteGeneratorStream[Int] = GeneratorStream.from(numbers, 100.millis)
    generator.onClose(isSuccess.done())
    generator.map(n => (n, System.currentTimeMillis() - t)).foreach((n,l) => buffer.addOne((n, l)))

    waitForResult(isSuccess, true)

    val res = buffer.toSeq
    assertEquals(res.size, numbers.size)
    assertEquals(res.map(_._1), numbers)
    assert(res.map(_._2).zip(res.tail.map(_._2)).forall((a, b) => b >= a))
  }

  test("heartbeat stream with function interval") {
    var counter = 0
    val isSuccess = Signal(false)
    val stream = GeneratorStream.heartbeat(() => 200.millis)
    stream.foreach { _ =>
      counter += 1
      if (counter == 3) isSuccess ! true
    }
    waitForResult(isSuccess, true)
    stream.close()
    awaitAllTasks
    assert(stream.isClosed)
  }

  test("repeat with constant interval publishes repeatedly") {
    var counter = 0
    val isSuccess = Signal(false)
    val stream = GeneratorStream.repeat("tick", 100.millis)
    stream.foreach { s =>
      assertEquals(s, "tick")
      counter += 1
      if (counter == 5) isSuccess ! true
    }
    waitForResult(isSuccess, true)
    stream.close()
    awaitAllTasks
    assert(stream.isClosed)
  }

  test("from(() => Option[E], interval) emits until None and then closes") {
    var n = 0
    val buffer = mutable.ArrayBuffer[Int]()
    val finished = Signal(false)
    val gen = GeneratorStream.from(() => {
      n += 1
      if (n <= 5) Some(n) else None
    }, 50.millis)

    gen.onClose(finished ! true)
    gen.foreach(buffer.addOne)

    waitForResult(finished, true)
    awaitAllTasks
    assert(gen.isClosed)
    assertEquals(buffer.toSeq, Seq(1,2,3,4,5))
  }

  test("FiniteGeneratorStream.apply(() => Option[E], interval) works the same") {
    var n = 0
    val buffer = mutable.ArrayBuffer[Int]()
    val finished = Signal(false)
    val gen: FiniteGeneratorStream[Int] = FiniteGeneratorStream(() => {
      n += 1
      if (n <= 3) Some(n) else None
    }, 60.millis)

    gen.onClose(finished ! true)
    gen.foreach(buffer.addOne)

    waitForResult(finished, true)
    awaitAllTasks
    assert(gen.isClosed)
    assertEquals(buffer.toSeq, Seq(1,2,3))
  }

  test("from(LazyList[E], interval) with take collects first N elements") {
    val buffer = mutable.ArrayBuffer[Int]()
    val done = FlagSignal()
    val gen = GeneratorStream.from(LazyList.from(1), 40.millis)
    val firstFive = gen.take(5)

    firstFive.foreach { n =>
      buffer.addOne(n)
      done.setIf(n == 5)
    }

    waitForResult(done, true)
    assertEquals(buffer.toSeq, Seq(1,2,3,4,5))
  }

  test("FiniteGeneratorStream.init publishes all but the last element") {
    val numbers = Seq(10,20,30,40)
    val bufInit = mutable.ArrayBuffer[Int]()
    val done = FlagSignal()
    val gen = GeneratorStream.from(numbers, 50.millis)

    gen.init.foreach { n =>
      bufInit.addOne(n)
      done.setIf(n == numbers.init.last)
    }

    waitForResult(done, true)
    // wait for the underlying finite generator to complete as well
    val allDone = Signal(false)
    gen.onClose(allDone ! true)
    waitForResult(allDone, true)
    awaitAllTasks

    assertEquals(bufInit.toSeq, numbers.init)
    assert(gen.isClosed)
  }

  test("CloseableGeneratorStream.onClose is invoked on close") {
    var closed = false
    val stream = GeneratorStream(() => 1, 100.millis)
    stream.onClose { closed = true }
    // consume a few events then close
    val done = FlagSignal()
    var counter = 0
    stream.foreach { _ =>
      counter += 1
      done.setIf(counter == 2)
    }
    waitForResult(done, true)
    stream.close()
    awaitAllTasks
    assert(stream.isClosed)
    assert(closed)
  }
}
