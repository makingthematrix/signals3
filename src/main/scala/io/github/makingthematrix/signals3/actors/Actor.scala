package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.actors.Actor.*
import io.github.makingthematrix.signals3.actors.Actor.HeartBeatStrategy.Linear
import io.github.makingthematrix.signals3.{CloseableFuture, DispatchQueue, Signal, SourceStream, Stream}

import scala.annotation.static
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.chaining.*
import scala.util.{Failure, Success}

/**
	* A lightweight actor that can be used to process messages asynchronously.
	*
	* The Actor model provides a way to create concurrent and distributed systems by encapsulating state
	* and behavior into individual actors that communicate with each other through message passing.
	*
	* Traditionally, an actor sits on top of a given component: a database, a file system, or a network connection,
	* and acts as a gateway between them. But in some projects, we try to use them to model small, independent entities,
	* like NPCs in games, neurons in artificial neural networks, cells in cellular automata, or individual nodes in
	* simulations of distributed systems.
	*
	* This implementation is designed to be lightweight and easy to use, with a focus on simplicity and performance, so
	* that it might make sense in both those use cases: as a big gateway between components, or as a small, independent entity.
  *
	* @tparam Msg The type of the incoming message
	* @tparam Rsp The type of the response
	* @tparam State The type of the internal state
	*/
trait Actor[Msg, Rsp, State] {
	/**
		* Represents system-level messages that can be used to control or affect the behavior
		* of an actor. These messages are typically utilized for lifecycle management or operational changes within a system.
		*
		* The `SystemMsg` enum contains the following members:
		* - `Pause`: the actor should temporarily suspend operations.
		* - `Unpause`: the actor should resume operations after being paused.
		* - `Close`: the actor should terminate its operations.
		* - `AddBehavior(id, pf)` - adds a new behavior to the actor
		* - `RemoveBehavior(id)` - removes a behavior from the actor
		*/
	enum SystemMsg {
		case Pause, Unpause, Close, Done, InvalidId
		case AddBehavior(beh: Beh[Msg, Rsp, State])
		case RemoveBehavior(behId: String)
		case AddBehaviorPF(pf: PF[Msg, Rsp, State]) // use instead of AddBehavior if you don't care about persistence of behaviors
		case Spawn(actorId: String = "",
		           state: Option[State] = None,
		           behaviors: List[Actor.Beh[Msg, Rsp, State]] = Nil,
		           heartbeat: Option[Actor.HeartBeatStrategy] = None,
		           onInit: Option[MutableActor[Msg, Rsp, State] => Unit] = None,
		           useSerialDispatch: Boolean = false,
		           executionContext: Option[ExecutionContext] = None,
		          )
		case NewChild(child: Actor[Msg, Rsp, State])
		case ActorClosed(actorId: String)
		case Register(actor: Actor[Msg, Rsp, State])
		case Unregister(actorId: String)
		case Ref(ref: ActorRef[Msg, Rsp])
		case AskForRef(actorId: String, systemId: String = "")
		case AskForRefAsync(sender: Actor[Msg, Rsp, State], actorId: String, systemId: String = "")
		case RegisterSystem(system: RemoteSystem[Msg, Rsp])
		case UnregisterSystem(systemId: String)
	}

	def id: String

	/** The input stream for handling incoming messages of type `Msg`.
		*
		* You can send messages directly to the actor, using "!" (bang) and "?" (ask) operators.
		* But if the messages are the result of event streams operations (e.g. they are coming from the http endpoints)
		* it might be more convinient to pipe them automatically to the exposed "in" stream.
		*
		* @see [[Stream.pipeTo]]
		*/
	def in: SourceStream[Msg]

	/** The **optional** output stream that may be used by the behaviors to push out a new response.
		*
		* "Optional" is a keyword here. It's totally up to a behavior if it decides to send a response to `out`.
		* You may build your actor in such a way that it operates solely on the `in` and `out` streams, you can forget
		* about them, or you can do anything in-between.
		*/
	def out: Stream[Rsp]

	/**
		* Retrieves a behavior from the actor's list of behaviors based on its unique identifier.
		*
		* @param id The unique identifier of the behavior to retrieve.
		* @return An `Option` containing the partial function defining the behavior, if found; otherwise, `None`.
		*/
	def getBehavior(id: String): Option[Beh[Msg, Rsp, State]]

	/**
		* Sends a system message to the actor, expecting a response in the form of a `CloseableFuture`.
		*
		* This is a direct way to send a system message to the actor a request a response. The message will be processed
		* asynchronously, depending on the heartbeat strategy. When it is processed, the sender will be notified of the it
		* because the associated `CloseableFuture` will finish with success.
		*
		* @param msg the message to send to the actor.
		* @return a `CloseableFuture` of the type `Unit`.
		*/
	def ask(msg: SystemMsg): CloseableFuture[SystemMsg]
	inline def ?(msg: SystemMsg): CloseableFuture[SystemMsg] = ask(msg)

