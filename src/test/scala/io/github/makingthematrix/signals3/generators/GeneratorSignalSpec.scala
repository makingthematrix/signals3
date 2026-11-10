package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.priv.DoneSignal
import io.github.makingthematrix.signals3.Utils.{awaitAllTasks, waitFor}
import io.github.makingthematrix.signals3.{DispatchQueue, EventContext, FlagSignal, SerialDispatchQueue, Signal}

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.collection.mutable
import scala.concurrent.duration.*

class GeneratorSignalSpec extends munit.FunSuite {
  private val eventContext = EventContext()
  given dq: DispatchQueue = SerialDispatchQueue()

  override def beforeEach(context: BeforeEach): Unit =
    eventContext.start()

  override def afterEach(context: AfterEach): Unit =
    eventContext.stop()

  given Timeout: FiniteDuration = 3.seconds
  val HeartBeatMs: FiniteDuration = 100.millis

  def fibDelay(t: (Int, Int)): FiniteDuration = FiniteDuration(t._2 * HeartBeatMs.toMillis, TimeUnit.MILLISECONDS)

  test("fibonacci signal with generate") {
    val builder = mutable.ArrayBuilder.make[Int]
    val isSuccess = DoneSignal()
  
    val signal = GeneratorSignal.generate((0, 1), HeartBeatMs) { case (a, b) => (b, a + b) }
    signal.foreach { case (_, b) =>
      builder.addOne(b)
      if (b == 8) isSuccess.done()
    }
    waitFor(isSuccess, true)
    signal.close()
    assertEquals(builder.result().toSeq, Seq(1, 1, 2, 3, 5, 8))
  }

  test("fibonacci signal with unfold") {
    val builder = mutable.ArrayBuilder.make[Int]
    val isSuccess = DoneSignal()

    val signal =
      GeneratorSignal.unfold((0, 1), 200.millis) { case (a, b) => (b, a + b) -> b }
    signal.foreach { b =>
      builder.addOne(b)
      isSuccess.doneIf(b == 8)
    }
    waitFor(isSuccess, true)
    signal.close()
    assertEquals(builder.result().toSeq, Seq(1, 2, 3, 5, 8))
    assert(signal.isClosed)
  }
  
  test("fibonacci signal with delays also in fibonacci") {
    val builder = mutable.ArrayBuilder.make[Int]
    val now = System.currentTimeMillis
    val isSuccess = Signal(false)

    val signal = GeneratorSignal.generate((0, 1), (t: (Int, Int)) => fibDelay(t)) { case (a, b) => (b, a + b) }
    signal.foreach { case (_, b) =>
      builder.addOne(b)
      isSuccess ! (b == 5)
    }
  
    waitFor(isSuccess, true)

    val totalTime = System.currentTimeMillis - now
    signal.close()
    assertEquals(builder.result().toSeq, Seq(1, 1, 2, 3, 5))
    // it should take (100 + 100 + 200 + 300)ms + 100ms for a buffer, but still it can be flaky
    assert(totalTime >= 700 && totalTime <= 1000L, s"total time: $totalTime")
  }

  test("fibonacci signal with variant unfold with delays also in fibonacci") {
    val builder = mutable.ArrayBuilder.make[Int]
    val now = System.currentTimeMillis
    val isSuccess = Signal(false)
    
    val signal = GeneratorSignal.unfold((0, 1), (t: (Int, Int)) => fibDelay(t)) { case (a, b) => (b, a + b) -> b }
    signal.foreach { b =>
      builder.addOne(b)
      isSuccess ! (b == 5)
    }

    waitFor(isSuccess, true)

    val totalTime = System.currentTimeMillis - now
    signal.close()
    assertEquals(builder.result().toSeq, Seq(1, 2, 3, 5))
    // it should take (100 + 200 + 300 + 500)ms + 100ms for a buffer, but still it can be flaky
    assert(totalTime >= 1100 && totalTime <= 1500L, s"total time: $totalTime")
    assert(signal.isClosed)
  }

