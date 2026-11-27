package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Behaviors.TF
import io.github.makingthematrix.signals3.actors.MutableActor
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
	
	/**
		* Registers an initialization behavior: the block of code will run when the actor is initialized,
		* exactly once. The function is registered in [[Behaviors]] under a generated id and unregistered
		* there after a successful initialization.
		*
		* @param f The code to run on initialization.
		*/
	inline final def onInit(f: () => Any): Unit = registry.addOneShot(f)

	inline final def onInit[Msg, State](tf: TF[Msg, State]): Unit = registry.addOneShot(tf)
	
	/**
		* Registers an already registered initialization behavior by its id.
		*
		* @param id The id of a [[Behaviors]] behavior.
		* @return `true` if the id was added; `false` if there is no behavior registered under it.
		*/
	inline final def onInit(id: String): Boolean = registry.addId(id)

	protected def runOnInit(): Boolean = registry.run()

	protected def runOnInit[Msg, State](actor: MutableActor[Msg, State]): Boolean = registry.run(actor)

	protected def resetOnInit(): Unit = registry.reset()
	
	/**
		* Registers a failure behavior with a function taking the exception; the function is registered
		* in [[Behaviors]] under a generated id.
		*
		* @param f The function to run when the initialization fails.
		* @return The generated id of the registered function.
		*/
	inline final def onInitFailed(f: Throwable => Any): Unit = registry.addFailOneShot(f)
	
	/**
		* Registers an already registered failure behavior by its id.
		*
		* @param id The id of a [[Behaviors]] failure behavior.
		* @return `true` if the id was added; `false` if there is no failure behavior registered under it.
		*/
	inline final def onInitFailedId(id: String): Boolean = registry.addFailId(id)

	final def isInitializedSignal(using ExecutionContext): Signal[Boolean] = FlagSignal().tap { signal =>
		if (isInitialized) signal.set()
		else {
			onInit(() => signal.set())
			onInitFailed(_ => signal.clear())
		}
	}

	inline final def removeOnInit(id: String): Unit = registry.remove(id)
}

trait Initializable extends CanBeInitialized {
	def initialize(): Boolean = if (!initialized.getAndSet(true)) {
		if (runOnInit()) true else {
			initialized.set(false)
			false
		}
	} else false
}
