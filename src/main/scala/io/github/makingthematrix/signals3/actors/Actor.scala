package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.actors.Actor.*
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
		case Pause, Unpause, Close
		case Done
		case InvalidId
		case AddBehavior(id: String, pf: PF[Msg, Rsp, State])
		case RemoveBehavior(id: String)
		case AddPF(pf: PF[Msg, Rsp, State]) // use instead of AddBehavior if you don't care about persistence of behaviors
		case Spawn(id: String = "",
			         state: Option[State] = None,
			         behaviors: List[Actor.Beh[Msg, Rsp, State]] = Nil,
			         heartbeat: Option[Actor.HeartBeatStrategy] = None,
			         onInit: Option[MutableActor[Msg, Rsp, State] => Unit] = None,
			         useSerialDispatch: Boolean = false,
			         executionContext: Option[ExecutionContext] = None,
		          )
		case NewChild(child: Actor[Msg, Rsp, State])
		case ActorClosed(id: String)
/*		case GetRef(ref: ActorRef[Msg, Rsp])
		case Register(actor: Actor[Msg, Rsp, State])
		case AskForRef(actor: Actor[Msg, Rsp, State], id: String)*/
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
	def getBehavior(id: String): Option[PF[Msg, Rsp, State]]

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
	def ask(behId: String, msg: Msg): CloseableFuture[Rsp]
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
	def bang(behId: String, msg: Msg): Unit
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
}

object Actor {
	// todo: Pausable, v
	// todo: pausing and closing through special messages, v
	// todo: private var state: State for keeping and modifying internal state, v
	// todo: behaviors must have access to this actor to be able to mutate the state v
	// todo: heartbeat should be a strategy: Linear(ms), Agitated(min, coeff, max), Reactive v
	// todo: Scaladoc v
	// todo: unit tests v
	// todo: managing behaviors through messages v
	// todo: divide the Actor class into an immutable trait used outside and a mutable class that extends it - the behaviors use the latter v
	// todo: add the out stream that can be used by behaviors to send messages to v
	// todo: change the behaviors list to a map - all behaviors that fit for a given message are executed, not only the oldest one v
	// todo: change the name of finalBehavior to finalBehavior (the last behavior); the current one is confusing v
	// todo: change the behaviors back to a list xD v
	// todo: a way to request that a given message is handled by a behavior with the given id v
	// todo: similarly, there should be an `onClose` function (but that's already implemented) v
	// todo: onInit function that the actor can use, for example, to send out messages that it's alive v
	// todo: remove finalBehavior; unprocessed messages are ignored v
	// todo: serial actors can have fewer safe-guards (and in fact they should have)  v
	// todo: ActorBuilder v
	// todo: spawn sub-actors v
	// todo: close sub-actors when the parent is closed v

	// todo: ActorSystem where you can register new actors with unique ids
	// todo: ActorRef (local) retrieved from ActorSystem, used to send messages to other actors
	// todo: RemoteActorRef and the ability to register actors from another app via https
	// todo: LocalActorRef should carry the ActorSystem id too to enable communication between different actor systems

	// todo: HealthCheck system message, sent from the parent to the child; if the child doesn't respond in time, the message is repeated, and the the child is closed
	// todo: consider to allow the children to use different types of messages ; and then: clusters? persistance?
	// todo: maybe think about plugging in a logging functionality so that an unprocessed message can be logged as a warning
	// todo: similarly about metrics
	// todo: and about the max number of messages processed per heartbeat
	// todo: make constants configurable through environment variables
	// todo: actors should carry tags (strings) and the actor system ca get requests to connect an actor with any other actor that has a given tag

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

	def apply[Msg, Rsp, State](id: String, state: State, behavior: Beh[Msg, Rsp, State], beat: HeartBeatStrategy, parent: Actor[Msg, Rsp, State])
	                          (using ExecutionContext): ActorImpl[Msg, Rsp, State] =
		new ActorImpl(id, state, beat, Some(parent)).tap { actor =>
			actor.addBehavior(behavior)
			actor.initialize()
		}

	def apply[Msg, Rsp, State](id: String, state: State, behavior: Beh[Msg, Rsp, State], beat: HeartBeatStrategy)
	                          (using ExecutionContext): ActorImpl[Msg, Rsp, State] =
		new ActorImpl(id, state, beat, None).tap { actor =>
			actor.addBehavior(behavior)
			actor.initialize()
		}

