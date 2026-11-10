package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Behaviors.{Beh, Fail}
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
	
	private var _onInit: List[Beh] = Nil

	// Registers a function that should be called exactly once when the actor is initialized
	def onInit(beh: Beh): Unit =
		_onInit ::= beh

	inline final def onInit(body: => Unit): Unit = onInit((IdGenerator.generate("onInit"), () => body))
	inline final def onInit(id: String, f: () => Unit): Unit = onInit(id -> f)
	
	final def onInit(id: String): Boolean = Behaviors.getBeh(id) match {
		case Some(beh) => onInit(beh); true
		case None => false
	}
	
	def isInitializedSignal(using ExecutionContext): Signal[Boolean]
	
	protected inline final def runOnInit(): Unit = _onInit.foreach(_.f())

	private var _onInitFailed: List[Fail] = Nil
	
	def onInitFailed(fail: Fail): Unit =
		_onInitFailed ::= fail

	inline final def onInitFailed(f: Throwable => Unit): Unit = 
		onInitFailed((IdGenerator.generate("onInitFailed"), f))

	inline final def onInitFailed(id: String, f: Throwable => Unit): Unit = onInitFailed(id -> f)

	final def onInitFailed(id: String): Boolean = Behaviors.getFail(id) match {
		case Some(fail) => onInitFailed(fail); true
		case None => false
	}

	protected inline final def runOnInitFailed(t: Throwable): Unit = _onInitFailed.foreach(_.f(t))
}

trait Initializable extends CanBeInitialized {
	def initialize(): Unit =
		if (!initialized.getAndSet(true)) try {
			runOnInit()
		} catch {
			case t: Throwable =>
				runOnInitFailed(t)
				initialized.set(false)
		}
	
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
