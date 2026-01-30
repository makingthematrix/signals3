package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.testutils.{awaitAllTasks, waitForResult}
import io.github.makingthematrix.signals3.{DoneSignal, EventContext, FlagSignal, Signal, Threading}

import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.concurrent.duration.*

class GeneratorSignalSpec extends munit.FunSuite {
  import EventContext.Implicits.global
  import Threading.defaultContext

  def fibDelay(t: (Int, Int)): FiniteDuration = FiniteDuration(t._2 * 200L, TimeUnit.MILLISECONDS)
  
  test("fibonacci signal with generate") {
    val builder = mutable.ArrayBuilder.make[Int]
    val isSuccess = DoneSignal()
  
    val signal = GeneratorSignal.generate((0, 1), 200.millis) { case (a, b) => (b, a + b) }
    signal.foreach { case (_, b) =>
      builder.addOne(b)
      if (b == 8) isSuccess.done()
    }
    waitForResult(isSuccess, true)
    signal.close()
    awaitAllTasks
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
    waitForResult(isSuccess, true)
    signal.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(1, 2, 3, 5, 8))
    assert(signal.isClosed)
  }
  
  test("fibonacci signal with delays also in fibonacci") {
    val builder = mutable.ArrayBuilder.make[Int]
    val now = System.currentTimeMillis
    val isSuccess = Signal(false)

    val signal = GeneratorSignal.generate((0, 1), (t: (Int, Int)) => fibDelay(t)) { case (a, b) => (b, a + b) }
    signal.foreach { case (a, b) =>
      builder.addOne(b)
      isSuccess ! (b == 5)
    }
  
    waitForResult(isSuccess, true)

    val totalTime = System.currentTimeMillis - now
    signal.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(1, 1, 2, 3, 5))
    // it should take (100 + 100 + 200 + 300)ms * 2 + 100ms for a buffer, but still it can be flaky
    assert(totalTime >= 1400 && totalTime <= 1500L, s"total time: $totalTime")
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

    waitForResult(isSuccess, true)

    val totalTime = System.currentTimeMillis - now
    signal.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(1, 2, 3, 5))
    // it should take (100 + 200 + 300 + 500)ms * 2 + 100ms for a buffer, but still it can be flaky
    assert(totalTime >= 2200 && totalTime <= 2500L, s"total time: $totalTime")
    assert(signal.isClosed)
  }

  test("add pause to counter generator signal") {
    val isSuccess = Signal(false)
    val signal1 = GeneratorSignal.counter(200.millis)
    signal1.foreach { counter =>
      isSuccess ! (counter == 4)
    }
    val t1 = System.currentTimeMillis
    waitForResult(isSuccess, true)
    val timePassed1 = System.currentTimeMillis - t1
    signal1.close()
    awaitAllTasks

    // restart, this time with pause for one beat when counter == 2
    isSuccess ! false
    waitForResult(isSuccess, false)

    var pausedOn = 0L

    def paused(counter: Int): Boolean =
      if (counter == 2 && pausedOn == 0L) {
        pausedOn = System.currentTimeMillis
        true
      } else {
        System.currentTimeMillis - pausedOn < 300L
      }
    val signal2 = GeneratorSignal(0, (n: Int) => n + 1, 200.millis, paused)

    signal2.foreach { counter =>
      isSuccess ! (counter == 4)
    }

    val t2 = System.currentTimeMillis
    waitForResult(isSuccess, true)
    val timePassed2 = System.currentTimeMillis - t2
    signal2.close()
    awaitAllTasks
    assert(timePassed2 - timePassed1 >= 200, timePassed2)
    assert(pausedOn > 0L)
  }

  test("Close the generator signal automatically") {
    import scala.util.Using
    val arr = mutable.ArrayBuilder.make[Int]
    Using(GeneratorSignal.counter(200.millis)) { sig =>
      sig.foreach(n => arr.addOne(n))
      Thread.sleep(500L)
    }
    val res1 = arr.result().toSeq
    assert(res1.nonEmpty)
    // the generator itself is not available here anymore (as it should be)
    // so to check if it's closed we wait a little and see if a new value was generated (it shouldn't be)
    Thread.sleep(500L)
    val res2 = arr.result().toSeq
    assertEquals(res1, res2)
  }

  test("Close the generator stream automatically") {
    import scala.util.Using
    val arr = mutable.ArrayBuilder.make[Int]
    Using(GeneratorStream.repeat(1, 200.millis)) { stream =>
      stream.foreach(n => arr.addOne(n))
      Thread.sleep(500L)
    }
    val res1 = arr.result().toSeq
    assert(res1.nonEmpty)
    // the generator itself is not available here anymore (as it should be)
    // so to check if it's closed we wait a little and see if a new value was generated (it shouldn't be)
    Thread.sleep(500L)
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

    waitForResult(done, true)
    signal.close()
    awaitAllTasks
    assertEquals(last, 4)
  }

  test("from(Iterable[V], interval) publishes all values and then closes") {
    val values = Seq(10, 20, 30, 40)
    val buf = mutable.ArrayBuffer[Int]()
    val done = FlagSignal()
    val sig = GeneratorSignal.from(values, 50.millis)

    sig.foreach { v =>
      buf.addOne(v)
      done.setIf(v == values.last)
    }

    waitForResult(done, true)
    awaitAllTasks
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

    waitForResult(finished, true)
    awaitAllTasks
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
    }, 60.millis)

    sig.onClose(finished ! true)
    sig.foreach(buf.addOne)

    waitForResult(finished, true)
    awaitAllTasks
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

    waitForResult(done, true)
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

    waitForResult(finishedInit, true)
    // Also ensure the underlying generator completes
    val allDone = Signal(false)
    sig.onClose(allDone ! true)
    waitForResult(allDone, true)
    awaitAllTasks

    assertEquals(bufInit.toSeq, values.init)
    assert(sig.isClosed)
  }

  test("CloseableGeneratorSignal.onClose is invoked on close") {
    var closed = false
    val sig = GeneratorSignal(0, (n: Int) => n + 1, 80.millis)
    sig.onClose { closed = true }
    val done = FlagSignal()
    var cnt = 0
    sig.foreach { _ =>
      cnt += 1
      done.setIf(cnt == 2)
    }

    waitForResult(done, true)
    sig.close()
    awaitAllTasks
    assert(sig.isClosed)
    assert(closed)
  }

  test("closing the generator signal twice returns false") {
    val sig = GeneratorSignal.counter(100.millis)
    assert(sig.closeAndCheck())
    assert(sig.isClosed)
    assert(!sig.closeAndCheck())
  }
}
