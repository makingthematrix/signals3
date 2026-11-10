package io.github.makingthematrix.signals3

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext

trait CanBePaused {
	protected val paused: AtomicBoolean = new AtomicBoolean(false)
	protected lazy val pausedSignal = FlagSignal()

	protected var _onPause: List[() => Unit] = Nil

	def onPause(body: => Unit): Unit =
		_onPause ::= (() => body)

	def isPaused: Boolean = paused.get()

	protected var _onUnpause: List[() => Unit] = Nil

	def onUnpause(body: => Unit): Unit =
		_onUnpause ::= (() => body)

	inline def isPausedSignal: Signal[Boolean] = pausedSignal
}

trait Pausable extends CanBePaused {
	def pause()(using ExecutionContext): Unit =
		if (!paused.getAndSet(true)) try {
			tryPause()
			pausedSignal.set()
			_onPause.foreach(_())
		} catch {
			case t: Throwable => onPauseFailed(t); unpause()
		}

	protected def tryPause(): Unit = {}

	protected def onPauseFailed(t: Throwable): Unit = {}

	def unpause()(using ExecutionContext): Unit =
		if (paused.getAndSet(false)) try {
			tryUnpause()
			pausedSignal.clear()
			_onUnpause.foreach(_())
		} catch {
			case t: Throwable => onUnpauseFailed(t); // no call to `pause()` here to avoid endless cycle of pause/unpause
		}

	protected def tryUnpause(): Unit = {}

	protected def onUnpauseFailed(t: Throwable): Unit = {}
}