	/**
		* Sends a message to the actor, expecting a response in the form of a `CloseableFuture`.
		*
		* This is a direct way to send a message to the actor a request a response. The message will be processed asynchronously,
		* depending on the heartbeat strategy. When it is processed, the result will be sent back to the sender as the result
		* of the associated `CloseableFuture`. The sender may await that result, or simply check if the processing is successful.
		* They may also close the future if the result is not longer needed, or ignore it - but in that case it's better to use
		* the "!" operator instead.
		*
		* @param behId An optional parameter for forcing the identified behavior to process the message. Leave out for regular processing.
		* @param msg the message to send to the actor.
		* @return a `CloseableFuture` containing the response from the actor.
		*/
	def ask(msg: Msg, actorPath: ActorPath, behId: String): CloseableFuture[Rsp]
	inline def ask(behId: String, msg: Msg): CloseableFuture[Rsp] = ask(msg, ActorPath.Direct, behId)
	inline def ask(t: (String, Msg)): CloseableFuture[Rsp] = ask(t._1, t._2)
	inline def ?(t: (String, Msg)): CloseableFuture[Rsp] = ask(t)
	inline def ask(msg: Msg): CloseableFuture[Rsp] = ask("", msg)
	inline def ?(msg: Msg): CloseableFuture[Rsp] = ask(msg)

	/**
		* Sends a system message to the actor.
		*
		* System messages are defined in [[ActorImpl.SystemMsg]]. They are processed asynchronously, just like regular messages
		* but they are not affected by the actor being paused (since  a system message might be used to unpause or close
		* a paused actor). No response will be returned to the sender.
		*
		* @param msg the message to send to the actor.
		*/
	def bang(msg: SystemMsg): Unit
	inline def !(msg: SystemMsg): Unit = bang(msg)

	/**
		* Sends a message to the actor without expecting a response.
		*
		* This method is used to asynchronously send a message to the actor.
		* The message will be processed according to the actor's behavior,
		* but no response will be returned to the sender. This is useful
		* for fire-and-forget scenarios where the sender does not need to
		* track the result of the message processing.
		*
		* @param behId An optional parameter for forcing the identified behavior to process the message. Leave out for regular processing.
		* @param msg The message to be sent to the actor.
		*/
	def bang(msg: Msg, actorPath: ActorPath, behId: String): Unit
	inline def bang(behId: String, msg: Msg): Unit = bang(msg, ActorPath.Direct, behId)
	inline def bang(t: (String, Msg)): Unit = bang(t._1, t._2)
	inline def !(t: (String, Msg)): Unit = bang(t)
	inline def bang(msg: Msg): Unit = bang("", msg)
	inline def !(msg: Msg): Unit = bang(msg)

	/**
		* Retrieves the current state of the actor
		* @return the current state
		*/
	def state: State

	/**
		* Retrieves the current heartbeat strategy of the actor
		* @return the current heartbeat strategy
		*/
	protected def heartbeat: HeartBeatStrategy

	/**
		* Returns a signal that works on a given [[scala.concurrent.ExecutionContext]]; it starts with the value set to `false` (unless it's
		* created after the actor is already initialized) and it will be set to `true` when the actor is initialized.
		*
		* @return A signal that will be set to `true` when the actor is initialzied.
		*/
	def isInitializedSignal(using ExecutionContext): Signal[Boolean]
	
	def isInitialized: Boolean

	val isSerial: Boolean
	
	def isClosed: Boolean
	
	def isClosedSignal(using ExecutionContext): Signal[Boolean]
	
	def isPaused: Boolean
	
	def isPausedSignal: Signal[Boolean]

	def parent: Option[Actor[Msg, Rsp, State]]

	def system: Option[ActorSystem[Msg, Rsp, State]]
}

object Actor {
	@static private val noResponse: Failure[Nothing] = Failure[Nothing](new IllegalStateException("No response"))
	@static private val ignored: Success[Option[Nothing]] = Success[Option[Nothing]](None)
	@static private[actors] val actorIsClosed = IllegalStateException("Actor is closed")

	/**
		* A special type of a failure indicating that although the message was received via the "?" (ask) operator and it was
		* processed, no response was given as the result.
		*
		* @return A `Failure` instance wrapping an `IllegalStateException`: "no response".
		*/
	inline def NoResponse[Rsp]: Failure[Rsp] = noResponse.asInstanceOf[Failure[Rsp]]

