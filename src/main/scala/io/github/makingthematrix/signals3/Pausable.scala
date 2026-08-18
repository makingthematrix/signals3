package io.github.makingthematrix.signals3

import java.util.concurrent.atomic.AtomicBoolean

trait Pausable {
	private val paused: AtomicBoolean = new AtomicBoolean(false)
	private var _onPause: Option[() => Unit] = None

	def onPause(body: => Unit): Unit = {
		_onPause = Some(() => body)
	}

	def pause(): Unit = {
		paused.set(true)
		_onPause.foreach(_())
	}

	def unpause(): Unit = {
		paused.set(false)
	}

	def isPaused: Boolean = paused.get()
}
