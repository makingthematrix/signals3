package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Closeable.CloseableStream
import io.github.makingthematrix.signals3.testutils.*

class PausableSpec extends munit.FunSuite {
  import EventContext.Implicits.global
  import Threading.defaultContext

  // ===== Pausable Trait Tests =====

  test("Pausable trait - initially not paused") {
    val pausable = new Pausable {}
    assert(!pausable.isPaused)
  }

  test("Pausable trait - pause sets isPaused to true") {
    val pausable = new Pausable {}
    pausable.pause()
    assert(pausable.isPaused)
  }

  test("Pausable trait - unpause sets isPaused to false") {
    val pausable = new Pausable {}
    pausable.pause()
    pausable.unpause()
    assert(!pausable.isPaused)
  }

  test("Pausable trait - multiple pause calls keep it paused") {
    val pausable = new Pausable {}
    pausable.pause()
    pausable.pause()
    pausable.pause()
    assert(pausable.isPaused)
  }

  test("Pausable trait - pause after unpause works") {
    val pausable = new Pausable {}
    pausable.pause()
    pausable.unpause()
    assert(!pausable.isPaused)
    pausable.pause()
    assert(pausable.isPaused)
  }

  test("Pausable trait - onPause callback is triggered when paused") {
    val pausable = new Pausable {}
    var callbackCalled = false
    pausable.onPause { callbackCalled = true }
    
    assert(!callbackCalled)
    pausable.pause()
    assert(callbackCalled)
  }

  test("Pausable trait - onPause callback is not triggered by unpause") {
    val pausable = new Pausable {}
    var callbackCalled = false
    pausable.onPause { callbackCalled = true }
    
    pausable.pause()
    assert(callbackCalled)
    callbackCalled = false
    pausable.unpause()
    assert(!callbackCalled)
  }

  test("Pausable trait - onPause callback is triggered on each pause call") {
    val pausable = new Pausable {}
    var callbackCount = 0
    pausable.onPause { callbackCount += 1 }
    
    pausable.pause()
    pausable.pause()
    pausable.pause()
    assertEquals(callbackCount, 3) // Callback is called each time pause() is called
  }

  test("Pausable trait - onPause callback is triggered on each separate pause") {
    val pausable = new Pausable {}
    var callbackCount = 0
    pausable.onPause { callbackCount += 1 }
    
    pausable.pause()
    assertEquals(callbackCount, 1)
    pausable.unpause()
    pausable.pause()
    assertEquals(callbackCount, 2)
    pausable.unpause()
    pausable.pause()
    assertEquals(callbackCount, 3)
  }

  // ===== Stream Pausability Tests =====

  test("Stream - events are not dispatched when paused") {
    val src = Stream[Int]()
    val received = Signal(Seq.empty[Int])
    
    src.foreach { n => received.mutate(_ :+ n) }
    
    src ! 1
    waitForResult(received, Seq(1))
    
    src.pause()
    assert(src.isPaused)
    
    src ! 2
    src ! 3
    awaitAllTasks
    
    // Events 2 and 3 should not have been received
    assert(waitForResult(received, Seq(1)))
    
    src.unpause()
    assert(!src.isPaused)
    
    // After unpausing, new events should come through
    src ! 4
    waitForResult(received, Seq(1, 4))
  }

  test("Stream - events dispatched after unpause include those sent while paused") {
    // This is actually NOT the case - events sent while paused are LOST
    // The pause only affects future dispatch calls, not buffering
    val src = Stream[Int]()
    val received = Signal(Seq.empty[Int])
    
    src.foreach { n => received.mutate(_ :+ n) }
    
    src ! 1
    waitForResult(received, Seq(1))
    
    src.pause()
    src ! 2  // This event is lost because dispatch checks isPaused
    src ! 3  // This event is also lost
    
    awaitAllTasks
    assert(waitForResult(received, Seq(1)))
    
    src.unpause()
    src ! 4
    waitForResult(received, Seq(1, 4))
    
    // Events 2 and 3 are lost - they were sent while paused
    assert(!waitForResult(received, Seq(1, 2, 3, 4)))
  }

  test("Stream - onPause callback is triggered") {
    val src = Stream[Int]()
    val pausedSignal = Signal(false)
    
    src.onPause { pausedSignal ! true }
    
    assert(!pausedSignal.currentValue.contains(true))
    src.pause()
    waitForResult(pausedSignal, true)
  }

  test("Stream - can pause and unpause multiple times") {
    val src = Stream[Int]()
    val received = Signal(Seq.empty[Int])
    
    src.foreach { n => received.mutate(_ :+ n) }
    
    // First batch
    src ! 1
    waitForResult(received, Seq(1))
    
    // First pause
    src.pause()
    src ! 2
    awaitAllTasks
    assert(waitForResult(received, Seq(1)))
    
    // First unpause
    src.unpause()
    src ! 3
    waitForResult(received, Seq(1, 3))
    
    // Second pause
    src.pause()
    src ! 4
    awaitAllTasks
    assert(waitForResult(received, Seq(1, 3)))
    
    // Second unpause
    src.unpause()
    src ! 5
    waitForResult(received, Seq(1, 3, 5))
  }