	/**
		* Creates a new actor instance with the given initial state, final behavior, and heartbeat strategy.
		* The actor is initialized immediately after creation. It's going to use the `ExecutionContext` passed to it
		* as an implicit parameter.
		*
		* @param state    The initial state of the actor.
		* @param behavior The behavior of the actor, responsible for handling incoming messages.
		* @param beat     The heartbeat strategy used to configure the actor's responsiveness.
		* @return An initialized actor instance.
		*/
	inline def apply[Msg, Rsp, State](state: State, behavior: Beh[Msg, Rsp, State], beat: HeartBeatStrategy)
	                                 (using ExecutionContext): ActorImpl[Msg, Rsp, State] =
		apply(IdGenerator.generate(), state, behavior, beat)

	inline def apply[Msg, Rsp, State](state: State, behavior: PF[Msg, Rsp, State], beat: HeartBeatStrategy)
	                                 (using ExecutionContext): ActorImpl[Msg, Rsp, State] =
		apply(state, "default" -> behavior, beat)

	def apply[Msg, Rsp, State](id: String, state: State, behavior: Beh[Msg, Rsp, State], beat: HeartBeatStrategy,
	                           onInit: MutableActor[Msg, Rsp, State] => Unit)
	                          (using ExecutionContext): ActorImpl[Msg, Rsp, State] =
		new ActorImpl(id, state, beat).tap { actor =>
			actor.onInit(onInit)
			actor.addBehavior(behavior)
			actor.initialize()
		}
	/**
		* Creates a new actor instance with the given initial state, final behavior, and heartbeat strategy.
		* The actor is initialized immediately after creation. It's going to use the `ExecutionContext` passed to it
		* as an implicit parameter.
		*
		* @param state    The initial state of the actor.
		* @param behavior The final behavior of the actor, responsible for handling incoming messages.
		* @param beat     The heartbeat strategy used to configure the actor's responsiveness.
		* @param onInit   A function that will be called during the initialization of the actor.
		* @return An initialized actor instance.
		*/
	inline def apply[Msg, Rsp, State](state: State, behavior: Beh[Msg, Rsp, State], beat: HeartBeatStrategy,
	                           onInit: MutableActor[Msg, Rsp, State] => Unit)
	                          (using ExecutionContext): Actor[Msg, Rsp, State] =
		apply(IdGenerator.generate(), state, behavior,beat, onInit)

	inline 	def apply[Msg, Rsp, State](state: State, behavior: PF[Msg, Rsp, State], beat: HeartBeatStrategy,
	                                   onInit: MutableActor[Msg, Rsp, State] => Unit)
	                                  (using ExecutionContext): Actor[Msg, Rsp, State] =
		apply(state, "default" -> behavior, beat, onInit)

