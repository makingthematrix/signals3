package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Behaviors.{Beh, Fail}
import io.github.makingthematrix.signals3.priv.BehaviorRegistry

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
import scala.util.chaining.scalaUtilChainingOps

trait CanBePaused {
	protected val paused: AtomicBoolean = new AtomicBoolean(false)

	private val pauseRegistry = BehaviorRegistry("Pause")
	private val unpauseRegistry = BehaviorRegistry("Unpause)")

	def isPaused: Boolean = paused.get()

	protected final inline def runOnPause(): Boolean = pauseRegistry.run()

	protected def onPause(beh: Beh): Unit = pauseRegistry.add(beh)
	inline final def onPause(body: => Any): Unit = pauseRegistry.add(body)
	inline final def onPause(id: String, f: () => Any): Unit = pauseRegistry.add(id, f)
	inline final def onPauseById(id: String): Boolean = pauseRegistry.addById(id)

	protected def onPauseFailed(fail: Fail): Unit = pauseRegistry.addFail(fail)
	inline final def onPauseFailed(f: Throwable => Any): Unit = pauseRegistry.addFail(f)
	inline final def onPauseFailed(id: String, f: Throwable => Any): Unit = pauseRegistry.addFail(id, f)
	inline final def onPauseFailedById(id: String): Boolean = pauseRegistry.addFailById(id)

	protected final inline def runOnUnpause(): Boolean = unpauseRegistry.run()

	protected def onUnpause(beh: Beh): Unit = unpauseRegistry.add(beh)
	inline final def onUnpause(body: => Any): Unit = unpauseRegistry.add(body)
	inline final def onUnpause(id: String, f: () => Any): Unit = unpauseRegistry.add(id, f)
	inline final def onUnpauseById(id: String): Boolean = unpauseRegistry.addById(id)

	protected def onUnpauseFailed(fail: Fail): Unit = unpauseRegistry.addFail(fail)
	inline final def onUnpauseFailed(f: Throwable => Any): Unit = unpauseRegistry.addFail(f)
	inline final def onUnpauseFailed(id: String, f: Throwable => Any): Unit = unpauseRegistry.addFail(id, f)
	inline final def onUnpauseFailedById(id: String): Boolean = unpauseRegistry.addFailById(id)

	final def isPausedSignal(using ExecutionContext): Signal[Boolean] = FlagSignal().tap { signal =>
		if (isPaused) signal.set() else signal.clear()
		onPause(signal.set())
		onPauseFailed(_ => signal.clear())
		onUnpause(signal.clear())
		onUnpauseFailed(_ => signal.set())
	}
}

trait Pausable extends CanBePaused {
	def pause(): Boolean =
		if (!paused.getAndSet(true)) {
			if (runOnPause()) true else {
				paused.set(false)
				false
			}
		} else false

	def unpause(): Boolean =
		if (paused.getAndSet(false)) {
			if (runOnUnpause()) true else {
				paused.set(true)
				false
			}
		} else false
}
