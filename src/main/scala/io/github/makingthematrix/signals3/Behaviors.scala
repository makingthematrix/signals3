package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.actors.MutableActor

import scala.collection.mutable
import scala.util.Try
import scala.util.chaining.scalaUtilChainingOps

/**
	* A singleton storage of behaviors - the ''behavior library''.
	*
	* A behavior is a function (total or partial) paired with a unique id. Behaviors are used mainly by actors
	* (see [[io.github.makingthematrix.signals3.actors.MutableActor]]) - but not only by them. Any code can register
	* a reusable behavior here and then ask for it by id, which makes it possible to e.g. add a behavior to an actor
	* without passing the function around, only its id.
	*
	* The library keeps four kinds of behaviors:
	* - '''Beh''' - a tuple of an id and a total function, meant for the four of five lifecycle events: 
	*   `onInit`, `onPause`, `onUnpause`, `onClose`.
	* - '''Fail''' - a tuple of an id and function that takes a Throwable: meant for when one of the lifecycle
	* events fail
	*  - '''PBeh''' - a ''partial'' behavior: a partial function which tries to process an incoming message
	*    and may decide that it doesn't handle it (the former `Actor.Beh`);
	*  - '''TBeh''' - a ''total'' behavior: a total function which always runs, no matter what message came in.
	*    Total behaviors are meant for the fifth lifecycle event of an actor: `onBeat`
	*
	* Registration is dynamic: new behaviors can be registered (and unregistered) at runtime through the methods
	* of this object.
	*
	* All behaviors share one global id space: an id is unique across all four kinds - no two behaviors
	* anywhere in the system share an id. This is on purpose: ids double as debugging handles (they show up
	* in logs, crash traces, and serialized state), so they must never be ambiguous. Every registration
	* method checks all the registries and refuses an id that is already taken; every kind is however kept
	* in its own registry, so retrieval is by kind (e.g. [[getBeh]] only sees [[Beh]]).
	*/
object Behaviors {
	type Beh = (id: String, f: () => Any)
	type Fail = (id: String, f: Throwable => Any)

	private val behs = mutable.HashMap[String, () => Any]()
	private val fails = mutable.HashMap[String, Throwable => Any]()
	
	def registerBeh(beh: Beh): Boolean =
		if (isRegistered(beh.id)) false
		else {
			behs += beh.id -> beh.f
			true
		}

	/**
		* Registers a [[Beh]] function under a newly generated id and returns that id. The id prefix
		* defaults to `"beh"` but can be customized to make the ids easier to tell apart in logs
		* (e.g. `"onInit"` for the `onInit` lifecycle event).
		*/
	inline def registerF(f: () => Any, prefix: String = "beh"): String =
		IdGenerator.generate(prefix).tap { id => registerBeh(id -> f) }
	
	def registerFail(fail: Fail): Boolean =
		if (isRegistered(fail.id)) false
		else {
			fails += fail.id -> fail.f
			true
		}

	/**
		* Registers a [[Fail]] function under a newly generated id and returns that id. The id prefix
		* defaults to `"fail"` but can be customized (e.g. `"onCloseFailed"` for the failure handlers
		* of the `onClose` lifecycle event).
		*/
	inline def registerFailCase(f: Throwable => Any, prefix: String = "fail"): String =
		IdGenerator.generate(prefix).tap { id => registerFail(id -> f) }
	
	inline def getBeh(id: String): Option[Beh] = behs.get(id).map(f => id -> f)
	inline def getFail(id: String): Option[Fail] = fails.get(id).map(f => id -> f)

	object Beh {
		def unapply(id: String): Option[Beh] = getBeh(id)
	}

	object Fail {
		def unapply(id: String): Option[Fail] = getFail(id)
	}
	/**
		* The type of a custom behavior: a partial function that takes a message and an actor and returns an optional response.
		*/
	type PF[Msg, State] = PartialFunction[(Msg, MutableActor[Msg, State]), Option[Msg]]

	/**
		* A ''partial'' behavior: a partial function (`PF`) paired with a unique id.
		*
		* A partial behavior tries to process an incoming message, but it may decide that it doesn't handle it,
		* in which case the next behavior in the actor's behavior list is given a chance.
		*/
	type PBeh[Msg, State] = (id: String, pf: PF[Msg, State])

	/**
		* The type of a total behavior function: it takes the actor and always runs, no matter what message came in.
		*
		* Total behaviors are meant for the fifth lifecycle event of an actor: `onBeat` - functions which should
		* be executed at every heartbeat of the actor rather than in response to a particular message.
		* The other four lifecycle events - `onInit`, `onPause`, `onUnpause`, `onClose` - use the simpler [[Beh]]
		* functions instead (with failures handled by [[Fail]]).
		*/
	type TF[Msg, State] = MutableActor[Msg, State] => Any

