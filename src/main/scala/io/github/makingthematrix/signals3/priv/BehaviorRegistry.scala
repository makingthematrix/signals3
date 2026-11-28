package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Behaviors
import io.github.makingthematrix.signals3.Behaviors.TF
import io.github.makingthematrix.signals3.actors.MutableActor

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
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
	* The ids of the behaviors are kept in concurrent sets (`ConcurrentHashMap.newKeySet`): an id is present
	* at most once (attaching it again is silently ignored), an event can fire - iterate the sets - while
	* other threads register, and there is ''no guaranteed run order'' of the behaviors
	* of one event.
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
	// The sets of the behavior and failure ids. A set can't hold a duplicate, so attaching
	// an already attached id is silently ignored. The sets are concurrent: the event can fire - iterate
	// them - while other threads register; the iteration is weakly consistent (it may miss an id added
	// in the middle of the run - such an id simply runs at the next firing of the event).
	// There is no run order of the behaviors.
	private val behs = ConcurrentHashMap.newKeySet[String]()
	private val fails = ConcurrentHashMap.newKeySet[String]()
	// The ids registered by `addOneShot`/`addFailOneShot`. They are the only ids unregistered from
	// Behaviors automatically - after a successful run of the event. Everything else stays there
	// until the user unregisters it manually.
	private val oneshots = ConcurrentHashMap.newKeySet[String]()

	/**
		* Registers the given block of code in [[Behaviors]] under a newly generated
		* id and adds that id to the registry.
		*
		* @param f The code to run when the lifecycle event fires.
		* @return The generated id of the registered function.
		*/
	def add(f: () => Any): String = Behaviors.registerF(f, s"on$name").tap { id => behs.add(id) }
	
	/**
		* Registers the given block of code in [[Behaviors]] under a newly generated id, adds that id to
		* the registry, and marks it as ''one-shot'': after a successful run of the event, the function
		* is unregistered from [[Behaviors]]. A failed run keeps it, so a retry works.
		*
		* @param f The code to run when the lifecycle event fires.
		*/
	def addOneShot(f: () => Any): Unit = {
		val id = Behaviors.registerF(f, s"on$name")
		behs.add(id)
		oneshots.add(id)
	}

	def add[Msg, State](tf: TF[Msg, State]): String = Behaviors.registerTF(tf, s"on$name").tap { id => behs.add(id) }

	def addOneShot[Msg, State](tf: TF[Msg, State]): Unit = {
		val id = Behaviors.registerTF(tf, s"on$name")
		behs.add(id)
		oneshots.add(id)
	}

	/**
		* Adds the id of an already registered behavior. It can be the id of a plain behavior
		* ([[Behaviors.getBeh]]) or of a total behavior which takes the actor
		* ([[Behaviors.getTBeh]] - registered e.g. by the `TF` variants of the `on*` methods);
		* the `run(actor)` variant applies the latter with the actor at the moment of the run.
		* An id of any other kind can also be attached, but it will never run (it is silently skipped).
		*
		* @param id The id of a behavior registered in [[Behaviors]] - for example, one shared by
		*           several objects to make them all run the same behavior.
		* @return `true` if the id was added; `false` if there is no behavior registered under it
		*         in [[Behaviors]].
		*/
	def addId(id: String): Boolean =
		if (Behaviors.isRegistered(id)) {
			behs.add(id)
			true
		} else false

	/**
		* Registers the given function in [[Behaviors]] under a newly generated
		* id and adds that id to the registry.
		*
		* @param f The function to run if the lifecycle event fails.
		* @return The generated id of the registered function.
		*/
	def addFail(f: Throwable => Any): String =
		Behaviors.registerFailCase(f, s"on${name}Failed").tap { id => fails.add(id) }

	/**
		* Registers the given function in [[Behaviors]] under a newly generated id, adds that id to the
		* registry, and marks it as ''one-shot'': after a successful run of the event (in which the failure
		* behaviors did not need to run), the function is unregistered from [[Behaviors]].
		*
		* @param f The function to run if the lifecycle event fails.
		*/
	def addFailOneShot(f: Throwable => Any): Unit = {
		val id = Behaviors.registerFailCase(f, s"on${name}Failed")
		fails.add(id)
		oneshots.add(id)
	}

	/**
		* Adds the id of an already registered failure behavior.
		*
		* @param id The id of a failure behavior registered in [[Behaviors]].
		* @return `true` if the id was added; `false` if there is no failure behavior registered under it
		*         in [[Behaviors]].
		*/
	def addFailId(id: String): Boolean = Behaviors.getFail(id) match {
		case Some(_) => fails.add(id); true
		case None    => false
	}

	/**
		* Runs all the behaviors of this registry, in no particular order, inside a single
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
			behs.asScala.foreach(id => Behaviors.getBeh(id).foreach(_.f())); true
		} catch {
			case t: Throwable => fails.asScala.foreach(id => Behaviors.getFail(id).foreach(_.f(t))); false
		}
		if (res && !oneshots.isEmpty) {
			oneshots.asScala.foreach(Behaviors.unregister)
			oneshots.clear()
		}
		res
	}

	def run[Msg, State](actor: MutableActor[Msg, State]): Boolean = {
		val res = try {
			behs.asScala.foreach { id =>
				val tf = Behaviors.getTBeh[Msg, State](id)
				if (tf.isDefined) tf.foreach(_.tf(actor)) else Behaviors.getBeh(id).foreach(_.f())
			}
			true
		} catch {
			case t: Throwable => fails.asScala.foreach(id => Behaviors.getFail(id).foreach(_.f(t))); false
		}
		if (res && !oneshots.isEmpty) {
			oneshots.asScala.foreach(Behaviors.unregister)
			oneshots.clear()
		}
		res
	}


	/**
		* Removes all behavior and failure ids from this registry. The functions registered in [[Behaviors]]
		* stay there; this only severs the connection between this event and the behaviors.
		*/
	def reset(): Unit = {
		behs.clear()
		fails.clear()
		oneshots.asScala.foreach(Behaviors.unregister)
		oneshots.clear()
	}

	def remove(id: String): Unit = {
		behs.remove(id)
		fails.remove(id)
		if (oneshots.contains(id)) Behaviors.unregister(id)
		oneshots.remove(id)
	}
}
