package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.priv.DoneSignal

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
import scala.util.chaining.scalaUtilChainingOps

trait CanBeInitialized {
	protected val initialized: AtomicBoolean = new AtomicBoolean(false)
	
	/**
		* Returns whether the actor is initialized or not.
		* Before initialization, the actor cannot receive messages.
		*/
	def isInitialized: Boolean = initialized.get()


	protected var _onInit: List[() => Unit] = Nil

	// Registers a function that should be called exactly once when the actor is initialized
	def onInit(body: => Unit): Unit =
		_onInit ::= (() => body)
	
	def isInitializedSignal(using ExecutionContext): Signal[Boolean]
}

trait Initializable extends CanBeInitialized {
	def initialize(): Unit =
		if (!initialized.getAndSet(true)) try {
			tryInitialize()
			_onInit.foreach(_())
			_onInit = Nil
		} catch {
			case t: Throwable =>
				onInitializationFailed(t)
				initialized.set(false)
		}
		
	protected def tryInitialize(): Unit = {}
	
	protected def onInitializationFailed(t: Throwable): Unit = {}
	
	/**
		* Returns a signal that works on a given [[scala.concurrent.ExecutionContext]]; it starts with the value set to `false` (unless it's
		* created after the actor is already initialized) and it will be set to `true` when the actor is initialized.
		*
		* @return A signal that will be set to `true` when the actor is initialized.
		*/
	def isInitializedSignal(using ExecutionContext): Signal[Boolean] =
		DoneSignal().tap { signal =>
			if (isInitialized) signal.done() else onInit(signal.done())
		}
}
