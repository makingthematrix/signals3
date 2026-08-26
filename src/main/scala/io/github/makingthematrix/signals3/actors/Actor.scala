package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.{Closeable, CloseableFuture, DispatchQueue, Pausable, SourceStream, Stream}
import io.github.makingthematrix.signals3.actors.Actor.{F, HeartBeatStrategy, NoResponse, PF}
import io.github.makingthematrix.signals3.generators.GeneratorStream

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.{static, targetName}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.{Failure, Success, Try}
import scala.util.chaining.*

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
trait Actor[Msg, Rsp, State] extends Closeable with Pausable {
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
		case AddBehavior(id: String, pf: PF[Msg, Rsp, State])
		case RemoveBehavior(id: String)
	}

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
	@targetName("ask") def ?(msg: SystemMsg): CloseableFuture[Unit]

	/**
		* Sends a message to the actor, expecting a response in the form of a `CloseableFuture`.
		*
		* This is a direct way to send a message to the actor a request a response. The message will be processed asynchronously,
		* depending on the heartbeat strategy. When it is processed, the result will be sent back to the sender as the result
		* of the associated `CloseableFuture`. The sender may await that result, or simply check if the processing is successful.
		* They may also close the future if the result is not longer needed, or ignore it - but in that case it's better to use
		* the "!" operator instead.
		*
		* @param msg the message to send to the actor.
		* @return a `CloseableFuture` containing the response from the actor.
		*/
	@targetName("ask") def ?(msg: Msg): CloseableFuture[Rsp]

	/**
		* Sends a system message to the actor.
		*
		* System messages are defined in [[ActorImpl.SystemMsg]]. They are processed asynchronously, just like regular messages
		* but they are not affected by the actor being paused (since  a system message might be used to unpause or close
		* a paused actor). No response will be returned to the sender.
		*
		* @param msg the message to send to the actor.
		*/
	@targetName("bang") def !(msg: SystemMsg): Unit

	/**
		* Sends a message to the actor without expecting a response.
		*
		* This method is used to asynchronously send a message to the actor.
		* The message will be processed according to the actor's behavior,
		* but no response will be returned to the sender. This is useful
		* for fire-and-forget scenarios where the sender does not need to
		* track the result of the message processing.
		*
		* @param msg The message to be sent to the actor.
		*/
	@targetName("bang") def !(msg: Msg): Unit

	/**
		* Retrieves the current state of the actor
		* @return the current state
		*/
	def state: State

	/**
		* Retrieves the current default behavior of the actor
		* @return the current default behavior
		*/
	def defBehavior: F[Msg, Rsp, State]

	/**
		* Retrieves the current heartbeat strategy of the actor
		* @return the current heartbeat strategy
		*/
	def heartbeat: HeartBeatStrategy
}

/**
	* A trait representing a mutable actor, which is an extension of the `Actor` trait. This actor
	* allows dynamic modification of its internal state and behavior at runtime. It introduces methods
	* to update the actor's default behavior, heartbeat strategy, and state, as well as to add or remove
	* behaviors dynamically.
	*
	* Mainly used by the `Behavior` functions.
	*
	* @tparam Msg The type of the incoming message
	* @tparam Rsp The type of the response
	* @tparam State The type of the internal state
	*/
trait MutableActor[Msg, Rsp, State] extends Actor[Msg, Rsp, State] {
	/**
		* Enables the behavior method to alter the actor's state
		* @param newState the new state of the actor
		*/
	def state_=(newState: State): Unit

	/**
		* Enables the behavior method to alter the default behavior
		* @param newDefBehavior the new default behavior
		*/
	def defBehavior_=(newDefBehavior: F[Msg, Rsp, State]): Unit

	/**
		* Enables the behavior method to alter the heartbeat strategy
		* @param newHeartbeat the new heartbeat strategy
		*/
	def heartbeat_=(newHeartbeat: HeartBeatStrategy): Unit

	/**
		* Adds a new behavior to the actor. The behavior is appended to the list of existing behaviors,
		* meaning it will be executed only if all preceding behaviors fail to handle the message.
		*
		* @param id A unique identifier for the behavior being added.
		* @param pf The behavior function represented as a partial function that takes a message
		*           and an actor, and optionally returns a response.
		*/
	def addBehavior(id: String, pf: PF[Msg, Rsp, State]): Unit