	/**
		* A special type of response, indicating the incoming message was ignored. It's not necessarily an error.
		* @return A `Success` instance wrapping `None`
		*/
	inline def Ignored[Rsp]: Success[Option[Rsp]] = ignored.asInstanceOf[Success[Option[Rsp]]]

	inline def ActorIsClosed[Rsp](using ExecutionContext): CloseableFuture[Rsp] = CloseableFuture.failed[Rsp](actorIsClosed)
	
	inline def invalidActorId[Rsp](actorId: String)(using ExecutionContext): CloseableFuture[Rsp] =
		CloseableFuture.failed(new IllegalArgumentException(s"Invalid actor id: $actorId"))

	inline def unhandledMsg[Msg, Rsp](msg: Msg)(using ExecutionContext): CloseableFuture[Rsp] =
		CloseableFuture.failed(new IllegalArgumentException(s"Unhandled message: $msg"))

	inline def wrongPath[Rsp](path: ActorPath)(using ExecutionContext): CloseableFuture[Rsp] =
		CloseableFuture.failed(new IllegalArgumentException(s"wrong path: $path"))

	// The type of a custom behavior: a partial function that takes a message and an actor and returns an optional response.
	type PF[Msg, Rsp, State] = PartialFunction[(Msg, MutableActor[Msg, Rsp, State]), Option[Rsp]]
	// A shorthand for behavior tuples
	type Beh[Msg, Rsp, State] = (id: String, pf: PF[Msg, Rsp, State])

	/**
		* Represents a strategy for configuring the heartbeat of an actor.
		*
		* This enum defines various approaches to managing heartbeat intervals, suitable for
		* different scenarios based on the requirements of responsiveness.
		*/
	enum HeartBeatStrategy(val timeout: FiniteDuration) {
		case Linear(ms: Long, override val timeout: FiniteDuration = 5.second) extends HeartBeatStrategy(timeout)
		case Agitated(minMs: Long, coeff: Double, maxMs: Long, override val timeout: FiniteDuration = 5.second) extends HeartBeatStrategy(timeout)
		case Reactive(maxMs: Long, maxMsgs: Int, override val timeout: FiniteDuration = 5.second) extends HeartBeatStrategy(timeout)
	}

	/**
		* Default heartbeat strategy for the actor.
		*
		* By default, the strategy is set to `HeartBeatStrategy.Linear` with a heartbeat interval of 100 milliseconds.
		*
		* @see [[HeartBeatStrategy]]
		*/
	val defBeat: HeartBeatStrategy = HeartBeatStrategy.Linear(100L)

	/**
		* Creates a new actor instance with the given initial state, behavior, and heartbeat strategy.
		* The actor is initialized immediately after creation. It's going to use the `ExecutionContext` passed to it
		* as an implicit parameter.
		*
		* Use only when you want a single independent actor.
		* For a bigger system, use [[ActorSystem]] and/or [[ActorBuilder]].
		*
		* @param state    The initial state of the actor.
		* @param behavior The behavior of the actor, responsible for handling incoming messages.
		* @param beat     The heartbeat strategy used to configure the actor's responsiveness.
		* @return An initialized actor instance.
		*/
	def apply[Msg, Rsp, State](state: State, behavior: Beh[Msg, Rsp, State], beat: HeartBeatStrategy)
	                          (using ExecutionContext): Actor[Msg, Rsp, State] =
		new ActorImpl(IdGenerator.generate(), state, beat).tap { actor =>
			actor.addBehavior(behavior)
			actor.initialize()
		}
		
	inline def serial[Msg, Rsp, State](state: State, behavior: Beh[Msg, Rsp, State], beat: HeartBeatStrategy): Actor[Msg, Rsp, State] =
		apply(state, behavior, beat)(using DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))

	inline def apply[Msg, Rsp](behavior: Beh[Msg, Rsp, Unit], beat: HeartBeatStrategy)
	                          (using ExecutionContext): Actor[Msg, Rsp, Unit] =
		apply((), behavior, beat)

	inline def serial[Msg, Rsp](behavior: Beh[Msg, Rsp, Unit], beat: HeartBeatStrategy): Actor[Msg, Rsp, Unit] =
		serial((), behavior, beat)

	inline def apply[Msg, Rsp](behavior: Beh[Msg, Rsp, Unit])(using ExecutionContext): Actor[Msg, Rsp, Unit] =
		apply((), behavior, defBeat)

	inline def serial[Msg, Rsp](behavior: Beh[Msg, Rsp, Unit]): Actor[Msg, Rsp, Unit] = serial((), behavior, defBeat)
}