  test("add pause to counter generator signal") {
    val isSuccess = Signal(false)
    val signal1 = GeneratorSignal.counter(HeartBeatMs)
    signal1.foreach { counter =>
      isSuccess ! (counter == 4)
    }
    val t1 = System.currentTimeMillis
    waitFor(isSuccess, true)
    val timePassed1 = System.currentTimeMillis - t1
    signal1.close()
    awaitAllTasks

    // restart, this time with pause for one beat when counter == 2
    isSuccess ! false
    waitFor(isSuccess, false)

    val signal2 = GeneratorSignal(0, (n: Int) => n + 1, HeartBeatMs)

    var pausedOn = 0L

    signal2.foreach {
      case 2 if pausedOn == 0L =>
        pausedOn = System.currentTimeMillis
        signal2.pause()
      case _ =>
        if (System.currentTimeMillis - pausedOn < 150L) signal2.pause() else signal2.unpause()
    }

    signal2.foreach { counter =>
      isSuccess ! (counter == 4)
    }

    val t2 = System.currentTimeMillis
    waitFor(isSuccess, true)
    val timePassed2 = System.currentTimeMillis - t2
    signal2.close()
    awaitAllTasks
    assert(timePassed2 - timePassed1 >= HeartBeatMs.toMillis, timePassed2)
    assert(pausedOn > 0L)
  }

  test("Close the generator signal automatically") {
    import scala.util.Using
    val arr = mutable.ArrayBuilder.make[Int]
    Using(GeneratorSignal.counter(HeartBeatMs)) { sig =>
      sig.foreach(n => arr.addOne(n))
      Thread.sleep(HeartBeatMs.toMillis * 2L)
    }
    val res1 = arr.result().toSeq
    assert(res1.nonEmpty)
    // the generator itself is not available here anymore (as it should be)
    // so to check if it's closed we wait a little and see if a new value was generated (it shouldn't be)
    Thread.sleep(HeartBeatMs.toMillis * 2L)
    val res2 = arr.result().toSeq
    assertEquals(res1, res2)
  }

  test("Close the generator stream automatically") {
    import scala.util.Using
    val arr = mutable.ArrayBuilder.make[Int]
    Using(GeneratorStream.repeat(1, HeartBeatMs)) { stream =>
      stream.foreach(n => arr.addOne(n))
      Thread.sleep(HeartBeatMs.toMillis * 2L)
    }
    val res1 = arr.result().toSeq
    assert(res1.nonEmpty)
    // the generator itself is not available here anymore (as it should be)
    // so to check if it's closed we wait a little and see if a new value was generated (it shouldn't be)
    Thread.sleep(HeartBeatMs.toMillis * 2L)
    val res2 = arr.result().toSeq
    assertEquals(res1, res2)
  }

  test("counter with function interval emits increasing values") {
    var last = 0
    val done = FlagSignal()
    val signal = GeneratorSignal.counter((n: Int) => (100 + n * 50).millis)

    signal.foreach { n =>
      last = n
      done.setIf(n >= 4)
    }

    waitFor(done, true)
    signal.close()
    assertEquals(last, 4)
  }

  test("from(Iterable[V], interval) publishes all values and then closes") {
    val values = Seq(10, 20, 30, 40)
    val buf = mutable.ArrayBuffer[Int]()
    val done = FlagSignal()
    val sig = GeneratorSignal.from(values, (HeartBeatMs.toMillis / 4L).millis)

    sig.foreach { v =>
      buf.addOne(v)
      done.setIf(v == values.last)
    }

    waitFor(done, true)
    assert(sig.isClosed)
    // FiniteGeneratorSignal starts with the first value; foreach records subsequent changes as they occur.
    // The collected sequence should contain all values, in order, ending with the last one.
    assertEquals(buf.toSeq, values)
  }

  test("from(() => Option[V], interval) emits until None and then closes") {
    var n = 0
    val buf = mutable.ArrayBuffer[Int]()
    val finished = Signal(false)
    val sig = GeneratorSignal.from(() => {
      n += 1
      if (n <= 5) Some(n) else None
    }, 40.millis)

    sig.onClose(finished ! true)
    sig.foreach(buf.addOne)

    waitFor(finished, true)(using duration = 1.seconds)
    assert(sig.isClosed)
    assertEquals(buf.toSeq, Seq(1, 2, 3, 4, 5))
  }

  test("FiniteGeneratorSignal.apply(() => Option[V], interval) works the same") {
    var n = 0
    val buf = mutable.ArrayBuffer[Int]()
    val finished = Signal(false)
    val sig: FiniteGeneratorSignal[Int] = FiniteGeneratorSignal(() => {
      n += 1
      if (n <= 3) Some(n) else None
    }, 40.millis)

    sig.onClose(finished ! true)
    sig.foreach(buf.addOne)

    waitFor(finished, true)(using duration = 1.seconds)
    assert(sig.isClosed)
    assertEquals(buf.toSeq, Seq(1, 2, 3))
  }