	/**
		* Adds a new behavior to the actor. The behavior is appended to the list of existing behaviors,
		* meaning it will be executed only if all preceding behaviors fail to handle the message.
		*
		* @param behavior The behavior to be added, represented as a tuple containing a unique identifier
		*                 and a partial function that defines the behavior logic.
		*/
	def addBehavior(behavior: (id: String, pf: PF[Msg, Rsp, State])): Unit

	/**
		* Adds a behavior function to the actor and returns a unique identifier for it.
		* The behavior is appended to the list of existing behaviors, which are evaluated
		* in order when processing a message. The newly added behavior will only be executed
		* if all preceding behaviors fail to handle the message.
		*
		* @param pf A partial function that represents the behavior logic. It takes a message,
		*           a response, and the state, and optionally handles the message.
		*
		* @return   A unique identifier for the newly added behavior.
		*/
	def addBehavior(pf: PF[Msg, Rsp, State]): String =
		UUID.randomUUID().toString.tap { name => addBehavior(name -> pf) }

	/**
		* Adds the provided partial function as a behavior to this entity.
		*
		* @param pf A partial function that defines how the entity responds to specific messages,
		*           including mappings from messages to responses and potential state transitions.
		*
		* @return   A string indicating the behavior addition result or status.
		*/
	@targetName("plus") def +(pf: PF[Msg, Rsp, State]): String = addBehavior(pf)

	/**
		* Removes a behavior from the actor's list of behaviors based on its unique identifier.
		* The specified behavior will no longer be part of the message processing sequence.
		*
		* @param id The unique identifier of the behavior to be removed.
		*/
	def removeBehavior(id: String): Unit

	/**
		* Removes a specific behavior from the actor's list of behaviors, based on the reference its function,
		* given that it's the same reference that was used to add it.
		*
		* @param pf A reference to a partial function defining the behavior to be removed
		*/
	def removeBehavior(pf: PF[Msg, Rsp, State]): Unit

	/**
		* Removes a specific behavior from the actor's list of behaviors, based on the reference its function,
		* given that it's the same reference that was used to add it.
		*
		* @param pf A reference to a partial function defining the behavior to be removed
		*/
	@targetName("minus") def -(pf: PF[Msg, Rsp, State]): Unit = removeBehavior(pf)

	/**
		* Removes a behavior from the actor's list of behaviors based on its unique identifier.
		* The specified behavior will no longer be part of the message processing sequence.
		*
		* @param id The unique identifier of the behavior to be removed.
		*/
	@targetName("minus") def -(id: String): Unit = removeBehavior(id)

	/** The **optional** output stream that may be used by the behaviors to push out a new response.
		*
		* "Optional" is a keyword here. It's totally up to a behavior if it decides to send a response to `out`.
		* You may build your actor in such a way that it operates solely on the `in` and `out` streams, you can forget
		* about them, or you can do anything in-between.
		*
		* In `MutableActor` the type of `out` changes to `SourceStream[Rsp]` so that the behavior may send a response to it.
		*/
	override def out: SourceStream[Rsp]
}

/**
	* Represents an implementation of a mutable actor with a customizable state, behaviors, and heartbeat strategy.
	*
	* @tparam Msg   The type of messages processed by this actor.
	* @tparam Rsp   The type of responses returned by this actor.
	* @tparam State The type representing the internal state of the actor.
	*
	*               This class is designed to manage asynchronous message processing. It maintains:
	*               - A mutable state of the actor.
	*               - A queue for incoming messages and system messages.
	*               - A list of behavior functions to handle messages.
	*               - A heartbeat strategy to schedule message processing.
	*
	*               The actor can be configured with custom behaviors and handles messages using
	*               a prioritized strategy based on the order of the behaviors.
	*/