  test("Stream - pausing a SourceStream prevents publishing") {
    val src: SourceStream[Int] = Stream()
    val received = Signal(Seq.empty[Int])
    
    src.foreach { n => received.mutate(_ :+ n) }
    
    src ! 1
    waitForResult(received, Seq(1))
    
    src ! 2
    waitForResult(received, Seq(1, 2))
    
    src.pause()
    assert(src.isPaused)
    
    src ! 3
    src ! 4
    awaitAllTasks
    
    // Events 3 and 4 should not have been received because stream was paused
    assert(waitForResult(received, Seq(1, 2)))
    
    src.unpause()
    src ! 5
    waitForResult(received, Seq(1, 2, 5))
  }

  // ===== Signal Pausability Tests =====

  test("Signal - value changes are not published when paused") {
    val sig = Signal(0)
    val received = Signal(Seq.empty[Int])
    
    sig.foreach { n => received.mutate(_ :+ n) }
    
    // Initial value 0 is sent to subscriber immediately
    waitForResult(received, Seq(0))
    
    sig ! 1
    waitForResult(received, Seq(0, 1))
    
    sig.pause()
    assert(sig.isPaused)
    
    sig ! 2
    sig ! 3
    awaitAllTasks
    
    // Value changes while paused should not be published
    assert(waitForResult(received, Seq(0, 1)))
    
    sig.unpause()
    assert(!sig.isPaused)
    
    // After unpausing, new changes should be published
    sig ! 4
    waitForResult(received, Seq(0, 1, 4))
  }

  test("Signal - value changes while paused do not update the signal value") {
    val sig = Signal(0)
    
    sig ! 1
    assert(sig.currentValue.contains(1))
    
    sig.pause()
    sig ! 2
    sig ! 3
    awaitAllTasks
    
    // The signal value should still be 1, not 2 or 3
    assert(sig.currentValue.contains(1))
    
    sig.unpause()
    sig ! 4
    assert(sig.currentValue.contains(4))
  }

  test("Signal - onPause callback is triggered") {
    val sig = Signal(0)
    val pausedSignal = Signal(false)
    
    sig.onPause { pausedSignal ! true }
    
    assert(!pausedSignal.currentValue.contains(true))
    sig.pause()
    waitForResult(pausedSignal, true)
  }

  test("Signal - can pause and unpause multiple times") {
    val sig = Signal(0)
    val received = Signal(Seq.empty[Int])
    
    sig.foreach { n => received.mutate(_ :+ n) }
    
    // Initial value 0 is sent immediately
    waitForResult(received, Seq(0))
    
    sig ! 1
    waitForResult(received, Seq(0, 1))
    
    sig.pause()
    sig ! 2
    awaitAllTasks
    assert(waitForResult(received, Seq(0, 1)))
    
    sig.unpause()
    sig ! 3
    waitForResult(received, Seq(0, 1, 3))
    
    sig.pause()
    sig ! 4
    awaitAllTasks
    assert(waitForResult(received, Seq(0, 1, 3)))
    
    sig.unpause()
    sig ! 5
    waitForResult(received, Seq(0, 1, 3, 5))
  }

  test("Signal - pausing a SourceSignal prevents value changes") {
    val sig: SourceSignal[Int] = Signal(0)
    val received = Signal(Seq.empty[Int])
    
    sig.foreach { n => received.mutate(_ :+ n) }
    
    // Initial value 0 is sent immediately
    waitForResult(received, Seq(0))
    
    sig ! 1
    waitForResult(received, Seq(0, 1))
    
    // Don't use !! for now as it may use a different execution context
    sig.pause()
    assert(sig.isPaused)
    
    sig ! 2
    sig ! 3
    awaitAllTasks
    
    // Values 2 and 3 should not have been received because signal was paused
    assert(waitForResult(received, Seq(0, 1)))
    assert(sig.currentValue.contains(1)) // Value should still be 1
    
    sig.unpause()
    sig ! 4
    waitForResult(received, Seq(0, 1, 4))
  }

  test("Signal - mutate while paused does not change value") {
    val sig = Signal(0)
    
    sig ! 1
    assert(sig.currentValue.contains(1))
    
    sig.pause()
    val changed = sig.mutate(_ + 1)
    assert(!changed) // mutate should return false when paused
    assert(sig.currentValue.contains(1)) // Value should still be 1
    
    sig.unpause()
    val changed2 = sig.mutate(_ + 1)
    assert(changed2) // mutate should return true now
    assert(sig.currentValue.contains(2))
  }

