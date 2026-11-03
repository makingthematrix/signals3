package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.Utils.{awaitAllTasks, waitFor}
import io.github.makingthematrix.signals3.{DispatchQueue, EventContext, FlagSignal, SerialDispatchQueue, Signal}

import java.util.concurrent.{CountDownLatch, TimeUnit}
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

    val stream2 = GeneratorStream(() => (), HeartBeatMs)

    stream2.foreach { _ =>
      if (counter == 2 && pausedOn == 0L) {
        pausedOn = System.currentTimeMillis
        stream2.pause()
      }
      else if (System.currentTimeMillis - pausedOn < 150L) stream2.pause()
      else stream2.unpause()
    }

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

  test("force generates an event immediately and resets the beat") {
    var n = 0
    val buffer = mutable.ArrayBuffer[(Int, Long)]()
    // a latch instead of waitFor+Signal: waitFor polls every 100ms which aliases with the beat interval
    // and can return long after the second beat
    val twoBeats = new CountDownLatch(2)
    val forcedBeat = Signal(false)
    val fourBeats = Signal(false)
    val stream = GeneratorStream.generate(HeartBeatMs) {
      n += 1
      n
    }
    stream.foreach { e =>
      buffer.addOne((e, System.currentTimeMillis))
      if (e <= 2) twoBeats.countDown()
      if (e == 3) forcedBeat ! true
      if (e == 4) fourBeats ! true
    }

    // wait for two natural beats so that the generator settles into its rhythm
    assert(twoBeats.await(Timeout.toMillis, TimeUnit.MILLISECONDS), "the generator didn't produce two natural beats")
    // move into the middle of the current interval; if the beat is not reset by `force`,
    // the pending beat would fire soon after, at the old schedule
    Thread.sleep(30L)
    val forcedAt = System.currentTimeMillis
    stream.force()

    // the forced event should be published almost immediately after the call
    waitFor(forcedBeat, true)
    val (forcedEvent, forcedAtPublished) = buffer(2)
    assertEquals(forcedEvent, 3)
    val forcedDelay = forcedAtPublished - forcedAt
    assert(forcedDelay >= 0L && forcedDelay <= 50L, s"delay of the forced event: $forcedDelay")

    // the next natural beat should come a full interval after the forced one, not at the old schedule
    waitFor(fourBeats, true)
    val (_, nextBeatAt) = buffer(3)
    val gap = nextBeatAt - forcedAtPublished
    val intervalMs = HeartBeatMs.toMillis
    assert(gap >= intervalMs - (intervalMs / 4) && gap <= 3 * intervalMs, s"gap between the forced and the next beat: $gap")

    assertEquals(buffer.take(4).map(_._1).toSeq, Seq(1, 2, 3, 4))
    stream.close()
    awaitAllTasks
    assert(stream.isClosed)
  }

  test("force resets the beat also in the stream with the function interval") {
    var n = 0
    val buffer = mutable.ArrayBuffer[(Int, Long)]()
    val twoBeats = new CountDownLatch(2)
    val forcedBeat = Signal(false)
    val fourBeats = Signal(false)
    val stream = GeneratorStream.generate(() => HeartBeatMs) {
      n += 1
      n
    }
    stream.foreach { e =>
      buffer.addOne((e, System.currentTimeMillis))
      if (e <= 2) twoBeats.countDown()
      if (e == 3) forcedBeat ! true
      if (e == 4) fourBeats ! true
    }

    assert(twoBeats.await(Timeout.toMillis, TimeUnit.MILLISECONDS), "the generator didn't produce two natural beats")
    Thread.sleep(30L)
    val forcedAt = System.currentTimeMillis
    stream.force()

    waitFor(forcedBeat, true)
    val (forcedEvent, forcedAtPublished) = buffer(2)
    assertEquals(forcedEvent, 3)
    val forcedDelay = forcedAtPublished - forcedAt
    assert(forcedDelay >= 0L && forcedDelay <= 50L, s"delay of the forced event: $forcedDelay")

    waitFor(fourBeats, true)
    val (_, nextBeatAt) = buffer(3)
    val gap = nextBeatAt - forcedAtPublished
    val intervalMs = HeartBeatMs.toMillis
    assert(gap >= intervalMs - (intervalMs / 4) && gap <= 3 * intervalMs, s"gap between the forced and the next beat: $gap")

    assertEquals(buffer.take(4).map(_._1).toSeq, Seq(1, 2, 3, 4))
    stream.close()
    awaitAllTasks
    assert(stream.isClosed)
  }

  test("force on a not yet initialized stream publishes the first event immediately") {
    var n = 0
    val buffer = mutable.ArrayBuffer[(Int, Long)]()
    val twoEvents = new CountDownLatch(2)
    val stream = new CloseableGeneratorStream[Int](HeartBeatMs, () => { n += 1; n })
    assert(!stream.isInitialized)
    stream.foreach { e =>
      buffer.addOne((e, System.currentTimeMillis))
      twoEvents.countDown()
    }

    val forcedAt = System.currentTimeMillis
    stream.force()
    assert(stream.isInitialized)

    // the forced first event is published almost immediately
    assert(twoEvents.await(Timeout.toMillis, TimeUnit.MILLISECONDS), "the second event wasn't generated")
    val forcedEvent = buffer(0)._1
    assertEquals(forcedEvent, 1)
    val forcedDelay = buffer(0)._2 - forcedAt
    assert(forcedDelay >= 0L && forcedDelay <= 50L, s"delay of the forced event: $forcedDelay")

    // the beat started by the initialization is reset - the second event comes a full interval after the forced one
    val intervalMs = HeartBeatMs.toMillis
    val gap = buffer(1)._2 - buffer(0)._2
    assert(gap >= intervalMs - (intervalMs / 4) && gap <= 3 * intervalMs, s"gap between the forced and the next event: $gap")

    assertEquals(buffer.take(2).map(_._1).toSeq, Seq(1, 2))
    stream.close()
    awaitAllTasks
    assert(stream.isClosed)
  }

  test("force on a closed stream does nothing") {
    var n = 0
    val buffer = mutable.ArrayBuffer[Int]()
    val stream = GeneratorStream.generate(HeartBeatMs) { n += 1; n }
    val oneEvent = new CountDownLatch(1)
    stream.foreach { e =>
      buffer.addOne(e)
      oneEvent.countDown()
    }
    assert(oneEvent.await(Timeout.toMillis, TimeUnit.MILLISECONDS))
    Thread.sleep(20L) // let the beat finish rescheduling itself before closing
    stream.close()
    awaitAllTasks
    assert(stream.isClosed)

    val count = buffer.size
    stream.force()
    Thread.sleep(3 * HeartBeatMs.toMillis)

    assertEquals(buffer.size, count, "force generated an event on a closed stream")
    assert(stream.isClosed)
  }

  test("double force generates two immediate events and resets the beat to the second force") {
    var n = 0
    val buffer = mutable.ArrayBuffer[(Int, Long)]()
    val twoBeats = new CountDownLatch(2)
    val fiveEvents = new CountDownLatch(5)
    val stream = GeneratorStream.generate(HeartBeatMs) { n += 1; n }
    stream.foreach { e =>
      buffer.addOne((e, System.currentTimeMillis))
      if (e <= 2) twoBeats.countDown()
      fiveEvents.countDown()
    }

    assert(twoBeats.await(Timeout.toMillis, TimeUnit.MILLISECONDS), "the stream didn't produce two natural beats")
    Thread.sleep(30L)
    val forcedAt1 = System.currentTimeMillis
    stream.force()
    Thread.sleep(30L)
    val forcedAt2 = System.currentTimeMillis
    stream.force()

    assert(fiveEvents.await(Timeout.toMillis, TimeUnit.MILLISECONDS), "the stream didn't produce three more events")
    stream.close()
    awaitAllTasks
    assert(stream.isClosed)

    val intervalMs = HeartBeatMs.toMillis
    assertEquals(buffer.take(5).map(_._1).toSeq, Seq(1, 2, 3, 4, 5))
    val firstForcedDelay = buffer(2)._2 - forcedAt1
    assert(firstForcedDelay >= 0L && firstForcedDelay <= 50L, s"delay of the first forced event: $firstForcedDelay")
    val secondForcedDelay = buffer(3)._2 - forcedAt2
    assert(secondForcedDelay >= 0L && secondForcedDelay <= 50L, s"delay of the second forced event: $secondForcedDelay")

    // the next natural beat comes a full interval after the second force, not after the first one
    val gap = buffer(4)._2 - buffer(3)._2
    assert(gap >= intervalMs - (intervalMs / 4) && gap <= 3 * intervalMs,
      s"gap between the second forced event and the next beat: $gap")
  }

  test("force on a finite generator emits the next element immediately and closes the stream at the end") {
    var n = 0
    val buffer = mutable.ArrayBuffer[(Int, Long)]()
    val finished = Signal(false)
    val twoBeats = new CountDownLatch(2)
    val gen = GeneratorStream.from(() => {
      n += 1
      if (n <= 5) Some(n) else None
    }, HeartBeatMs)
    gen.onClose(finished ! true)
    gen.foreach { e =>
      buffer.addOne((e, System.currentTimeMillis))
      if (e <= 2) twoBeats.countDown()
    }

    assert(twoBeats.await(Timeout.toMillis, TimeUnit.MILLISECONDS), "the generator didn't produce two elements")
    Thread.sleep(30L)
    val forcedAt = System.currentTimeMillis
    gen.force()

    waitFor(finished, true)
    assert(gen.isClosed)
    assertEquals(buffer.map(_._1).toSeq, Seq(1, 2, 3, 4, 5))

    // the forced element (3) is published almost immediately
    val forcedDelay = buffer(2)._2 - forcedAt
    assert(forcedDelay >= 0L && forcedDelay <= 50L, s"delay of the forced element: $forcedDelay")
    // the beat is reset - the next element comes a full interval after the forced one
    val intervalMs = HeartBeatMs.toMillis
    val gap = buffer(3)._2 - buffer(2)._2
    assert(gap >= intervalMs - (intervalMs / 4) && gap <= 3 * intervalMs,
      s"gap between the forced element and the next one: $gap")
  }

  test("a forced beat with a throwing generate function ignores the exception and resets the beat") {
    var n = 0
    val buffer = mutable.ArrayBuffer[(Int, Long)]()
    val twoBeats = new CountDownLatch(2)
    val twoMore = new CountDownLatch(2)
    val stream = GeneratorStream.generate(HeartBeatMs) {
      n += 1
      if (n == 3) throw new IllegalStateException("boom") else n
    }
    stream.foreach { e =>
      buffer.addOne((e, System.currentTimeMillis))
      if (e <= 2) twoBeats.countDown()
      if (e >= 4) twoMore.countDown()
    }

    assert(twoBeats.await(Timeout.toMillis, TimeUnit.MILLISECONDS), "the stream didn't produce two natural beats")
    Thread.sleep(30L)
    val forcedAt = System.currentTimeMillis
    // the third call to generate throws; no event is published but the exception is ignored
    stream.force()

    assert(twoMore.await(Timeout.toMillis, TimeUnit.MILLISECONDS), "the stream didn't continue after the failed forced beat")
    stream.close()
    awaitAllTasks
    assert(stream.isClosed)

    val intervalMs = HeartBeatMs.toMillis
    // no event with the value 3 was published, and no event was published at the forced beat time
    assertEquals(buffer.take(4).map(_._1).toSeq, Seq(1, 2, 4, 5))
    // the beat was still reset - the next event comes a full interval after the forced beat
    val gap = buffer(2)._2 - forcedAt
    assert(gap >= intervalMs - (intervalMs / 4) && gap <= 3 * intervalMs,
      s"gap between the forced beat and the next event: $gap")
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
