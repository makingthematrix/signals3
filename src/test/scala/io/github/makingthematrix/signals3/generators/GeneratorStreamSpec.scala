package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.testutils.{awaitAllTasks, waitFor}
import io.github.makingthematrix.signals3.{DispatchQueue, EventContext, FlagSignal, SerialDispatchQueue, Signal}

import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.concurrent.duration.*

class GeneratorStreamSpec extends munit.FunSuite {
  private val eventContext = EventContext()
  given dq: DispatchQueue = SerialDispatchQueue()

  override def beforeEach(context: BeforeEach): Unit =
    eventContext.start()

  override def afterEach(context: AfterEach): Unit =
    eventContext.stop()
    
  given Timeout: FiniteDuration = 3.seconds
  val HeartBeatMs: FiniteDuration = 100.millis

  test("heartbeat stream") {
    var counter = 0
    val isSuccess = Signal(false)
    val stream = GeneratorStream.heartbeat(HeartBeatMs)
    stream.foreach { _ =>
      counter += 1
      if (counter == 3) isSuccess ! true
    }
    waitFor(isSuccess, true)
    stream.close()
    awaitAllTasks
    assert(stream.isClosed)
  }

  test("closing the stream twice returns false") {
    val stream = GeneratorStream.heartbeat(HeartBeatMs)
    assert(stream.closeAndCheck())
    assert(stream.isClosed)
    assert(!stream.closeAndCheck())
  }

  test("a made-up variant heartbeat stream") {
    var counter = 0
    val isSuccess = Signal(false)
    val stream = GeneratorStream.repeat((), () => FiniteDuration(HeartBeatMs.toMillis, TimeUnit.MILLISECONDS))
    stream.foreach { _ =>
      counter += 1
      if (counter == 3) isSuccess ! true
    }
    waitFor(isSuccess, true)
    stream.close()
    awaitAllTasks
    assert(stream.isClosed)
  }

  test("fibonacci stream with generate") {
    var a = 0
    var b = 1
    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    val stream = GeneratorStream.generate(HeartBeatMs) {
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
    waitFor(isSuccess, true)
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

    val stream = GeneratorStream.generate(() => FiniteDuration(b * HeartBeatMs.toMillis, TimeUnit.MILLISECONDS)) {
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
    waitFor(isSuccess, true)

    val totalTime = System.currentTimeMillis - now
    stream.close()
    assertEquals(builder.result().toSeq, Seq(1, 1, 2, 3, 5))
    // it should take (100 + 100 + 200 + 300 + 500)ms + 100ms for a buffer, but still it can be flaky
    assert(totalTime >= 1200L && totalTime <= 1500L, s"total time: $totalTime")
  }

  test("add pause to heartbeat") {
    var counter = 0
    val isSuccess = Signal(false)
    val stream1 = GeneratorStream.heartbeat(HeartBeatMs)
    stream1.foreach { _ =>
      counter += 1
      isSuccess ! (counter == 4)
    }
    val t1 = System.currentTimeMillis
    waitFor(isSuccess, true)
    val timePassed1 = System.currentTimeMillis - t1
    stream1.close()

    // restart, this time with pause for one beat when counter == 2
    isSuccess ! false
    waitFor(isSuccess, false)
    counter = 0

    var pausedOn = 0L

    def paused(): Boolean =
      if (counter == 2 && pausedOn == 0L) {
        pausedOn = System.currentTimeMillis
        true
      }
      else
        System.currentTimeMillis - pausedOn < 150L

    val stream2 = GeneratorStream(() => (), HeartBeatMs, paused)
    stream2.foreach { _ =>
      counter += 1
      isSuccess ! (counter == 4)
    }

    val t2 = System.currentTimeMillis
    waitFor(isSuccess, true)
    val timePassed2 = System.currentTimeMillis - t2
    stream2.close()
    awaitAllTasks
    assert(timePassed2 - timePassed1 >= HeartBeatMs.toMillis, timePassed2)
    assert(pausedOn > 0L)
  }

  test("Generate a stream from a sequence of numbers") {
    val numbers = Seq(1,2,3,4,5)
    val t = System.currentTimeMillis()
    val buffer = mutable.ArrayBuffer[(Int, Long)]()
    val isSuccess = Signal.done()
    val generator: FiniteGeneratorStream[Int] = GeneratorStream.from(numbers, (HeartBeatMs.toMillis / 4).millis)
    generator.onClose(isSuccess.done())
    generator.map(n => (n, System.currentTimeMillis() - t)).foreach((n,l) => buffer.addOne((n, l)))

    waitFor(isSuccess, true)(using duration = 1.seconds)

    val res = buffer.toSeq
    assertEquals(res.size, numbers.size)
    assertEquals(res.map(_._1), numbers)
    assert(res.map(_._2).zip(res.tail.map(_._2)).forall((a, b) => b >= a))
  }

  test("heartbeat stream with function interval") {
    var counter = 0
    val isSuccess = Signal(false)
    val stream = GeneratorStream.heartbeat(() => HeartBeatMs)
    stream.foreach { _ =>
      counter += 1
      if (counter == 3) isSuccess ! true
    }
    waitFor(isSuccess, true)
    stream.close()
    awaitAllTasks
    assert(stream.isClosed)
  }

  test("repeat with constant interval publishes repeatedly") {
    var counter = 0
    val isSuccess = Signal(false)
    val stream = GeneratorStream.repeat("tick", (HeartBeatMs.toMillis / 2).millis)
    stream.foreach { s =>
      assertEquals(s, "tick")
      counter += 1
      if (counter == 5) isSuccess ! true
    }
    waitFor(isSuccess, true)
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
    }, (HeartBeatMs.toMillis / 4).millis)

    gen.onClose(finished ! true)
    gen.foreach(buffer.addOne)

    waitFor(finished, true)(using duration = 1.seconds)
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
    }, (HeartBeatMs.toMillis / 4).millis)

    gen.onClose(finished ! true)
    gen.foreach(buffer.addOne)

    waitFor(finished, true)(using duration = 1.seconds)
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

    waitFor(done, true)
    assertEquals(buffer.toSeq, Seq(1,2,3,4,5))
  }

  test("FiniteGeneratorStream.init publishes all but the last element") {
    val numbers = Seq(10,20,30,40)
    val bufInit = mutable.ArrayBuffer[Int]()
    val done = FlagSignal()
    val gen = GeneratorStream.from(numbers, (HeartBeatMs.toMillis / 4).millis)

    gen.init.foreach { n =>
      bufInit.addOne(n)
      done.setIf(n == numbers.init.last)
    }

    waitFor(done, true)(using duration = 1.seconds)
    // wait for the underlying finite generator to complete as well
    val allDone = Signal(false)
    gen.onClose(allDone ! true)
    waitFor(allDone, true)(using duration = 1.seconds)

    assertEquals(bufInit.toSeq, numbers.init)
    assert(gen.isClosed)
  }

  test("CloseableGeneratorStream.onClose is invoked on close") {
    var closed = false
    val stream = GeneratorStream(() => 1, HeartBeatMs)
    stream.onClose { closed = true }
    // consume a few events then close
    val done = FlagSignal()
    var counter = 0
    stream.foreach { _ =>
      counter += 1
      done.setIf(counter == 2)
    }
    waitFor(done, true)
    stream.close()
    awaitAllTasks
    assert(stream.isClosed)
    assert(closed)
  }
}