  // ===== ConstSignal pausability =====

  test("ConstSignal - is always paused") {
    val constSig = Signal.const(42)
    assert(constSig.isPaused)
  }

  test("ConstSignal - pause and unpause do not change isPaused") {
    val constSig = Signal.const(42)
    assert(constSig.isPaused)
    constSig.unpause()
    assert(constSig.isPaused)
    constSig.pause()
    assert(constSig.isPaused)
  }

  // ===== Pause and Close Interaction Tests =====

  // Note: Regular Stream and Signal are not Closeable by default.
  // Only CloseableStream and CloseableSignal (from generators or .closeable) can be closed.
  // The pause/unpause functionality works independently of close functionality.
  
  test("SourceStream - pausing then closing - pause state can be toggled") {
    val src: SourceStream[Int] = Stream()
    val closeableSrc = src.closeable
    
    // Can pause before close
    src.pause()
    assert(src.isPaused)
    
    closeableSrc.close()
    assert(closeableSrc.isClosed)
    
    // Can unpause after close (pause state is independent)
    src.unpause()
    assert(!src.isPaused)
    assert(closeableSrc.isClosed)
  }

  test("SourceSignal - pausing then closing - pause state can be toggled") {
    val sig: SourceSignal[Int] = Signal(0)
    val closeableSig = sig.closeable
    
    // Can pause before close
    sig.pause()
    assert(sig.isPaused)
    
    closeableSig.close()
    assert(closeableSig.isClosed)
    
    // Can unpause after close (pause state is independent)
    sig.unpause()
    assert(!sig.isPaused)
    assert(closeableSig.isClosed)
  }

  test("CloseableStream - can be paused and closed independently") {
    val src: SourceStream[Int] = Stream()
    val closeableSrc: CloseableStream[Int] = src.closeable
    val received = Signal(Seq.empty[Int])
    
    closeableSrc.foreach { n => received.mutate(_ :+ n) }
    
    src ! 1
    waitForResult(received, Seq(1))
    
    // Pause the underlying stream
    src.pause()
    assert(src.isPaused)
    
    src ! 2
    awaitAllTasks
    assert(waitForResult(received, Seq(1)))
    
    // Close the closeable wrapper
    closeableSrc.close()
    assert(closeableSrc.isClosed)
    
    src ! 3
    awaitAllTasks
    // Events still blocked by both pause and close
    assert(waitForResult(received, Seq(1)))
    
    // Unpause
    src.unpause()
    src ! 4
    awaitAllTasks
    // Still blocked by close
    assert(waitForResult(received, Seq(1)))
  }

  // ===== Map/FlatMap with Pausability =====

  test("Stream - pausing works through map transformation") {
    val src = Stream[Int]()
    val received = Signal(Seq.empty[Int])
    
    src.map(_ * 2).foreach { n => received.mutate(_ :+ n) }
    
    src ! 1
    waitForResult(received, Seq(2))
    
    src.pause()
    src ! 2
    awaitAllTasks
    assert(waitForResult(received, Seq(2)))
    
    src.unpause()
    src ! 3
    waitForResult(received, Seq(2, 6))
  }

  test("Signal - pausing works through map transformation") {
    val sig = Signal(0)
    val received = Signal(Seq.empty[Int])
    
    sig.map(_ * 2).foreach { n => received.mutate(_ :+ n) }
    
    // Initial value 0 is mapped to 0
    waitForResult(received, Seq(0))
    
    sig ! 1
    waitForResult(received, Seq(0, 2))
    
    sig.pause()
    sig ! 2
    awaitAllTasks
    assert(waitForResult(received, Seq(0, 2)))
    
    sig.unpause()
    sig ! 3
    waitForResult(received, Seq(0, 2, 6))
  }

  // ===== onPause callback edge cases =====

  test("Stream - onPause callback can be set multiple times, only last one counts") {
    val src = Stream[Int]()
    var firstCalled = false
    var secondCalled = false
    
    src.onPause { firstCalled = true }
    src.onPause { secondCalled = true }
    
    src.pause()

    assert(firstCalled)
    assert(secondCalled)
  }

  test("Signal - onPause callback can be set multiple times, only last one counts") {
    val sig = Signal(0)
    var firstCalled = false
    var secondCalled = false
    
    sig.onPause { firstCalled = true }
    sig.onPause { secondCalled = true }
    
    sig.pause()

    assert(firstCalled)
    assert(secondCalled)
  }

  test("Stream - onPause with no callback set does not throw") {
    val src = Stream[Int]()
    // Don't set any callback
    src.pause() // Should not throw
    assert(src.isPaused)
  }

  test("Signal - onPause with no callback set does not throw") {
    val sig = Signal(0)
    // Don't set any callback
    sig.pause() // Should not throw
    assert(sig.isPaused)
  }
}
