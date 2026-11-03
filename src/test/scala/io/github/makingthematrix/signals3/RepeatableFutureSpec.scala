package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Threading.defaultContext

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.collection.mutable
import scala.concurrent.duration.*

/**
  * Tests of [[RepeatableFuture]] - the closeable future returned by [[CloseableFuture.repeat]] and
  * [[CloseableFuture.repeatVariant]] - with focus on the `restart` method which is the basis of `force` in
  * generator streams and generator signals.
  */
class RepeatableFutureSpec extends munit.FunSuite {
  given Timeout: FiniteDuration = 3.seconds
  val HeartBeatMs: FiniteDuration = 100.millis

  private def await(latch: CountDownLatch): Boolean = latch.await(Timeout.toMillis, TimeUnit.MILLISECONDS)

  test("restart resets the phase of the constant interval repeat") {
    val timestamps = mutable.ArrayBuffer[Long]()
    val offset = System.currentTimeMillis
    val naturalBeats = new CountDownLatch(3)
    val allBeats = new CountDownLatch(5)
    val cf = CloseableFuture.repeat(HeartBeatMs) {
      timestamps.addOne(System.currentTimeMillis - offset)
      naturalBeats.countDown()
      allBeats.countDown()
    }

    assert(await(naturalBeats), "the repeat didn't execute three times before the restart")
    // move into the middle of the current interval; without the reset,
    // the pending execution would fire soon after, at the old schedule
    Thread.sleep(30L)
    val restartAt = System.currentTimeMillis - offset
    cf.restart()

    assert(await(allBeats), "the repeat didn't execute twice more after the restart")
    Thread.sleep(20L) // let the last beat finish rescheduling itself before closing
    cf.close()
    assert(cf.isClosed)

    val intervalMs = HeartBeatMs.toMillis
    assertEquals(timestamps.size, 5)
    // the first execution after the restart comes a full interval after it, not at the old schedule
    val firstAfterRestart = timestamps(3)
    assert(firstAfterRestart >= restartAt + intervalMs - (intervalMs / 4)
      && firstAfterRestart <= restartAt + 3 * intervalMs,
      s"the first beat after the restart: $firstAfterRestart, the restart at: $restartAt")
    // the drift adjustment starts over from the restart - the next gap is also ~interval, not shortened
    val gap = timestamps(4) - firstAfterRestart
    assert(gap >= intervalMs - (intervalMs / 4) && gap <= 2 * intervalMs,
      s"the gap between the two beats after the restart: $gap")
  }

  test("restart re-invokes the interval function of repeatVariant and uses the returned delay") {
    val beats = mutable.ArrayBuffer[Long]()
    val offset = System.currentTimeMillis
    var intervalMs = HeartBeatMs.toMillis
    var intervalCalls = 0
    val naturalBeats = new CountDownLatch(2)
    val allBeats = new CountDownLatch(3)
    val cf = CloseableFuture.repeatVariant(() => { intervalCalls += 1; intervalMs.millis }) {
      beats.addOne(System.currentTimeMillis - offset)
      naturalBeats.countDown()
      allBeats.countDown()
    }

    assert(await(naturalBeats), "the repeat didn't execute twice before the restart")
    Thread.sleep(30L)
    // the interval function was called at creation and after each of the two executions
    assertEquals(intervalCalls, 3)

    // the pending third execution was scheduled with the old interval; the restart should replace it
    // with one scheduled with the new interval returned by the interval function
    intervalMs = 2 * HeartBeatMs.toMillis
    val restartAt = System.currentTimeMillis - offset
    cf.restart()

    // restart re-invoked the interval function to get the delay to the next execution
    assertEquals(intervalCalls, 4)

    assert(await(allBeats), "the repeat didn't execute once more after the restart")
    Thread.sleep(20L)
    cf.close()
    assert(cf.isClosed)

    assertEquals(beats.size, 3)
    val baseMs = HeartBeatMs.toMillis
    // the first execution after the restart comes with the new delay, not at the old schedule
    val firstAfterRestart = beats(2)
    assert(firstAfterRestart >= restartAt + 2 * baseMs - (baseMs / 2)
      && firstAfterRestart <= restartAt + 6 * baseMs,
      s"the first beat after the restart: $firstAfterRestart, the restart at: $restartAt")
  }

  test("restart after close is a no-op") {
    var executions = 0
    val firstBeat = new CountDownLatch(1)
    val cf = CloseableFuture.repeat(HeartBeatMs) {
      executions += 1
      firstBeat.countDown()
    }

    assert(await(firstBeat), "the repeat didn't execute")
    Thread.sleep(20L) // let the first beat finish rescheduling itself before closing
    assert(cf.closeAndCheck())
    assert(cf.isClosed)

    val count = executions
    cf.restart()
    Thread.sleep(3 * HeartBeatMs.toMillis)

    assertEquals(executions, count, "restart executed the body again after the future was closed")
    assert(cf.isClosed)
  }

  test("restart during an in-flight execution is a no-op") {
    val beatStarts = mutable.ArrayBuffer[Long]()
    val offset = System.currentTimeMillis
    var intervalCalls = 0
    val bodyStarted = new CountDownLatch(1)
    val threeBodiesEnded = new CountDownLatch(3)
    val cf = CloseableFuture.repeatVariant(() => { intervalCalls += 1; HeartBeatMs }) {
      beatStarts.addOne(System.currentTimeMillis - offset)
      bodyStarted.countDown()
      Thread.sleep(50L)
      threeBodiesEnded.countDown()
    }

    assert(await(bodyStarted), "the repeat didn't start executing")
    val callsBeforeRestart = intervalCalls
    // the body is still running; restart should do nothing and let the body complete
    cf.restart()
    assertEquals(intervalCalls, callsBeforeRestart, "restart rescheduled the future during an in-flight execution")

    // the loop continues normally after the in-flight execution completes
    assert(await(threeBodiesEnded), "the repeat loop stopped after the restart during an in-flight execution")
    Thread.sleep(20L)
    cf.close()
    assert(cf.isClosed)

    assertEquals(beatStarts.size, 3)
    val bodyMs = 50L
    // the second beat couldn't start before the first body ended; and it comes ~interval after that,
    // from the normal schedule, not from the restart point
    val gap = beatStarts(1) - beatStarts(0)
    assert(gap >= bodyMs && gap <= 4 * HeartBeatMs.toMillis, s"the gap between the first two beats: $gap")
  }
}
