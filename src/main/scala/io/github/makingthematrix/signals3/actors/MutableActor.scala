package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.Behaviors.TF
import io.github.makingthematrix.signals3.priv.BehaviorRegistry
import io.github.makingthematrix.signals3.SourceStream

/**
	* A trait representing a mutable actor, which is an extension of the `Actor` trait. This actor
	* allows dynamic modification of its internal state and behavior at runtime. It introduces methods
	* to update the actor's final behavior, heartbeat strategy, and state, as well as to add or remove
	* behaviors dynamically.
	*
	* Mainly used by the `Behavior` functions.
	*
	* @tparam Msg   The type of the incoming message
	* @tparam State The type of the internal state
	*/
trait MutableActor[Msg, State] extends Actor[Msg, State]{
	/**
		* Enables the behavior method to alter the actor's state
		*
		* @param newState the new state of the actor
		*/
	def state_=(newState: State): Unit

	/** The **optional** output stream that may be used by the behaviors to push out a new response.
		*
		* "Optional" is a keyword here. It's totally up to a behavior if it decides to send a response to `out`.
		* You may build your actor in such a way that it operates solely on the `in` and `out` streams, you can forget
		* about them, or you can do anything in-between.
		*
		* In `MutableActor` the type of `out` changes to `SourceStream[Msg]` so that the behavior may send a response to it.
		*/
	override def out: SourceStream[Msg]

	// `onBeat` is a recurring event (it can happen many times), so - unlike the one-shot lifecycle
	// events - none of its behaviors are ever unregistered automatically; see `priv.BehaviorRegistry`
	// for the general rules. As everywhere else, the beat registry stores only ids; the functions
	// live in `Behaviors` and are retrieved by the id at the moment of the run. The ids are kept in
	// a `mutable.HashSet`: an id is present at most once (attaching it again is silently ignored)
	// and there is no guaranteed run order of the behaviors.
	private val registry = BehaviorRegistry("Beat")
	
	/**
		* Runs all the beat behaviors, at every heartbeat of the actor, inside a single try-catch
		* (in no particular order). The functions are fetched from [[Behaviors]] by the stored ids
		* at the moment of the run; an id whose function is no longer registered is silently skipped.
		*
		* @return `true` if all the behaviors run successfully; `false` if one of them threw an exception -
		*         in that case all the failure behaviors are run with the exception (and, again, an
		*         unregistered failure id is silently skipped).
		*/
	protected final inline def runOnBeat(): Boolean = registry.run(this)

	/**
		* Registers a beat behavior: the function is registered in [[io.github.makingthematrix.signals3.Behaviors]]
		* under a generated id and that id is added to the actor's beat registry. It will run at every
		* heartbeat of the actor. Note the explicit `() => Any` parameter: an actor-taking function
		* passed here would be evaluated once and silently discarded - use [[onBeatTF]] for those.
		*
		* @param f The function to run at every heartbeat.
		* @return The generated id of the registered function - save it to unregister the function
		*         in [[io.github.makingthematrix.signals3.Behaviors]] manually, if you ever need to.
		*/
	inline final def onBeat(f: () => Any): String = registry.add(f)

	/**
		* Registers a beat behavior: the actor-taking function is adapted (with `this` as the argument)
		* into a plain function registered in [[io.github.makingthematrix.signals3.Behaviors]] under
		* a generated id, and that id is added to the actor's beat registry. It will run at every
		* heartbeat of the actor.
		*
		* @param tf The function to run at every heartbeat - it takes this actor.
		* @return The generated id of the registered function - save it to unregister the function
		*         in [[io.github.makingthematrix.signals3.Behaviors]] manually, if you ever need to.
		*/
	inline final def onBeat(tf: TF[Msg, State]): String = registry.add(tf)
	/**
		* Attaches an already registered beat behavior by its id. The behavior can be shared with
		* other actors - unregistering it stays in the hands of whoever registered it.
		*
		* @param id The id of a [[Behaviors]] total behavior.
		* @return `true` if the id was added; `false` if there is no total behavior registered under it.
		*/
	inline final def onBeatId(id: String): Boolean = registry.addId(id)

	/**
		* Registers a failure behavior which runs if a beat behavior throws an exception.
		* The exception prevents the remaining beat behaviors from running.
		*
		* @param f The function taking the exception.
		* @return The generated id of the registered function - save it to `Behaviors.unregister` the
		*         function manually, if you ever need to.
		*/
	inline final def onBeatFailed(f: Throwable => Any): String = registry.addFail(f)

	/**
		* Attaches an already registered failure behavior by its id.
		*
		* @param id The id of a [[Behaviors]] failure behavior.
		* @return `true` if the id was added; `false` if there is no failure behavior registered under it.
		*/
	final def onBeatFailedId(id: String): Boolean = registry.addFailId(id)

	/**
		* Removes all behavior and failure ids from the actor's beat registry: until new ones are added,
		* the actor beats empty. The functions registered in [[Behaviors]] - including the ones generated
		* by [[onBeat]] - stay there: their ids may be shared with other actors (`AddOnBeat` can serve
		* several of them), so this method never unregisters anything.
		*/
	inline final def resetOnBeat(): Unit = registry.reset()
}
