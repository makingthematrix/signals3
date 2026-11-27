package io.github.makingthematrix.signals3.actors

import scala.annotation.static
import scala.concurrent.ExecutionContext
import scala.reflect.{ClassTag, TypeTest}

/**
	* Represents system-level messages that can be used to control or affect the behavior
	* of an actor. These messages are typically utilized for lifecycle management or operational changes within a system.
	*
	* The `SystemMsg` enum contains the following members:
	* - `Pause`: the actor should temporarily suspend operations.
	* - `Unpause`: the actor should resume operations after being paused.
	* - `Close`: the actor should terminate its operations.
	* - `Done`: a response message confirming that the operation was successful.
	* - `InvalidId`: a response message indicating that the provided behavior ID is invalid, usually combined with `CloseableFuture.failed`
	* - `SetBehavior(pbehId)` - pushes the partial behavior registered under this id onto the actor's behavior stack,
	*   making it the current behavior; setting the current behavior again is a no-op
	* - `UnwindBehavior` - pops the actor's behavior stack, so the previously set behavior becomes current again;
	*   unwinding below the last behavior is a silent no-op
	* - `AddOnBeat(tbehId)` - adds the total behavior registered under this id to the actor's heartbeat
	* - `RemoveOnBeat(tbehId)` - removes the heartbeat behavior with this id from the actor, if attached
	* - `ResetOnBeat` - removes all the actor's heartbeat behaviors
	* - `AddOnInit(behId)` - adds the behavior registered under this id to the actor's init event -
	*   as init runs once, it only fires if the actor has not been initialized yet;
	*   `AddOnPause(behId)`, `AddOnUnpause(behId)`, `AddOnClose(behId)` do the same for the other
	*   lifecycle events
	* - `RemoveOnInit(behId)`, `RemoveOnPause(behId)`, `RemoveOnUnpause(behId)`, `RemoveOnClose(behId)` -
	*   remove the id from the given event's registry, if attached; an id which was never attached
	*   is silently ignored
	* - `ResetOnInit`, `ResetOnPause`, `ResetOnUnpause`, `ResetOnClose` - detach all the ids of the
	*   given lifecycle event; the functions stay registered in the `Behaviors` library
	* - `Spawn(...)` - the actor should spawn a new sub-actor with the specified parameters: id, initial
	*   state, the id of its behavior, heartbeat, and the ids of the behaviors for all five lifecycle events
	*   (`onInit`, `onBeat`, `onPause`, `onUnpause`, `onClose`).
	* - `NewChild(child)` - the response for `Spawn` with the new actor's JVM reference (todo: change to ActorRef?)
	* - `ActorClosed(actorId)` - informs that another actor has closed
	* - `Register(actor)` - used to register a new actor with the system or another actor dictionary
	* - `Unregister(actorId)` - used to unregister an actor from the system or another actor dictionary
	* - `AskForRef(actorId, systemId)` - asks for another actor's reference and expects the response to come through `ask`
	* - `AskForRefAsync(sender, actorId, systemId)` - asks for another actor's reference and expects the response to come as a separate message
	* - `Ref(ref)` - a response to `AskForRef` and `AskForRefAsync`; passes another actor's reference to the actor
	* - `Requeue(actorId, msg, behId, tryNumber)` - requeues a message so it will be processed again
	*/
enum SystemMsg {
	case Pause, Unpause, Close, Done, InvalidId
	case SetBehavior(pbehId: String)
	case UnwindBehavior
	case AddOnBeat(tbehId: String)
	case AddOnInit(behId: String)
	case AddOnPause(behId: String)
	case AddOnUnpause(behId: String)
	case AddOnClose(behId: String)
	case RemoveOnBeat(tbehId: String)
	case RemoveOnInit(behId: String)
	case RemoveOnPause(behId: String)
	case RemoveOnUnpause(behId: String)
	case RemoveOnClose(behId: String)
	case ResetOnBeat
	case ResetOnInit
	case ResetOnPause
	case ResetOnUnpause
	case ResetOnClose
	case Spawn[State](actorId: String = "",
	                       state: Option[State] = None,
	                       behavior: String = "",
	                       heartbeat: Option[HeartBeatStrategy] = None,
	                       onInit: Set[String] = Set.empty,
	                       onBeat: Set[String] = Set.empty,
	                       onPause: Set[String] = Set.empty,
	                       onUnpause: Set[String] = Set.empty,
	                       onClose: Set[String] = Set.empty,
	                       useSerialDispatch: Boolean = false,
	                       executionContext: Option[ExecutionContext] = None
	                      )
	case NewChild[Msg](child: ActorRef[Msg])
	case ActorClosed(actorId: String)
	case Register[Msg, State](actor: Actor[Msg, ?], tags: Set[ActorTag] = Set.empty)
	case Unregister(actorId: String)
	case AskForRef(actorId: String = "", systemId: String = "", tags: Set[ActorTag] = Set.empty)
	case AskForRefAsync[Msg](sender: Actor[Msg, ?], actorId: String = "", systemId: String = "", tags: Set[ActorTag] = Set.empty)
	case Ref[Msg](ref: ActorRef[Msg])
	case Requeue[Msg](actorId: String, msg: Msg, behId: String, tryNumber: Int = 0)
	case SysRequeue(actorId: String, msg: SystemMsg, tryNumber: Int = 0)
	case RegisterSystem[Msg](system: RemoteSystem[Msg], tags: Set[ActorTag] = Set.empty)
	case SystemClosed(systemId: String)
	case UnregisterSystem(systemId: String)
	case AskForSystem(systemId: String = "", tags: Set[ActorTag] = Set.empty)
	case AskForSystemAsync[Msg](sender: Actor[Msg, ?], systemId: String = "", tags: Set[ActorTag] = Set.empty)
	case RemoteRef[Msg](ref: RemoteSystem[Msg])
}

object SystemMsg {
	/**
		* Creates a "trusted" [[scala.reflect.TypeTest]] used to match on `SystemMsg` cases with type parameters
		* (like `Spawn[State]`) without giving up the full static type of the matched message.
		*
		* The type arguments of such cases are erased at runtime, so a plain type pattern like
		* `case msg: Spawn[State]` cannot be checked - the compiler emits an unchecked warning
		* and falls back to testing only the runtime class of the message. A `TypeTest` created by this method
		* does exactly that check, but in one auditable place: it verifies the runtime class of the scrutinee
		* and then casts the message to the full type, taking the type arguments on trust. The trust is based on
		* the invariant that the actor infrastructure creates and routes these messages only between actors
		* parameterized with the same `Msg` and `State` types.
		*
		* The `TypeTest` instances used by `ActorImpl` and `ActorSystem` are defined next to the `match` expressions
		* which process system messages - see `ActorImpl.processSysEntry` and `ActorSystem.processSysEntry`.
		*
		* @tparam S the supertype of the matched values - in practice `SystemMsg`
		* @tparam T the expected subtype of the matched values, e.g. `SystemMsg.Spawn[State]`
		* @return a `TypeTest[S, T]` which matches any value of the runtime class of `T`
		*/
	
	@static private[actors] final class SystemMsgTypeTest[S, T](using ct: ClassTag[T]) extends TypeTest[S, T] {
		override inline def unapply(s: S): Option[s.type & T] =
			if (ct.runtimeClass.isInstance(s)) Some(s.asInstanceOf[s.type & T]) else None
	}
		
	private[actors] inline def trusted[S, T](using ClassTag[T]): TypeTest[S, T] = new SystemMsgTypeTest[S, T]
}
