package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.actors.{Actor, MutableActor}

import scala.collection.mutable

/**
	* A singleton storage of behaviors - the ''behavior library''.
	*
	* A behavior is a function (total or partial) paired with a unique id. Behaviors are used mainly by actors
	* (see [[io.github.makingthematrix.signals3.actors.MutableActor]]) - but not only by them. Any code can register
	* a reusable behavior here and then ask for it by id, which makes it possible to e.g. add a behavior to an actor
	* without passing the function around, only its id.
	*
	* The library keeps fourkinds of behaviors:
	* - '''Beh''' - a tuple of an id and a total function, meant for the four of five lifecycle events: 
	*   `onInit`, onPause`, `onUnpause`, `onClose`.
	* - '''Fail''' - a tuple of an id and function that takes a Throwable: meant for when one of the lifecycle
	* events fail
	*  - '''PBeh''' - a ''partial'' behavior: a partial function which tries to process an incoming message
	*    and may decide that it doesn't handle it (the former `Actor.Beh`);
	*  - '''TBeh''' - a ''total'' behavior: a total function which always runs, no matter what message came in.
	*    Total behaviors are meant for the fifth lifecycle event of an actor: `onBeat`
	*
	* Registration is dynamic: new behaviors can be registered (and unregistered) at runtime through the methods
	* of this object, and some generally useful behaviors are hardcoded in the library from the start.
	*
	* Each kind has its own registry, so ids are unique within a kind, but a partial and a total behavior
	* may share the same id.
	*/
object Behaviors {
	type Beh = (id: String, f: () => Unit)
	type Fail = (id: String, f: Throwable => Unit)

	private val behs = mutable.HashMap[String, () => Unit]()
	private val fails = mutable.HashMap[String, Throwable => Unit]()

	def registerBeh(beh: Beh): Boolean =
		if (behs.contains(beh.id)) false
		else {
			behs += beh.id -> beh.f
			true
		}

	def registerFail(fail: Fail): Boolean =
		if (fails.contains(fail.id)) false
		else {
			fails += fail.id -> fail.f
			true
		}

	inline def unregisterBeh(id: String): Boolean = behs.remove(id).isDefined
	inline def unregisterFail(id: String): Boolean = fails.remove(id).isDefined

	inline def getBeh(id: String): Option[Beh] = behs.get(id).map(f => id -> f)
	inline def getFail(id: String): Option[Fail] = fails.get(id).map(f => id -> f)

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
		* Total behaviors are meant for the five lifecycle events of an actor: `onInit`, `onBeat`, `onPause`,
		* `onUnpause`, `onClose` - functions which should be executed at a given point of the actor's lifecycle
		* rather than in response to a particular message.
		*/
	type TF[Msg, State] = MutableActor[Msg, State] => Unit

	/**
		* A ''total'' behavior: a total function (`TF`) paired with a unique id.
		*/
	type TBeh[Msg, State] = (id: String, tf: TF[Msg, State])

	/**
		* A `TBeh` that does nothing - the default value of the five lifecycle events of an actor
		* (`onInit`, `onBeat`, `onPause`, `onUnpause`, `onClose`), recognizable by the empty id.
		*/
	val NothingTBeh: (String, MutableActor[?, ?] => Unit) = "" -> Actor.DoNothing

	// Behaviors of all message and state types share one registry, so the stored functions are untyped
	// and the type parameters are restored with unchecked casts at retrieval.
	private type AnyPF = PartialFunction[(Any, MutableActor[Any, Any]), Option[Any]]
	private type AnyTF = MutableActor[Any, Any] => Unit

	// The registry of partial behaviors, keyed by id.
	private val pbehs = mutable.HashMap[String, AnyPF]()
	// The registry of total behaviors, keyed by id.
	private val tbehs = mutable.HashMap[String, AnyTF]()

	/**
		* Registers a new partial behavior in the library.
		*
		* @param beh The partial behavior to register: a tuple of a unique id and a partial function.
		* @return `true` if the behavior was registered, `false` if a partial behavior with this id already exists
		*         (the existing one is kept).
		*/
	def registerPBeh[Msg, State](beh: PBeh[Msg, State]): Boolean =
		if (pbehs.contains(beh.id) || tbehs.contains(beh.id)) false
		else {
			pbehs += beh.id -> beh.pf.asInstanceOf[AnyPF]
			true
		}

	/**
		* Registers a new total behavior in the library.
		*
		* @param beh The total behavior to register: a tuple of a unique id and a total function.
		* @return `true` if the behavior was registered, `false` if a total behavior with this id already exists
		*         (the existing one is kept).
		*/
	def registerTBeh[Msg, State](beh: TBeh[Msg, State]): Boolean =
		if (pbehs.contains(beh.id) || tbehs.contains(beh.id)) false
		else {
			tbehs += beh.id -> beh.tf.asInstanceOf[AnyTF]
			true
		}

	/**
		* Unregisters the behavior with the given id - partial or total (or both, if a partial and a total
		* behavior share the id).
		*
		* @param id The id of the behavior to remove.
		* @return `true` if any behavior was removed, `false` if nothing was registered under this id.
		*/
	def unregister(id: String): Boolean =
		pbehs.remove(id).isDefined || tbehs.remove(id).isDefined

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
		pbehs.get(id).map(pf => id -> pf.asInstanceOf[PF[Msg, State]])

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
		tbehs.get(id).map(tf => id -> tf.asInstanceOf[TF[Msg, State]])
}
