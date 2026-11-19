package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Behaviors

import scala.collection.mutable
import scala.util.chaining.scalaUtilChainingOps

/**
	* A registry of behaviors of a single lifecycle event, with their failure counterparts.
	*
	* The traits which implement the lifecycle events - `CanBeClosed`, `CanBePaused`, and `CanBeInitialized`,
	* - keep one instance of this registry per event:  for onClose`,
	* `onPause`, `onUnpause`, and `onInit`.
	*
	* The registry stores ''only the ids'' of the behaviors. The functions live in [[Behaviors]]:
	* a function is always registered there first, and this registry then only keeps the id, retrieving
	* the function by the id at the moment the event fires.
	*
	* The `Fail` behaviors are stored in the same registry as the behaviors of the event they guard: if one
	* of the behaviors throws an exception, the remaining behaviors are not run and all the `Fail` behaviors
	* are invoked with the exception instead.
	*
	* The behaviors run in the reverse order of registration - the last added behavior runs first.
	*
	* '''Registration and cleanup:''' registration is explicit. `addId`/`addFailId` attach a function
	* registered beforehand in [[Behaviors]] - such a function is never unregistered here automatically:
	* the user may share its id with other registries or objects, and they decide when to unregister it.
	* `add`, `addOneShot`, `addFail`, and `addFailOneShot` register a fresh function in [[Behaviors]]
	* under a newly generated id (the plain forms return the id, so it can be unregistered manually
	* whenever needed).
	*
	* '''One-shots:''' ids registered by `addOneShot`/`addFailOneShot` are marked as one-shot. After a
	* ''successful'' run of the event, all one-shot ids - behaviors and fails alike - are unregistered
	* from [[Behaviors]]: their functions are dead. A ''failed'' run keeps them, so a retry works.
	*/
private[signals3] final class BehaviorRegistry(name: String) {
	// The behavior and failure ids, in reverse order of registration - the most recently added id
	// is at the head of the list, so it runs first (same as the behavior lists of the actors).
	private var behs: List[String] = Nil
	private var fails: List[String] = Nil
	// The ids registered by `addOneShot`/`addFailOneShot`. They are the only ids unregistered from
	// Behaviors automatically - after a successful run of the event. Everything else stays there
	// until the user unregisters it manually.
	private val oneshots = mutable.HashSet[String]()

	/**
		* Registers the given block of code in [[Behaviors]] under a newly generated
		* id and adds that id to the registry.
		*
		* @param body The code to run when the lifecycle event fires.
		* @return The generated id of the registered function.
		*/
	def add(body: => Any): String = Behaviors.registerF(() => body, s"on$name").tap { id => behs ::= id }

	/**
		* Registers the given block of code in [[Behaviors]] under a newly generated id, adds that id to
		* the registry, and marks it as ''one-shot'': after a successful run of the event, the function
		* is unregistered from [[Behaviors]]. A failed run keeps it, so a retry works.
		*
		* @param body The code to run when the lifecycle event fires.
		*/
	def addOneShot(body: => Any): Unit = {
		val id = Behaviors.registerF(() => body, s"on$name")
		behs ::= id
		oneshots += id
	}

	/**
		* Adds the id of an already registered behavior.
		*
		* @param id The id of a behavior registered in [[Behaviors]] - for example, one shared by
		*           several objects to make them all run the same behavior.
		* @return `true` if the id was added; `false` if there is no behavior registered under it
		*         in [[Behaviors]].
		*/
	def addId(id: String): Boolean = Behaviors.getBeh(id) match {
		case Some(_) => behs ::= id; true
		case None    => false
	}

	/**
		* Registers the given function in [[Behaviors]] under a newly generated
		* id and adds that id to the registry.
		*
		* @param f The function to run if the lifecycle event fails.
		* @return The generated id of the registered function.
		*/
	def addFail(f: Throwable => Any): String =
		Behaviors.registerFailCase(f, s"on${name}Failed").tap { id => fails ::= id }

	/**
		* Registers the given function in [[Behaviors]] under a newly generated id, adds that id to the
		* registry, and marks it as ''one-shot'': after a successful run of the event (in which the failure
		* behaviors did not need to run), the function is unregistered from [[Behaviors]].
		*
		* @param f The function to run if the lifecycle event fails.
		*/
	def addFailOneShot(f: Throwable => Any): Unit = {
		val id = Behaviors.registerFailCase(f, s"on${name}Failed")
		fails ::= id
		oneshots += id
	}

	/**
		* Adds the id of an already registered failure behavior.
		*
		* @param id The id of a failure behavior registered in [[Behaviors]].
		* @return `true` if the id was added; `false` if there is no failure behavior registered under it
		*         in [[Behaviors]].
		*/
	def addFailId(id: String): Boolean = Behaviors.getFail(id) match {
		case Some(_) => fails ::= id; true
		case None    => false
	}

	/**
		* Runs all the behaviors of this registry, in the reverse order of registration, inside a single
		* try-catch. Each behavior's function is fetched from [[Behaviors]] by the stored id at the moment
		* of the run; an id whose function is no longer registered is silently skipped. The first behavior
		* that throws prevents the remaining ones from running.
		*
		* @return `true` if all the behaviors run successfully; `false` if one of them threw an exception -
		*         in that case all the failure behaviors of the registry are run with the exception
		*         (and an unregistered failure id is, again, silently skipped).
		*/
	def run(): Boolean = {
		val res = try {
			behs.foreach(id => Behaviors.getBeh(id).foreach(_.f())); true
		} catch {
			case t: Throwable => fails.foreach(id => Behaviors.getFail(id).foreach(_.f(t))); false
		}
		if (res && oneshots.nonEmpty) {
			oneshots.foreach(Behaviors.unregister)
			oneshots.clear()
		}
		res
	}

	/**
		* Removes all behavior and failure ids from this registry. The functions registered in [[Behaviors]]
		* stay there; this only severs the connection between this event and the behaviors.
		*/
	def reset(): Unit = {
		behs = Nil
		fails = Nil
		oneshots.clear()
	}
}
