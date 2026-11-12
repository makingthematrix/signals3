package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Behaviors.{Beh, Fail}
import io.github.makingthematrix.signals3.priv.BehaviorRegistry

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
import scala.util.chaining.scalaUtilChainingOps

trait CanBeInitialized {
	protected val initialized: AtomicBoolean = new AtomicBoolean(false)
	private val registry = BehaviorRegistry("Init")
	
	/**
		* Returns whether the actor is initialized or not.
		* Before initialization, the actor cannot receive messages.
		*/
	def isInitialized: Boolean = initialized.get()

	protected def onInit(beh: Beh): Unit = registry.add(beh)
	inline final def onInit(body: => Any): Unit = registry.add(body)
	inline final def onInit(id: String, f: () => Any): Unit = registry.add(id, f)
	inline final def onInitById(id: String): Boolean = registry.addById(id)

	protected inline final def runOnInit(): Boolean = registry.run()

	protected def onInitFailed(fail: Fail): Unit = registry.addFail(fail)
	inline final def onInitFailed(f: Throwable => Any): Unit = registry.addFail(f)
	inline final def onInitFailed(id: String, f: Throwable => Any): Unit = registry.addFail(id, f)
	inline final def onInitFailedById(id: String): Boolean = registry.addFailById(id)

	final def isInitializedSignal(using ExecutionContext): Signal[Boolean] = FlagSignal().tap { signal =>
		if (isInitialized) signal.set()
		else {
			onInit(signal.set())
			onInitFailed(_ => signal.clear())
		}
	}
}

trait Initializable extends CanBeInitialized {
	def initialize(): Boolean = if (!initialized.getAndSet(true)) {
		if (runOnInit()) true else {
			initialized.set(false)
			false
		}
	} else false
}
