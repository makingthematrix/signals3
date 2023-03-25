package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.testutils.{awaitAllTasks, waitForResult}
import io.github.makingthematrix.signals3.{EventContext, Signal, Threading}

import scala.collection.mutable
import scala.concurrent.duration.*

class GeneratorSignalSpec extends munit.FunSuite:
  import EventContext.Implicits.global
  import Threading.defaultContext
  
  test("fibonacci signal with generate") {
    val builder = mutable.ArrayBuilder.make[Int]
    val isSuccess = Signal(false)
  
    val signal = GeneratorSignal.generate((0, 1), 200.millis) { case (a, b) => (b, a + b) }
    signal.foreach { case (_, b) =>
      builder.addOne(b)
      isSuccess ! (b == 8)
    }
    waitForResult(isSuccess, true)
    signal.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(1, 1, 2, 3, 5, 8))
  }

  test("fibonacci signal with unfold") {
    val builder = mutable.ArrayBuilder.make[Int]
    val isSuccess = Signal(false)

    val signal = GeneratorSignal.unfold((0, 1), 200.millis) { case (a, b) => (b, a + b) -> b }
    signal.foreach { b =>
      builder.addOne(b)
      isSuccess ! (b == 8)
    }
    waitForResult(isSuccess, true)
    signal.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(1, 2, 3, 5, 8))
    assert(signal.isClosed)
  }
  
  test("fibonacci signal with delays also in fibonacci") {
    def fibDelay(t: (Int, Int)): Long = t._2 * 200L
  
    val builder = mutable.ArrayBuilder.make[Int]
    val now = System.currentTimeMillis
    val isSuccess = Signal(false)

    val signal = GeneratorSignal.generateVariant((0, 1), fibDelay) { case (a, b) => (b, a + b) }
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

  test("fibonacci signal with unfoldVariant with delays also in fibonacci") {
    def fibDelay(t: (Int, Int)): Long = t._2 * 200L

    val builder = mutable.ArrayBuilder.make[Int]
    val now = System.currentTimeMillis
    val isSuccess = Signal(false)
    
    val signal = GeneratorSignal.unfoldVariant((0, 1), fibDelay) { case (a, b) => (b, a + b) -> b }
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
    assert(totalTime >= 2200 && totalTime <= 2300L, s"total time: $totalTime")
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
      if counter == 2 && pausedOn == 0L then
        pausedOn = System.currentTimeMillis
        true
      else
        System.currentTimeMillis - pausedOn < 300L

    val signal2 = GeneratorSignal(0, _ + 1, 200.millis, paused)
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