	inline def serial[Msg, Rsp, State](id: String, state: State, behavior: Beh[Msg, Rsp, State], beat: HeartBeatStrategy, parent: Actor[Msg, Rsp, State]): Actor[Msg, Rsp, State] =
		apply(id, state, behavior, beat, parent)(using DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))

	inline def serial[Msg, Rsp, State](id: String, state: State, behavior: Beh[Msg, Rsp, State], beat: HeartBeatStrategy): Actor[Msg, Rsp, State] =
		apply(id, state, behavior, beat)(using DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))

	/**
		* Creates a new actor instance with the specified initial state, final behavior, and heartbeat strategy.
		* The actor operates using a new serial dispatch queue to handle incoming messages.
		*
		* @param state    The initial state of the actor.
		* @param behavior The final behavior of the actor, responsible for handling incoming messages.
		* @param beat     The heartbeat strategy used to configure the actor's responsiveness.
		* @return An initialized actor instance.
		*/
	inline def serial[Msg, Rsp, State](state: State, behavior: Beh[Msg, Rsp, State], beat: HeartBeatStrategy): Actor[Msg, Rsp, State] =
		apply(state, behavior, beat)(using DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))

	inline def serial[Msg, Rsp, State](state: State, pf: PF[Msg, Rsp, State], beat: HeartBeatStrategy): Actor[Msg, Rsp, State] =
		serial(state, "default" -> pf, beat)

	inline def serial[Msg, Rsp, State](id: String, state: State, behavior: Beh[Msg, Rsp, State], beat: HeartBeatStrategy,
	                                   onInit: MutableActor[Msg, Rsp, State] => Unit): Actor[Msg, Rsp, State] =
		apply(id, state, behavior, beat, onInit)(using DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))
	/**
		* Creates a new actor instance with the specified initial state, final behavior, and heartbeat strategy.
		* The actor operates using a new serial dispatch queue to handle incoming messages.
		*
		* @param state    The initial state of the actor.
		* @param behavior The final behavior of the actor, responsible for handling incoming messages.
		* @param beat     The heartbeat strategy used to configure the actor's responsiveness.
		* @param onInit   A function that will be called during the initialization of the actor.
		* @return An initialized actor instance.
		*/
	inline def serial[Msg, Rsp, State](state: State, behavior: Beh[Msg, Rsp, State], beat: HeartBeatStrategy,
	                                   onInit: MutableActor[Msg, Rsp, State] => Unit): Actor[Msg, Rsp, State] =
		apply(state, behavior, beat, onInit)(using DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))

	inline def serial[Msg, Rsp, State](state: State, pf: PF[Msg, Rsp, State], beat: HeartBeatStrategy,
	                                   onInit: MutableActor[Msg, Rsp, State] => Unit): Actor[Msg, Rsp, State] =
		serial(state, "default" -> pf, beat, onInit)

	/**
		* Creates a new actor instance with the given initial state and a final behavior, while the heartbeat strategy
		* is set to Linear(100ms).
		* The actor is initialized immediately after creation. It's going to use the `ExecutionContext` passed to it
		* as an implicit parameter.
		*
		* @param state    The initial state of the actor.
		* @param behavior The final behavior of the actor, responsible for handling incoming messages.
		* @return An initialized actor instance.
		*/
	inline def apply[Msg, Rsp, State](state: State, behavior: Beh[Msg, Rsp, State])(using ExecutionContext): Actor[Msg, Rsp, State] =
		apply(state, behavior, defBeat)

	inline def apply[Msg, Rsp, State](state: State, pf: PF[Msg, Rsp, State])(using ExecutionContext): Actor[Msg, Rsp, State] =
		apply(state, "default" -> pf)

	/**
		* Creates a new actor instance with the given initial state and a final behavior, while the heartbeat strategy
		* is set to Linear(100ms).
		* The actor is initialized immediately after creation. It's going to use the `ExecutionContext` passed to it
		* as an implicit parameter.
		*
		* @param state    The initial state of the actor.
		* @param behavior The final behavior of the actor, responsible for handling incoming messages.
		* @param onInit   A function that will be called during the initialization of the actor.
		* @return An initialized actor instance.
		*/
	inline def apply[Msg, Rsp, State](state: State, behavior: Beh[Msg, Rsp, State], onInit: MutableActor[Msg, Rsp, State] => Unit)
	                                 (using ExecutionContext): Actor[Msg, Rsp, State] =
		apply(state, behavior, defBeat, onInit)

	inline def apply[Msg, Rsp, State](state: State, pf: PF[Msg, Rsp, State], onInit: MutableActor[Msg, Rsp, State] => Unit)
	                                 (using ExecutionContext): Actor[Msg, Rsp, State] =
		apply(state, "default" -> pf,  onInit)

	/**
		* Creates a new actor instance with the specified initial state, and a final behavior, while the heartbeat strategy
		* * is set to Linear(100ms). The actor operates using a new serial dispatch queue to handle incoming messages.
		*
		* @param state    The initial state of the actor.
		* @param behavior The final behavior of the actor, responsible for handling incoming messages.
		* @return An initialized actor instance.
		*/
	inline def serial[Msg, Rsp, State](state: State, behavior: Beh[Msg, Rsp, State]): Actor[Msg, Rsp, State] =
		serial(state, behavior, defBeat)

	inline def serial[Msg, Rsp, State](state: State, pf: PF[Msg, Rsp, State]): Actor[Msg, Rsp, State] =
		serial(state, "default" -> pf)

	/**
		* Creates a new actor instance with the specified initial state, and a final behavior, while the heartbeat strategy
		* is set to Linear(100ms). The actor operates using a new serial dispatch queue to handle incoming messages.
		*
		* @param state    The initial state of the actor.
		* @param behavior The final behavior of the actor, responsible for handling incoming messages.
		* @param onInit   A function that will be called during the initialization of the actor.
		* @return An initialized actor instance.
		*/
	inline def serial[Msg, Rsp, State](state: State, behavior: Beh[Msg, Rsp, State],
	                                   onInit: MutableActor[Msg, Rsp, State] => Unit): Actor[Msg, Rsp, State] =
		serial(state, behavior, defBeat, onInit)

	inline def serial[Msg, Rsp, State](state: State, pf: PF[Msg, Rsp, State],
	                                   onInit: MutableActor[Msg, Rsp, State] => Unit): Actor[Msg, Rsp, State] =
		serial(state, "default" -> pf, onInit)

	def apply[Msg, Rsp, State](id: String, state: State, pfs: List[PF[Msg, Rsp, State]], beat: HeartBeatStrategy)
	                          (using ExecutionContext): Actor[Msg, Rsp, State] =
		new ActorImpl[Msg, Rsp, State](id, state, beat).tap { actor =>
			actor.addBehaviors(pfs)
			actor.initialize()
		}
	/**
		* Creates a new actor instance with the provided initial state, a list of partial functions
		* for behavior, and a heartbeat strategy. The actor is initialized immediately after creation
		* and will use the provided `ExecutionContext` for its operation.
		*
		* @param state The initial state of the actor.
		* @param pfs   A list of partial functions that define the actor's behaviors. Each function
		*              specifies how the actor should handle a specific type of message.
		* @param beat  The heartbeat strategy used to configure the actor's responsiveness.
		* @return An initialized actor instance configured with the given state, behaviors,
		*         and heartbeat strategy.
		*/
	inline def apply[Msg, Rsp, State](state: State, pfs: List[PF[Msg, Rsp, State]], beat: HeartBeatStrategy)
	                                 (using ExecutionContext): Actor[Msg, Rsp, State] =
		apply(IdGenerator.generate(), state, pfs, beat)

	def apply[Msg, Rsp, State](id: String, state: State, pfs: List[PF[Msg, Rsp, State]], beat: HeartBeatStrategy,
	                           onInit: MutableActor[Msg, Rsp, State] => Unit)
	                          (using ExecutionContext): Actor[Msg, Rsp, State] =
		new ActorImpl[Msg, Rsp, State](id, state, beat).tap { actor =>
			actor.addBehaviors(pfs)
			actor.onInit(onInit)
			actor.initialize()
		}
	/**
		* Creates a new actor instance with the provided initial state, a list of partial functions
		* for behavior, and a heartbeat strategy. The actor is initialized immediately after creation
		* and will use the provided `ExecutionContext` for its operation.
		*
		* @param state  The initial state of the actor.
		* @param pfs    A list of partial functions that define the actor's behaviors. Each function
		*               specifies how the actor should handle a specific type of message.
		* @param beat   The heartbeat strategy used to configure the actor's responsiveness.
		* @param onInit A function that will be called during the initialization of the actor.
		* @return An initialized actor instance configured with the given state, behaviors,
		*         and heartbeat strategy.
		*/
	inline def apply[Msg, Rsp, State](state: State, pfs: List[PF[Msg, Rsp, State]], beat: HeartBeatStrategy,
	                           onInit: MutableActor[Msg, Rsp, State] => Unit)
	                          (using ExecutionContext): Actor[Msg, Rsp, State] =
		apply(IdGenerator.generate(), state, pfs, beat, onInit)

	inline def serial[Msg, Rsp, State](id: String, state: State, pfs: List[PF[Msg, Rsp, State]], beat: HeartBeatStrategy): Actor[Msg, Rsp, State] =
		apply(id, state, pfs, beat)(using DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))
	/**
		* Creates a new actor instance with the given initial state, a list of partial functions
		* defining its behaviors, and a heartbeat strategy.
		* The actor operates using a new serial dispatch queue to handle incoming messages.
		*
		* @param state The initial state of the actor.
		* @param pfs   A list of partial functions representing the actor's behavior. Each partial
		*              function specifies how the actor should process specific types of messages.
		* @param beat  The heartbeat strategy that determines the actor's responsiveness.
		* @return An initialized actor instance configured with the specified state, behaviors,
		*         and heartbeat strategy, operating on a serial dispatch queue.
		*/
	inline def serial[Msg, Rsp, State](state: State, pfs: List[PF[Msg, Rsp, State]], beat: HeartBeatStrategy): Actor[Msg, Rsp, State] =
		apply(state, pfs, beat)(using DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))

	inline def serial[Msg, Rsp, State](id: String, state: State, pfs: List[PF[Msg, Rsp, State]], beat: HeartBeatStrategy,
	                                   onInit: MutableActor[Msg, Rsp, State] => Unit): Actor[Msg, Rsp, State] =
		apply(id, state, pfs, beat, onInit)(using DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))
	/**
		* Creates a new actor instance with the given initial state, a list of partial functions
		* defining its behaviors, and a heartbeat strategy.
		* The actor operates using a new serial dispatch queue to handle incoming messages.
		*
		* @param state  The initial state of the actor.
		* @param pfs    A list of partial functions representing the actor's behavior. Each partial
		*               function specifies how the actor should process specific types of messages.
		* @param beat   The heartbeat strategy that determines the actor's responsiveness.
		* @param onInit A function that will be called during the initialization of the actor.
		* @return An initialized actor instance configured with the specified state, behaviors,
		*         and heartbeat strategy, operating on a serial dispatch queue.
		*/
	inline def serial[Msg, Rsp, State](state: State, pfs: List[PF[Msg, Rsp, State]], beat: HeartBeatStrategy,
	                                   onInit: MutableActor[Msg, Rsp, State] => Unit): Actor[Msg, Rsp, State] =
		apply(state, pfs, beat, onInit)(using DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))

	/**
		* Creates a new actor instance with the provided initial state and a list of partial functions
		* defining the actor's behaviors. The actor is immediately initialized and uses the implicit
		* `ExecutionContext` for its operations. The heartbeat strategy is sset to Linear(100ms).
		*
		* @param state The initial state of the actor.
		* @param pfs   A list of partial functions defining the behavior of the actor. Each partial
		*              function specifies how the actor should process specific types of messages.
		* @return An initialized actor instance configured with the specified state and behaviors.
		*/
	inline def apply[Msg, Rsp, State](state: State, pfs: List[PF[Msg, Rsp, State]])(using ExecutionContext): Actor[Msg, Rsp, State] =
		apply(state, pfs, defBeat)

	/**
		* Creates a new actor instance with the provided initial state and a list of partial functions
		* defining the actor's behaviors. The actor is immediately initialized and uses the implicit
		* `ExecutionContext` for its operations. The heartbeat strategy is sset to Linear(100ms).
		*
		* @param state  The initial state of the actor.
		* @param pfs    A list of partial functions defining the behavior of the actor. Each partial
		*               function specifies how the actor should process specific types of messages.
		* @param onInit A function that will be called during the initialization of the actor.
		* @return An initialized actor instance configured with the specified state and behaviors.
		*/
	inline def apply[Msg, Rsp, State](state: State, pfs: List[PF[Msg, Rsp, State]],
	                                  onInit: MutableActor[Msg, Rsp, State] => Unit)(using ExecutionContext): Actor[Msg, Rsp, State] =
		apply(state, pfs, defBeat, onInit)

	/**
		* Creates a new actor instance with the provided initial state and a list of partial functions
		* defining its behaviors. The actor operates using a new serial dispatch queue to handle
		* incoming messages. The the heartbeat strategy is set to Linear(100ms).
		*
		* @param state The initial state of the actor.
		* @param pfs   A list of partial functions defining the actor's behavior. Each partial
		*              function specifies how the actor should process specific types of messages.
		* @return An initialized actor instance configured with the specified state and behaviors,
		*         operating on a serial dispatch queue.
		*/
	inline def serial[Msg, Rsp, State](state: State, pfs: List[PF[Msg, Rsp, State]]): Actor[Msg, Rsp, State] =
		serial(state, pfs, defBeat)

	/**
		* Creates a new actor instance with the provided initial state and a list of partial functions
		* defining its behaviors. The actor operates using a new serial dispatch queue to handle
		* incoming messages. The the heartbeat strategy is set to Linear(100ms).
		*
		* @param state  The initial state of the actor.
		* @param pfs    A list of partial functions defining the actor's behavior. Each partial
		*               function specifies how the actor should process specific types of messages.
		* @param onInit A function that will be called during the initialization of the actor.
		* @return An initialized actor instance configured with the specified state and behaviors,
		*         operating on a serial dispatch queue.
		*/
	inline def serial[Msg, Rsp, State](state: State, pfs: List[PF[Msg, Rsp, State]],
	                                   onInit: MutableActor[Msg, Rsp, State] => Unit): Actor[Msg, Rsp, State] =
		serial(state, pfs, defBeat, onInit)
}