  test("from(LazyList[V], interval) with take collects first N values") {
    val buf = mutable.ArrayBuffer[Int]()
    val done = FlagSignal()
    val sig = GeneratorSignal.from(LazyList.from(1), 30.millis)
    val firstFive = sig.take(5)

    firstFive.foreach { v =>
      buf.addOne(v)
      done.setIf(v == 5)
    }

    waitFor(done, true)
    assertEquals(buf.toSeq, Seq(1, 2, 3, 4, 5))
  }

  test("FiniteGeneratorSignal.init publishes all but the last value") {
    val values = Seq(7, 9, 11, 13)
    val bufInit = mutable.ArrayBuffer[Int]()
    val finishedInit = FlagSignal()
    val sig = GeneratorSignal.from(values, 40.millis)

    sig.init.foreach { v =>
      bufInit.addOne(v)
      finishedInit.setIf(v == values.init.last)
    }

    waitFor(finishedInit, true)(using duration = 1.seconds)
    // Also ensure the underlying generator completes
    val allDone = Signal(false)
    sig.onClose(allDone ! true)
    waitFor(allDone, true)(using duration = 1.seconds)

    assertEquals(bufInit.toSeq, values.init)
    assert(sig.isClosed)
  }

  test("CloseableGeneratorSignal.onClose is invoked on close") {
    var closed = false
    val sig = GeneratorSignal(0, (n: Int) => n + 1, 40.millis)
    sig.onClose { closed = true }
    val done = FlagSignal()
    var cnt = 0
    sig.foreach { _ =>
      cnt += 1
      done.setIf(cnt == 2)
    }

    waitFor(done, true)
    sig.close()
    assert(sig.isClosed)
    assert(closed)
  }

  test("closing the generator signal twice returns false") {
    val sig = GeneratorSignal.counter(HeartBeatMs)
    assert(sig.closeAndCheck())
    assert(sig.isClosed)
    assert(!sig.closeAndCheck())
  }

  test("force updates the generator signal immediately and resets the beat") {
    val buffer = mutable.ArrayBuffer[(Int, Long)]()
    val twoBeats = new CountDownLatch(2)
    val fourBeats = new CountDownLatch(4)
    // the update function is instrumented with timestamps because the signal publishes values
    // only on change, so the subscribers can't be used to observe every beat
    val signal = GeneratorSignal.generate(0, HeartBeatMs) { v =>
      val nv = v + 1
      buffer.addOne((nv, System.currentTimeMillis))
      if (nv <= 2) twoBeats.countDown()
      fourBeats.countDown()
      nv
    }
    val done = FlagSignal()
    signal.foreach(n => done.setIf(n == 4))

    assert(twoBeats.await(Timeout.toMillis, TimeUnit.MILLISECONDS), "the signal wasn't updated twice")
    Thread.sleep(30L)
    val forcedAt = System.currentTimeMillis
    signal.force()

    assert(fourBeats.await(Timeout.toMillis, TimeUnit.MILLISECONDS), "the signal wasn't updated twice more")
    signal.close()
    awaitAllTasks
    assert(signal.isClosed)

    waitFor(done, true)
    val intervalMs = HeartBeatMs.toMillis
    assertEquals(buffer.take(4).map(_._1).toSeq, Seq(1, 2, 3, 4))
    // the forced update (3) happened almost immediately
    val forcedDelay = buffer(2)._2 - forcedAt
    assert(forcedDelay >= 0L && forcedDelay <= 50L, s"delay of the forced update: $forcedDelay")
    // the beat is reset - the next update comes a full interval after the forced one
    val gap = buffer(3)._2 - buffer(2)._2
    assert(gap >= intervalMs - (intervalMs / 4) && gap <= 3 * intervalMs,
      s"gap between the forced update and the next one: $gap")
  }

