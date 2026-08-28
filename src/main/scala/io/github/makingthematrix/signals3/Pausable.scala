package io.github.makingthematrix.signals3

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext

trait Pausable {
	private val paused: AtomicBoolean = new AtomicBoolean(false)
	private var _onPause: List[() => Unit] = Nil

	def onPause(body: => Unit): Unit =
		_onPause ::= (() => body)

	private final inline def callOnPause(): Unit = _onPause.foreach(_())

	def pause()(using ExecutionContext): Unit = {
		paused.set(true)
		pausedSignal.set()
		_onPause.foreach(_())
	}

	def unpause()(using ExecutionContext): Unit = {
		paused.set(false)
		pausedSignal.clear()
	}

	def isPaused: Boolean = paused.get()

	private lazy val pausedSignal = FlagSignal()
	
	def isPausedSignal: Signal[Boolean] = pausedSignal
}
