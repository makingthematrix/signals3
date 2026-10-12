package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.actors.Actor.{Beh, HeartBeatStrategy, PF}

import scala.concurrent.ExecutionContext

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
	* - `AddBehavior(id, pf)` - adds a new behavior to the actor
	* - `RemoveBehavior(id)` - removes a behavior from the actor
	* - `AddBehaviorPF(pf)` - use instead of `AddBehavior`` if you don't care about persistence of behaviors
	* - `Spawn(...)` - the actor should spawn a new sub-actor with the specified parameters.
	* - `NewChild(child)` - the response for `Spawn` with the new actor's JVM reference (todo: change to ActorRef?)
	* - `ActorClosed(actorId)` - informs that another actor has closed
	* - `Register(actor)` - used to register a new actor with the system or another actor dictionary
	* - `Unregister(actorId)` - used to unregister an actor from the system or another actor dictionary
	* - `AskForRef(actorId, systemId)` - asks for another actor's reference and expects the response to come through `ask`
	* - `AskForRefAsync(sender, actorId, systemId)` - asks for another actor's reference and expects the response to come as a separate message
	* - `Ref(ref)` - a response to `AskForRef` and `AskForRefAsync`; passes another actor's reference to the actor
	* - `Requeue(actorId, msg, behId, tryNumber)` - requeues a message so it will be processed again
	*/
enum SystemMsg{
	case Pause, Unpause, Close, Done, InvalidId
	case AddBehavior[Msg, State](beh: Beh[Msg, State])
	case RemoveBehavior(behId: String)
	case AddBehaviorPF[Msg, State](pf: PF[Msg, State])
	case Spawn[Msg, State](actorId: String = "",
	                       state: Option[State] = None,
	                       behaviors: List[Beh[Msg, State]] = Nil,
	                       heartbeat: Option[HeartBeatStrategy] = None,
	                       onInit: Option[MutableActor[Msg, State] => Unit] = None,
	                       useSerialDispatch: Boolean = false,
	                       executionContext: Option[ExecutionContext] = None
	                      )
	case NewChild[Msg, State](child: Actor[Msg, State])
	case ActorClosed(actorId: String)
	case Register[Msg, State](actor: Actor[Msg, State])
	case Unregister(actorId: String)
	case AskForRef(actorId: String, systemId: String = "")
	case AskForRefAsync[Msg, State](sender: Actor[Msg, State], actorId: String, systemId: String = "")
	case Ref[Msg](ref: ActorRef[Msg])
	case Requeue[Msg](actorId: String, msg: Msg, behId: String, tryNumber: Int = 0)
	case SystemClosed(systemId: String)
	case RegisterSystem[Msg](system: RemoteSystem[Msg])
	case UnregisterSystem(systemId: String)
}