	/**
		* A ''total'' behavior: a total function (`TF`) paired with a unique id.
		*/
	type TBeh[Msg, State] = (id: String, tf: TF[Msg, State])

	// Behaviors of all message and state types share one registry, so the stored functions are untyped
	// and the type parameters are restored with unchecked casts at retrieval.
	private type AnyPF = PartialFunction[(Any, MutableActor[Any, Any]), Option[Any]]
	private type AnyTF = MutableActor[Any, Any] => Unit

	// The registry of partial behaviors, keyed by id.
	private val pbehs = mutable.HashMap[String, AnyPF]()
	// The registry of total behaviors, keyed by id.
	private val tbehs = mutable.HashMap[String, AnyTF]()

	// Ids are globally unique across all four registries - see the object scaladoc. Every `register*`
	// method checks all of them before accepting an id.
	def isRegistered(id: String): Boolean =
		behs.contains(id) || fails.contains(id) || pbehs.contains(id) || tbehs.contains(id)

	/**
		* Registers a new partial behavior in the library.
		*
		* @param beh The partial behavior to register: a tuple of a unique id and a partial function.
		* @return `true` if the behavior was registered, `false` if this id is already taken by any behavior
		*         of any kind (the existing one is kept).
		*/
	def registerPBeh[Msg, State](beh: PBeh[Msg, State]): Boolean =
		if (isRegistered(beh.id)) false
		else {
			pbehs += beh.id -> beh.pf.asInstanceOf[AnyPF]
			true
		}
		
	inline def registerPF[Msg, State](pf: PF[Msg, State], prefix: String = "pbeh"): String =
		IdGenerator.generate(prefix).tap { id => registerPBeh(id -> pf) }

	/**
		* Registers a new total behavior in the library.
		*
		* @param beh The total behavior to register: a tuple of a unique id and a total function.
		* @return `true` if the behavior was registered, `false` if this id is already taken by any behavior
		*         of any kind (the existing one is kept).
		*/
	def registerTBeh[Msg, State](beh: TBeh[Msg, State]): Boolean =
		if (isRegistered(beh.id)) false
		else {
			tbehs += beh.id -> beh.tf.asInstanceOf[AnyTF]
			true
		}

	inline def registerTF[Msg, State](tf: TF[Msg, State], prefix: String = "tbeh"): String =
		IdGenerator.generate(prefix).tap { id => registerTBeh(id -> tf) }

	/**
		* Unregisters the behavior with the given id - whatever kind it is: a [[Beh]], a [[Fail]], a partial,
		* or a total behavior. Under the global id-uniqueness rule at most one behavior holds the id,
		* but all four registries are checked, which is a harmless safety net.
		*
		* @param id The id of the behavior to remove.
		* @return `true` if any behavior was removed, `false` if nothing was registered under this id.
		*/
	def unregister(id: String): Boolean =
		behs.remove(id).isDefined || fails.remove(id).isDefined || pbehs.remove(id).isDefined || tbehs.remove(id).isDefined

	/**
		* Returns the partial behavior function registered under the given id, if any.
		*
		* The library stores behaviors of all message and state types in one registry, so the type parameters
		* are restored with an unchecked cast - the caller is responsible for asking for the same types
		* the behavior was registered with.
		*
		* @param id The id of the partial behavior.
		* @return The partial behavior function, or `None` if there is no partial behavior with this id.
		*/
	def getPBeh[Msg, State](id: String): Option[PBeh[Msg, State]] =
		pbehs.get(id).flatMap(pf => Try(pf.asInstanceOf[PF[Msg, State]]).toOption.map(id -> _))

	object PBeh {
		def unapply[Msg, State](id: String): Option[PBeh[Msg, State]] = getPBeh(id)
	}
	
	/**
		* Returns the total behavior function registered under the given id, if any.
		*
		* The library stores behaviors of all message and state types in one registry, so the type parameters
		* are restored with an unchecked cast - the caller is responsible for asking for the same types
		* the behavior was registered with.
		*
		* @param id The id of the total behavior.
		* @return The total behavior function, or `None` if there is no total behavior with this id.
		*/
	def getTBeh[Msg, State](id: String): Option[TBeh[Msg, State]] =
		tbehs.get(id).flatMap(tf => Try(tf.asInstanceOf[TF[Msg, State]]).toOption.map(id -> _))
		
	object TBeh {
		def unapply[Msg, State](id: String): Option[TBeh[Msg, State]] = getTBeh(id)
	}
	
	def reset(): Unit = {
		behs.clear()
		fails.clear()
		tbehs.clear()
		pbehs.clear()
	}
}