final private[actors] class ActorImpl[Msg, Rsp, State](private var _state: State,
                                                       private var _defBehavior: F[Msg, Rsp, State] = Actor.ignoreMsg,
                                                       private var _heartbeat: HeartBeatStrategy = HeartBeatStrategy.Linear(100L))
                                                      (using ExecutionContext)
	extends MutableActor[Msg, Rsp, State] {
	import HeartBeatStrategy.*

	// a mutable queue of messages incoming from other actors and other sources; see the ! operator.
	private val msgs         = mutable.Queue[(Msg, Option[Promise[Rsp]])]()
	// a stream that serves as a single entry for the msgs list to prevent concurrent modification; see the "!" operator.
	private val msgStream    = Stream[(Msg, Option[Promise[Rsp]])]()
	// a mutable queue of system messages incoming from the controller; see the ! operator.
	private val systemMsgs   = mutable.Queue[(SystemMsg, Option[Promise[Unit]])]()
	// a stream that serves as a single entry for the systemMsgs list to prevent concurrent modification; see the "! operator.
	private val systemStream = Stream[(SystemMsg, Option[Promise[Unit]])]()
	// a variable list of behaviors; a behavior is a partial function that tries to process an incoming message; see the processMessages method.
	private var behaviors    = List.empty[(id: String, pf: PF[Msg, Rsp, State])]
	// the "beating heart" of the actor; depending on the strategy, accumulated messages are processed at each beat or when the message appears (reactive).
	private lazy val beat    = GeneratorStream.heartbeat(() => interval())

	// the next agitation time of the actor in milliseconds); used to determine the interval between beats when using the Agitated heartbeat strategy.
	private var nextAgitation: Long = 0L

	// a method used every consecutive beat to calculate the time for the next beat
	private def interval(): FiniteDuration = _heartbeat match {
		case Linear(ms) => ms.millis
		case Reactive(maxMs, _) => maxMs.millis
		case Agitated(minMs, _, _) if msgs.isEmpty && nextAgitation <= minMs => minMs.millis
		case Agitated(_, _, maxMs) if msgs.isEmpty && nextAgitation >= maxMs => maxMs.millis
		case Agitated(minMs, coeff, maxMs) if msgs.isEmpty =>
			nextAgitation = (nextAgitation * (1.0 * coeff)).toLong
			nextAgitation.millis
		case Agitated(minMs, _, _) =>
			nextAgitation = minMs
			nextAgitation.millis
	}

	override val in: SourceStream[Msg] = Stream[Msg]()
	in.map(msg => (msg, None)).pipeTo(msgStream)

	override val out: SourceStream[Rsp] = Stream[Rsp]()

	msgStream.foreach { msg =>
		msgs.enqueue(msg)
		_heartbeat match {
			case Reactive(_, maxMsgs) if msgs.size >= maxMsgs => Future { processMessages() }
			case _ =>
		}
	}

	systemStream.foreach { msg =>
		systemMsgs.enqueue(msg)
		_heartbeat match {
			case Reactive(_, _) => Future { processMessages() }
			case _ =>
		}
	}

	/**
		* Adds a new behavior to the actor. The behavior is appended to the list of existing behaviors,
		* meaning it will be executed only if all preceding behaviors fail to handle the message.
		*
		* @param id A unique identifier for the behavior being added.
		* @param pf The behavior function represented as a partial function that takes a message
		*           and an actor, and optionally returns a response.
		*/
	def addBehavior(id: String, pf: PF[Msg, Rsp, State]): Unit = {
		behaviors = behaviors.appended(id -> pf)
	}

	/**
		* Adds a new behavior to the actor. The behavior is appended to the list of existing behaviors,
		* meaning it will be executed only if all preceding behaviors fail to handle the message.
		*
		* @param behavior The behavior to be added, represented as a tuple containing a unique identifier
		*                 and a partial function that defines the behavior logic.
		*/
	def addBehavior(behavior: (id: String, pf: PF[Msg, Rsp, State])): Unit = {
		behaviors = behaviors.appended(behavior)
	}

	/**
		* Removes a behavior from the actor's list of behaviors based on its unique identifier.
		* The specified behavior will no longer be part of the message processing sequence.
		*
		* @param id The unique identifier of the behavior to be removed.
		*/
	def removeBehavior(id: String): Unit = {
		behaviors = behaviors.filterNot(_.id == id)
	}

	/**
		* Removes a specific behavior from the actor's list of behaviors, based on the reference its function,
		* given that it's the same reference that was used to add it.
		*
		* @param pf A reference to a partial function defining the behavior to be removed
		*/
	def removeBehavior(pf: PF[Msg, Rsp, State]): Unit = {
		behaviors = behaviors.filterNot(_.pf == pf)
	}

	override def getBehavior(id: String): Option[PF[Msg, Rsp, State]] =
		behaviors.collectFirst { case (name, pf) if name == id => pf }

	@targetName("ask") override def ?(msg: SystemMsg): CloseableFuture[Unit] = {
		val p = Promise[Unit]()
		systemStream ! (msg, Some(p))
		CloseableFuture.from(p)
	}

	@targetName("ask") override def ?(msg: Msg): CloseableFuture[Rsp] = {
		val p = Promise[Rsp]()
		msgStream ! (msg, Some(p))
		CloseableFuture.from(p)
	}

	@targetName("bang") override def !(msg: SystemMsg): Unit = { systemStream ! (msg, None) }

	@targetName("bang") override def !(msg: Msg): Unit = { msgStream ! (msg, None) }

	private val isProcessing = AtomicBoolean(false)

	// Processes awaiting messages and system messages
	// Should NOT be called directly - always only through `inStream` or wrapped in a future.
	private def processMessages(): Unit =
		if (isProcessing.getAndSet(true)) {
			processSystemMessages()
			processRegularMessages()
			isProcessing.set(false)
		}

	// Processes system messages; should NOT be called directly - always from `processMessages`
	private def processSystemMessages(): Unit = {
		import SystemMsg.*
		while (systemMsgs.nonEmpty) systemMsgs.dequeue() match {
			case (Pause, p)               => pause(); p.foreach(_.complete(Success(())))
			case (Unpause, p)             => unpause(); p.foreach(_.complete(Success(())))
			case (Close, p)               => if (p.isEmpty) close() else p.foreach(_.completeWith(shutdown()))
			case (AddBehavior(id, pf), p) => addBehavior(id, pf); p.foreach(_.complete(Success(())))
			case (RemoveBehavior(id), p)  => removeBehavior(id); p.foreach(_.complete(Success(())))
		}
	}

	// Processes regular messages; should NOT be called directly - always from `processMessages`
	private def processRegularMessages(): Unit =
		while (!isPaused && !isClosed && msgs.nonEmpty) msgs.dequeue() match {
			case (msg: Msg, Some(p)) =>
				process(msg) match {
					case Success(Some(rsp)) => p.complete(Try(rsp))
					case Success(None)      => p.complete(NoResponse[Rsp])
					case Failure(t)         => p.complete(Failure(t))
				}
			case (msg: Msg, _) => process(msg)
		}

	// Processes a single regular message; should NOT be called directly - always from `processMessages`
	private def process(msg: Msg): Try[Option[Rsp]] =
		behaviors.map(_.pf).find(_.isDefinedAt(msg, this)) match {
			case Some(f: PF[Msg, Rsp, State])        => Try(f(msg, this))
			case _ if _defBehavior == Actor.ignoreMsg => Success[Option[Rsp]](None)
			case _                                   => Try(_defBehavior(msg, this))
		}

	// Initializes the heartbeat of the actor.
	private[actors] def initialize(): Unit = beat.foreach(_ => processMessages())

	/**
		* Closes the actor and performs necessary checks to ensure all messages are completed before finalizing the closure.
		*
		* This method first invokes `beat.closeAndCheck()` to attempt closure at the heartbeat
		* level. If this is successful, it asynchronously processes any pending messages
		* using `processMessages` if messages are present. Once the processing is complete,
		* it delegates to `super.closeAndCheck()` to finalize the closure process.
		*
		* @return `true` if the actor and its heartbeat are successfully closed, `false` otherwise.
		*/
	override def closeAndCheck(): Boolean = {
		beat.close()
		if (msgs.isEmpty) super.closeAndCheck()
		else {
			shutdown().onComplete(_ => super.closeAndCheck())
			true
		}
	}

	private def shutdown(): Future[Unit] =
		for {
			_ =  beat.closeAndCheck()
			_ <- if (msgs.nonEmpty) Future { processMessages() } else Future.successful(())
			_ <- beat.isClosedSignal.onTrue
			_ =  super.closeAndCheck()
		} yield ()

	override def state: State = _state

	override def state_=(newState: State): Unit = { _state = newState }

	override def defBehavior: F[Msg, Rsp, State] = _defBehavior

	override def defBehavior_=(newDefBehavior: F[Msg, Rsp, State]): Unit = { _defBehavior = newDefBehavior }

	override def heartbeat: HeartBeatStrategy = _heartbeat

	override def heartbeat_=(newHeartbeat: HeartBeatStrategy): Unit = { _heartbeat = newHeartbeat }
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
	// todo: hange the behaviors list to a map - all behaviors that fit for a given message are executed, not only the oldest one
	// todo: change the name of defBehavior to endBehavior (the ladt behavior); the current one is confusing
	// todo: afterInit function that the actor can use, for example, to send out messages that it's alive
	// todo: similarly, there should be an `onClose` function (but that's already implemented)
	// todo: spawning sub-actors that are closed with the parent
	// todo: ActorBuilder
	// todo: HealthCheck system message, sent from the parent to the child; if the child doesn't respond in time, the message is repeated, and the the child is closed
	// todo: consider to allow the children to use different types of messages

	@static private val noResponse: Failure[Nothing] = Failure[Nothing](new IllegalStateException("No response"))

	/**
		* A special type of a failure indicating that although the message was received via the "?" (ask) operator and it was
		* processed, no response was given as the result.
		*
		* @return A `Failure` instance wrapping an `IllegalStateException`: "no response".
		*/
	inline def NoResponse[Rsp]: Failure[Rsp] = noResponse.asInstanceOf[Failure[Rsp]]

	// the default behavior of any actor is to simply ignore the message
	def ignoreMsg[Msg, Rsp, State](msg: Msg, actor: MutableActor[Msg, Rsp, State]): Option[Rsp] = None

	// The type of a default behavior: a function that takes a message and an actor and returns an optional response.
	type F[Msg, Rsp, State] = (Msg, MutableActor[Msg, Rsp, State]) => Option[Rsp]
	// The type of a custom behavior: a partial function that takes a message and an actor and returns an optional response.
	type PF[Msg, Rsp, State] = PartialFunction[(Msg, MutableActor[Msg, Rsp, State]), Option[Rsp]]

	/**
		* Represents a strategy for configuring the heartbeat of an actor.
		*
		* This enum defines various approaches to managing heartbeat intervals, suitable for
		* different scenarios based on the requirements of responsiveness.
		*/
	enum HeartBeatStrategy {
		case Linear(ms: Long)
		case Agitated(minMs: Long, coeff: Double, maxMs: Long)
		case Reactive(maxMs: Long, maxMsgs: Int)
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
		* Creates a new actor instance with the given initial state, default behavior, and heartbeat strategy.
		* The actor is initialized immediately after creation. It's going to use the `ExecutionContext` passed to it
		* as an implicit parameter.
		*
		* @param state       The initial state of the actor.
		* @param defBehavior The default behavior of the actor, responsible for handling incoming messages.
		* @param beat        The heartbeat strategy used to configure the actor's responsiveness.
		* @return An initialized actor instance.
		*/
	inline def apply[Msg, Rsp, State](state: State, defBehavior: F[Msg, Rsp, State], beat: HeartBeatStrategy)
	                                 (using ExecutionContext): Actor[Msg, Rsp, State] =
		new ActorImpl(state, defBehavior, beat).tap(_.initialize())

	/**
		* Creates a new actor instance with the specified initial state, default behavior, and heartbeat strategy.
		* The actor operates using a new serial dispatch queue to handle incoming messages.
		*
		* @param state       The initial state of the actor.
		* @param defBehavior The default behavior of the actor, responsible for handling incoming messages.
		* @param beat        The heartbeat strategy used to configure the actor's responsiveness.
		* @return An initialized actor instance.
		*/
	inline def serial[Msg, Rsp, State](state: State, defBehavior: F[Msg, Rsp, State], beat: HeartBeatStrategy): Actor[Msg, Rsp, State] =
		apply(state, defBehavior, beat)(using DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))

	/**
		* Creates a new actor instance with the given initial state and a default behavior, while the heartbeat strategy
		* is set to Linear(100ms).
		* The actor is initialized immediately after creation. It's going to use the `ExecutionContext` passed to it
		* as an implicit parameter.
		*
		* @param state       The initial state of the actor.
		* @param defBehavior The default behavior of the actor, responsible for handling incoming messages.
		* @return An initialized actor instance.
		*/
	inline def apply[Msg, Rsp, State](state: State, defBehavior: F[Msg, Rsp, State])(using ExecutionContext): Actor[Msg, Rsp, State] =
		apply(state, defBehavior, defBeat)

	/**
		* Creates a new actor instance with the specified initial state, and a default behavior, while the heartbeat strategy
		* * is sset to Linear(100ms).
		* The actor operates using a new serial dispatch queue to handle incoming messages.
		*
		* @param state       The initial state of the actor.
		* @param defBehavior The default behavior of the actor, responsible for handling incoming messages.
		* @return An initialized actor instance.
		*/
	inline def serial[Msg, Rsp, State](state: State, defBehavior: F[Msg, Rsp, State]): Actor[Msg, Rsp, State] =
		serial(state, defBehavior, defBeat)

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
	def apply[Msg, Rsp, State](state: State, pfs: List[PF[Msg, Rsp, State]], beat: HeartBeatStrategy)
	                          (using ExecutionContext): Actor[Msg, Rsp, State] =
		new ActorImpl[Msg, Rsp, State](state, ignoreMsg, beat).tap { actor =>
			pfs.foreach(actor.addBehavior)
			actor.initialize()
		}

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
}
