package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Behaviors.{Beh, Fail}

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext

trait CanBePaused {
	protected val paused: AtomicBoolean = new AtomicBoolean(false)
	protected lazy val pausedSignal = FlagSignal()

	protected var _onPause: List[Beh] = Nil

	protected final inline def runOnPause(): Unit = _onPause.foreach(_.f())

	protected def onPause(beh: Beh): Unit =
		_onPause ::= beh
	inline final def onPause(body: => Any): Unit = onPause((IdGenerator.generate("onPause"), () => body): Beh)
	inline final def onPause(id: String, f: () => Unit): Unit = onPause((id -> f): Beh)

	final def onPauseById(id: String): Boolean = Behaviors.getBeh(id) match {
		case Some(beh) => onPause(beh); true
		case None => false
	}

	def isPaused: Boolean = paused.get()

	protected final inline def runOnPauseFailed(t: Throwable): Unit = _onPauseFailed.foreach(_.f(t))

	protected def onPauseFailed(fail: Fail): Unit =
		_onPauseFailed ::= fail

	inline final def onPauseFailed(f: Throwable => Unit): Unit =
		onPauseFailed((IdGenerator.generate("onPauseFailed"), f): Fail)

	inline final def onPauseFailed(id: String, f: Throwable => Unit): Unit = onPauseFailed((id -> f): Fail)

	final def onPauseFailedById(id: String): Boolean = Behaviors.getFail(id) match {
		case Some(fail) => onPauseFailed(fail); true
		case None => false
	}

	private var _onPauseFailed: List[Fail] = Nil

	private var _onUnpause: List[Beh] = Nil

	protected final inline def runOnUnpause(): Unit = _onUnpause.foreach(_.f())

	protected def onUnpause(beh: Beh): Unit =
		_onUnpause ::= beh
	inline final def onUnpause(body: => Any): Unit = onUnpause((IdGenerator.generate("onUnpause"), () => {body; ()}): Beh)
	inline final def onUnpause(id: String, f: () => Unit): Unit = onUnpause((id -> f): Beh)

	final def onUnpauseById(id: String): Boolean = Behaviors.getBeh(id) match {
		case Some(beh) => onUnpause(beh); true
		case None => false
	}

	protected final inline def runOnUnpauseFailed(t: Throwable): Unit = _onUnpauseFailed.foreach(_.f(t))

	protected def onUnpauseFailed(fail: Fail): Unit =
		_onUnpauseFailed ::= fail

	inline final def onUnpauseFailed(f: Throwable => Unit): Unit =
		onUnpauseFailed((IdGenerator.generate("onUnpauseFailed"), f): Fail)

	inline final def onUnpauseFailed(id: String, f: Throwable => Unit): Unit = onUnpauseFailed((id -> f): Fail)

	final def onUnpauseFailedById(id: String): Boolean = Behaviors.getFail(id) match {
		case Some(fail) => onUnpauseFailed(fail); true
		case None => false
	}

	private var _onUnpauseFailed: List[Fail] = Nil

	inline def isPausedSignal: Signal[Boolean] = pausedSignal
}

trait Pausable extends CanBePaused {
	def pause()(using ExecutionContext): Unit =
		if (!paused.getAndSet(true)) try {
			pausedSignal.set()
			runOnPause()
		} catch {
			case t: Throwable =>
				paused.set(false)
				runOnPauseFailed(t)
				pausedSignal.clear()
		}

	def unpause()(using ExecutionContext): Unit =
		if (paused.getAndSet(false)) try {
			pausedSignal.clear()
			runOnUnpause()
		} catch {
			case t: Throwable =>
				paused.set(true)
				runOnUnpauseFailed(t)
				pausedSignal.set()
		}
}