  test("force with the function interval re-invokes the interval function with the forced value") {
    val buffer = mutable.ArrayBuffer[(Int, Long)]()
    val intervalValues = mutable.ArrayBuffer[Int]()
    val twoBeats = new CountDownLatch(2)
    val fourBeats = new CountDownLatch(4)
    val signal = GeneratorSignal.generate(0, (n: Int) => { intervalValues.addOne(n); HeartBeatMs }) { v =>
      val nv = v + 1
      buffer.addOne((nv, System.currentTimeMillis))
      if (nv <= 2) twoBeats.countDown()
      fourBeats.countDown()
      nv
    }
    val done = FlagSignal()
    signal.foreach(n => done.setIf(n == 4))

    assert(twoBeats.await(Timeout.toMillis, TimeUnit.MILLISECONDS), "the signal wasn't updated twice")
    Thread.sleep(30L)
    val forcedAt = System.currentTimeMillis
    signal.force()

    // the interval function was re-invoked by the restart, with the forced value as the argument
    assertEquals(intervalValues.take(4).toSeq, Seq(0, 1, 2, 3))

    assert(fourBeats.await(Timeout.toMillis, TimeUnit.MILLISECONDS), "the signal wasn't updated twice more")
    signal.close()
    awaitAllTasks
    assert(signal.isClosed)

    waitFor(done, true)
    val intervalMs = HeartBeatMs.toMillis
    assertEquals(buffer.take(4).map(_._1).toSeq, Seq(1, 2, 3, 4))
    val forcedDelay = buffer(2)._2 - forcedAt
    assert(forcedDelay >= 0L && forcedDelay <= 50L, s"delay of the forced update: $forcedDelay")
    val gap = buffer(3)._2 - buffer(2)._2
    assert(gap >= intervalMs - (intervalMs / 4) && gap <= 3 * intervalMs,
      s"gap between the forced update and the next one: $gap")
  }

  test("force on a closed signal does nothing") {
    var updates = 0
    val oneBeat = new CountDownLatch(1)
    val signal = GeneratorSignal.generate(0, HeartBeatMs) { v =>
      updates += 1
      v + 1
    }
    signal.foreach { _ => oneBeat.countDown() }
    assert(oneBeat.await(Timeout.toMillis, TimeUnit.MILLISECONDS), "the signal wasn't updated")
    Thread.sleep(20L) // let the beat finish rescheduling itself before closing
    signal.close()
    awaitAllTasks
    assert(signal.isClosed)

    val count = updates
    signal.force()
    Thread.sleep(3 * HeartBeatMs.toMillis)

    assertEquals(updates, count, "force updated the signal after it was closed")
    assert(signal.isClosed)
  }

  test("force when the update returns an equal value doesn't publish but resets the beat") {
    val beatTimes = mutable.ArrayBuffer[Long]()
    var observed = 0
    val twoBeats = new CountDownLatch(2)
    val fourBeats = new CountDownLatch(4)
    val signal = GeneratorSignal.generate("constant", HeartBeatMs) { v =>
      beatTimes.addOne(System.currentTimeMillis)
      if (beatTimes.size <= 2) twoBeats.countDown()
      fourBeats.countDown()
      v
    }
    signal.foreach(_ => observed += 1)

    assert(twoBeats.await(Timeout.toMillis, TimeUnit.MILLISECONDS), "the signal wasn't updated twice")
    Thread.sleep(30L)
    val forcedAt = System.currentTimeMillis
    signal.force()

    assert(fourBeats.await(Timeout.toMillis, TimeUnit.MILLISECONDS), "the signal wasn't updated twice more")
    signal.close()
    awaitAllTasks
    assert(signal.isClosed)

    val intervalMs = HeartBeatMs.toMillis
    // the update never changes the value, so the subscriber was notified only about the initial one
    assertEquals(observed, 1)
    // but the beat itself still runs and is reset by force
    val forcedDelay = beatTimes(2) - forcedAt
    assert(forcedDelay >= 0L && forcedDelay <= 50L, s"delay of the forced beat: $forcedDelay")
    val gap = beatTimes(3) - beatTimes(2)
    assert(gap >= intervalMs - (intervalMs / 4) && gap <= 3 * intervalMs,
      s"gap between the forced beat and the next one: $gap")
  }

  test("pause and unpause are ignored after close") {
    val signal = GeneratorSignal.generate(0, HeartBeatMs)(_ + 1)

    signal.close()
    assert(signal.isClosed)

    // pause on a closed generator is ignored
    signal.pause()
    assert(!signal.isPaused)

    // unpause on a closed generator is ignored too
    signal.unpause()
    assert(!signal.isPaused)
  }

  test("unpause after close keeps the generator paused") {
    val signal = GeneratorSignal.generate(0, HeartBeatMs)(_ + 1)
    signal.pause()
    assert(signal.isPaused)

    signal.close()
    assert(signal.isClosed)

    // unpause after close is ignored - the generator stays paused
    signal.unpause()
    assert(signal.isPaused)
  }
}
