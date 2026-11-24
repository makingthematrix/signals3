package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Behaviors.TF
import io.github.makingthematrix.signals3.actors.MutableActor
import io.github.makingthematrix.signals3.priv.BehaviorRegistry

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
import scala.util.chaining.scalaUtilChainingOps

trait CanBePaused {
	protected val paused: AtomicBoolean = new AtomicBoolean(false)

	// Pausing and unpausing are recurring events (they can happen many times), so these registries
	// never unregister their owned ids automatically - see `BehaviorRegistry` for the details.
	private val pauseRegistry = BehaviorRegistry("Pause")
	private val unpauseRegistry = BehaviorRegistry("Unpause")

	def isPaused: Boolean = paused.get()

	protected def runOnPause(): Boolean = pauseRegistry.run()

	protected def runOnPause[Msg, State](actor: MutableActor[Msg, State]): Boolean = pauseRegistry.run(actor)
	
	protected def resetOnPause(): Unit = pauseRegistry.reset()

	/**
		* Registers a pause behavior: the block of code will run every time this object is paused.
		* The function is registered in [[Behaviors]] under a generated id.
		*
		* @param f The code to run on pause.
		* @return The generated id of the registered function - save it to `Behaviors.unregister` the
		*         function manually, if you ever need to.
		*/
	inline final def onPause(f: () => Any): String = pauseRegistry.add(f)

	inline final def onPause[Msg, State](tf: TF[Msg, State]): String = pauseRegistry.add(tf)

	/**
		* Registers an already registered pause behavior by its id.
		*
		* @param id The id of a [[Behaviors]] behavior.
		* @return `true` if the id was added; `false` if there is no behavior registered under it.
		*/
	inline final def onPauseId(id: String): Boolean = pauseRegistry.addId(id)
	
	/**
		* Registers a failure behavior with a function taking the exception; the function is registered
		* in [[Behaviors]] under a generated id.
		*
		* @param f The function to run when pausing fails.
		* @return The generated id of the registered function.
		*/
	inline final def onPauseFailed(f: Throwable => Any): String = pauseRegistry.addFail(f)
	
	/**
		* Registers an already registered failure behavior by its id.
		*
		* @param id The id of a [[Behaviors]] failure behavior.
		* @return `true` if the id was added; `false` if there is no failure behavior registered under it.
		*/
	inline final def onPauseFailedId(id: String): Boolean = pauseRegistry.addFailId(id)

	protected def runOnUnpause(): Boolean = unpauseRegistry.run()

	protected def runOnUnpause[Msg, State](actor: MutableActor[Msg, State]): Boolean = unpauseRegistry.run(actor)
	
	protected def resetOnUnpause(): Unit = unpauseRegistry.reset()
	
	/**
		* Registers an unpause behavior: the block of code will run every time this object is unpaused.
		* The function is registered in [[Behaviors]] under a generated id.
		*
		* @param f The code to run on unpause.
		* @return The generated id of the registered function - save it to `Behaviors.unregister` the
		*         function manually, if you ever need to.
		*/
	inline final def onUnpause(f: () => Any): String = unpauseRegistry.add(f)

	inline final def onUnpause[Msg, State](tf: TF[Msg, State]): String = unpauseRegistry.add(tf)

	/**
		* Registers an already registered unpause behavior by its id.
		*
		* @param id The id of a [[Behaviors]] behavior.
		* @return `true` if the id was added; `false` if there is no behavior registered under it.
		*/
	inline final def onUnpauseId(id: String): Boolean = unpauseRegistry.addId(id)
	
	/**
		* Registers a failure behavior with a function taking the exception; the function is registered
		* in [[Behaviors]] under a generated id.
		*
		* @param f The function to run when unpausing fails.
		* @return The generated id of the registered function.
		*/
	inline final def onUnpauseFailed(f: Throwable => Any): String = unpauseRegistry.addFail(f)
	
	/**
		* Registers an already registered failure behavior by its id.
		*
		* @param id The id of a [[Behaviors]] failure behavior.
		* @return `true` if the id was added; `false` if there is no failure behavior registered under it.
		*/
	inline final def onUnpauseFailedId(id: String): Boolean = unpauseRegistry.addFailId(id)

	final def isPausedSignal(using ExecutionContext): Signal[Boolean] = FlagSignal().tap { signal =>
		if (isPaused) signal.set() else signal.clear()
		onPause(() => signal.set())
		onPauseFailed(_ => signal.clear())
		onUnpause(() => signal.clear())
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
