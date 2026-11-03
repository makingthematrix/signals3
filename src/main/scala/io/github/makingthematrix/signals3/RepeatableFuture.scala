package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.priv.ActuallyCloseable

import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Promise}
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/** The `CloseableFuture[Unit]` returned by [[repeat]] and [[repeatVariant]]. It repeatedly executes the given
	* `body` in intervals returned by the `interval` function, until it is closed.
	*
	* The pending execution can be cancelled and rescheduled with [[Repeat.restart]] - used e.g. by
	* `GeneratorStream.force` to execute the repeated action immediately and delay the next scheduled execution
	* to a full interval from the moment of the restart. `restartInterval` returns the delay to the first
	* execution after the restart, and `onRestart` is a hook which lets [[repeat]] reset the state of its
	* drift adjustment.
	*
	* @param interval        The function returning the delay between each two consecutive executions.
	* @param body            A task repeated every `interval`. If `body` throws an exception, the exception
	*                        will be ignored and the task will be called again, after the next interval.
	*/
final class RepeatableFuture(interval: () => FiniteDuration)(body: => Unit)(using ExecutionContext)
	extends ActuallyCloseable(Promise[Unit]()){

	private val alreadyRunning = new AtomicBoolean(false)
	private var task: TimerTask = sched(interval())

	private def onRepeat(): Unit = if (!alreadyRunning.getAndSet(true)){
		Try(body)
		if (!isClosed) task = sched(interval())
		alreadyRunning.set(false)
	}

	private inline def sched(t: FiniteDuration): TimerTask = CloseableFuture.schedule(() => onRepeat(), t.toMillis)

	/** Cancels the currently pending execution and schedules the next one after the delay returned by
		* `restartInterval` - typically the full interval, counted from now. Does nothing if the future
		* is already closed. */
	def restart(): Unit = if (!isClosed && !alreadyRunning.getAndSet(true)) {
		task.cancel()
		task = sched(interval())
		alreadyRunning.set(false)
	}

	override def closeAndCheck(): Boolean = {
		task.cancel()
		super.closeAndCheck()
	}
}
