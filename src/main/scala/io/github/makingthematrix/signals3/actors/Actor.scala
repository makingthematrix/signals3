package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.actors.Actor.*
import io.github.makingthematrix.signals3.actors.Actor.HeartBeatStrategy.Linear
import io.github.makingthematrix.signals3.{CloseableFuture, DispatchQueue, Signal, SourceStream, Stream}

import scala.Conversion.into
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
	* @tparam State The type of the internal state
	*/
trait Actor[Msg, State] {
	/**
		* The actor's unique identifier.
		*/
	val id: String

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
	def out: Stream[Msg]

	/**
		* Retrieves a behavior from the actor's list of behaviors based on its unique identifier.
		*
		* @param id The unique identifier of the behavior to retrieve.
		* @return An `Option` containing the partial function defining the behavior, if found; otherwise, `None`.
		*/
	def getBehavior(id: String): Option[Beh[Msg, State]]

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
	def ask(msg: into[Msg], actorPath: ActorPath, behId: String): CloseableFuture[Msg]
	inline def ask(behId: String, msg: into[Msg]): CloseableFuture[Msg] = ask(msg, ActorPath.Direct, behId)
	inline def ask(t: (String, into[Msg])): CloseableFuture[Msg] = ask(t._1, t._2)
	inline def ask(t: (into[Msg], ActorPath, String)): CloseableFuture[Msg] = ask(t._1, t._2, t._3)
	inline def ?(t: (String, into[Msg])): CloseableFuture[Msg] = ask(t)
	inline def ?(t: (into[Msg], ActorPath, String)): CloseableFuture[Msg] = ask(t)
	inline def ask(msg: into[Msg]): CloseableFuture[Msg] = ask("", msg)
	inline def ?(msg: into[Msg]): CloseableFuture[Msg] = ask(msg)

	/**
		* Sends a system message to the actor.
		*
		* System messages are defined in [[ActorImpl.SystemMsg]]. They are processed asynchronously, just like regular messages
		* but they are not affected by the actor being paused (since  a system message might be used to unpause or close
		* a paused actor). No response will be returned to the sender.
		*
		* @param msg the message to send to the actor.
		*/
	def tell(msg: SystemMsg): Unit
	inline def !(msg: SystemMsg): Unit = tell(msg)

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
	def tell(msg: into[Msg], actorPath: ActorPath, behId: String): Unit
	inline def tell(behId: String, msg: into[Msg]): Unit = tell(msg, ActorPath.Direct, behId)
	inline def tell(t: (String, into[Msg])): Unit = tell(t._1, t._2)
	inline def tell(t: (into[Msg], ActorPath, String)): Unit = tell(t._1, t._2, t._3)
	inline def !(t: (String, into[Msg])): Unit = tell(t)
	inline def tell(msg: into[Msg]): Unit = tell("", msg)
	inline def !(msg: into[Msg]): Unit = tell(msg)
	inline def !(t: (into[Msg], ActorPath, String)): Unit = tell(t)

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
		* @return A signal that will be set to `true` when the actor is initialized.
		*/
	def isInitializedSignal(using ExecutionContext): Signal[Boolean]

	/**
		* Returns whether the actor is initialized or not.
		* Before initialization, the actor cannot receive messages.
		*/
	def isInitialized: Boolean

	/**
		* Returns whether the actor works on a serial dispatch queue.
		*/
	val isSerial: Boolean

	/**
		* Returns whether the actor is closed or not.
		* A closed actor cannot receive messages and it cannot be reopened.
		*/
	def isClosed: Boolean

	/**
		* Returns a signal that works on a given [[scala.concurrent.ExecutionContext]]; it starts with the value set to `false` (unless it's
		* created after the actor is already closed) and it will be set to `true` when the actor is closed.
		*
		* @return A signal that will be set to `true` when the actor is closed.
		*/
	def isClosedSignal(using ExecutionContext): Signal[Boolean]

	/**
		* Returns whether the actor is paused or not.
		* A paused actor still can receive messages, but it will not process them until it is unpaused.
		*/
	def isPaused: Boolean

	/**
		* Returns a signal that works on a given [[scala.concurrent.ExecutionContext]]; it starts with the value set to `false` (unless it's
		* created after the actor is already paused) and it will be set to `true` when the actor is paused.
		*
		* @return A signal that will be set to `true` when the actor is paused.
		*/
	def isPausedSignal: Signal[Boolean]

	/**
		* Returns the parent actor of this actor, if any.
		*/
	def parent: Option[Actor[Msg, State]]

	/**
		* Returns the actor system that this actor belongs to, if any.
		*/
	def system: Option[ActorSystem[Msg, State]]
}

object Actor {
	@static private val noResponse: Failure[Nothing] = Failure[Nothing](new IllegalStateException("No response"))
	@static private val ignored: Success[Option[Nothing]] = Success[Option[Nothing]](None)
	@static private[actors] val actorIsClosed = IllegalStateException("Actor is closed")

	opaque type OtherActor[Msg] = Actor[Msg, ?]

	object OtherActor {
		inline def apply[Msg](actor: Actor[Msg, ?]): OtherActor[Msg] = actor
		extension [Msg](oa: OtherActor[Msg]) inline def id: String = oa.id
		extension [Msg](oa: OtherActor[Msg]) inline def ask(msg: into[Msg], actorPath: ActorPath, behId: String): CloseableFuture[Msg] = oa.ask(msg, actorPath, behId)
		extension [Msg](oa: OtherActor[Msg]) inline def ask(behId: String, msg: into[Msg]): CloseableFuture[Msg] = ask(msg, ActorPath.Direct, behId)
		extension [Msg](oa: OtherActor[Msg]) inline def ask(t: (String, into[Msg])): CloseableFuture[Msg] = ask(t._1, t._2)
		extension [Msg](oa: OtherActor[Msg]) inline def ask(t: (into[Msg], ActorPath, String)): CloseableFuture[Msg] = ask(t._1, t._2, t._3)
		extension [Msg](oa: OtherActor[Msg]) inline def ?(t: (String, into[Msg])): CloseableFuture[Msg] = oa.ask(t)
		extension [Msg](oa: OtherActor[Msg]) inline def ?(t: (into[Msg], ActorPath, String)): CloseableFuture[Msg] = oa.ask(t)
		extension [Msg](oa: OtherActor[Msg]) inline def ask(msg: into[Msg]): CloseableFuture[Msg] = ask("", msg)
		extension [Msg](oa: OtherActor[Msg]) inline def ?(msg: into[Msg]): CloseableFuture[Msg] = oa.ask(msg)

		extension [Msg](oa: OtherActor[Msg]) inline def tell(msg: into[Msg], actorPath: ActorPath, behId: String): Unit = oa.tell(msg, actorPath, behId)
		extension [Msg](oa: OtherActor[Msg]) inline def tell(behId: String, msg: into[Msg]): Unit = tell(msg, ActorPath.Direct, behId)
		extension [Msg](oa: OtherActor[Msg]) inline def tell(t: (String, into[Msg])): Unit = tell(t._1, t._2)
		extension [Msg](oa: OtherActor[Msg]) inline def tell(t: (into[Msg], ActorPath, String)): Unit = tell(t._1, t._2, t._3)
		extension [Msg](oa: OtherActor[Msg]) inline def !(t: (String, into[Msg])): Unit = oa.tell(t)
		extension [Msg](oa: OtherActor[Msg]) inline def tell(msg: into[Msg]): Unit = tell("", msg)
		extension [Msg](oa: OtherActor[Msg]) inline def !(msg: into[Msg]): Unit = oa.tell(msg)
		extension [Msg](oa: OtherActor[Msg]) inline def !(t: (into[Msg], ActorPath, String)): Unit = oa.tell(t)
	}
	/**
		* A special type of a failure indicating that although the message was received via the "?" (ask) operator and it was
		* processed, no response was given as the result.
		*
		* @return A `Failure` instance wrapping an `IllegalStateException`: "no response".
		*/
	inline def FailToRespond[Msg]: Failure[Msg] = noResponse.asInstanceOf[Failure[Msg]]

	/**
		* A special type of response, indicating the incoming message was ignored. It's not necessarily an error.
		* @return A `Success` instance wrapping `None`
		*/
	inline def NoResponse[Msg]: Success[Option[Msg]] = ignored.asInstanceOf[Success[Option[Msg]]]

	/**
		* A special type of failure indicating that the actor is closed and cannot process any more messages.
		*/
	inline def ActorIsClosed[Msg](using ExecutionContext): CloseableFuture[Msg] = CloseableFuture.failed[Msg](actorIsClosed)

	/**
		* A special type of failure indicating that the actor ID is invalid
		*/
	final case class InvalidIdException(actorId: String) extends IllegalArgumentException(s"Invalid actor id: $actorId")

	/**
		* A special type of response, indicating that an invalid actor ID was provided.
		* @return a failed `CloseableFuture` with an `InvalidIdException`.
		*/
	inline def invalidActorId[Msg](actorId: String)(using ExecutionContext): CloseableFuture[Msg] =
		CloseableFuture.failed(InvalidIdException(actorId))

	/**
		* A special type of failure indicating that an unhandled message was received.
		*/
	final case class UnhandledMsgException(msg: String) extends IllegalArgumentException(s"Unhandled message: $msg")

	/**
		* A special type of response, indicating that the received messages was not handled (probably not recognized).
		* * @return a failed `CloseableFuture` with an `UnhandledMsgException`.
		*/
	inline def unhandledMsg[Msg](error: String)(using ExecutionContext): CloseableFuture[Msg] =
		CloseableFuture.failed(UnhandledMsgException(error))

	/**
		* A special type of failure indicating that an invalid path.
		*/
	final case class WrongPathException(path: ActorPath) extends IllegalArgumentException(s"Wrong path: $path")

	/**
		* A special type of response, indicating that the provided actor path is invalid.
		* * @return a failed `CloseableFuture` with an `WrongPathException`.
		*/
	inline def wrongPath[Msg](path: ActorPath)(using ExecutionContext): CloseableFuture[Msg] =
		CloseableFuture.failed(WrongPathException(path))

	// The type of a custom behavior: a partial function that takes a message and an actor and returns an optional response.
	type PF[Msg, State] = PartialFunction[(Msg, MutableActor[Msg, State]), Option[Msg]]
	
	// A shorthand for behavior tuples
	type Beh[Msg, State] = (id: String, pf: PF[Msg, State])

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
	def apply[Msg, State](state: State, behavior: Beh[Msg, State], beat: HeartBeatStrategy)
	                          (using ExecutionContext): Actor[Msg, State] =
		new ActorImpl(IdGenerator.generate(), state, beat).tap { actor =>
			actor.addBehavior(behavior)
			actor.initialize()
		}

	/**
		* Creates a actor with the specified initial state, behavior, and heartbeat strategy.
		* The new actor runs on a serial dispatch queue.
		*
		* Use only when you want a single independent actor.
		* For a bigger system, use [[ActorSystem]] and/or [[ActorBuilder]].
		*
		* @param state    The initial state of the actor.
		* @param behavior The behavior of the actor, responsible for handling incoming messages.
		* @param beat     The heartbeat strategy used to configure the actor's responsiveness.
		* @return An initialized serial actor instance.
		*/
	inline def serial[Msg, State](state: State, behavior: Beh[Msg, State], beat: HeartBeatStrategy): Actor[Msg, State] =
		apply(state, behavior, beat)(using DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))

	/**
		* Creates a actor with the specified behavior and heartbeat strategy.
		* The actor is initialized immediately after creation. It's going to use the `ExecutionContext` passed to it
		* as an implicit parameter.
		*
		* Use only when you want a single independent actor.
		* For a bigger system, use [[ActorSystem]] and/or [[ActorBuilder]].
		*
		* @param behavior The behavior of the actor, responsible for handling incoming messages.
		* @param beat     The heartbeat strategy used to configure the actor's responsiveness.
		* @return An initialized serial actor instance.
		*/
	inline def apply[Msg](behavior: Beh[Msg, Unit], beat: HeartBeatStrategy)
	                          (using ExecutionContext): Actor[Msg, Unit] =
		apply((), behavior, beat)

	/**
		* Creates a actor with the specified behavior and heartbeat strategy.
		* The new actor runs on a serial dispatch queue.
		*
		* Use only when you want a single independent actor.
		* For a bigger system, use [[ActorSystem]] and/or [[ActorBuilder]].
		*
		* @param behavior The behavior of the actor, responsible for handling incoming messages.
		* @param beat     The heartbeat strategy used to configure the actor's responsiveness.
		* @return An initialized serial actor instance.
		*/
	inline def serial[Msg](behavior: Beh[Msg, Unit], beat: HeartBeatStrategy): Actor[Msg, Unit] =
		serial((), behavior, beat)


	/**
		* Creates a actor with the specified behavior.
		* The actor is initialized immediately after creation. It's going to use the `ExecutionContext` passed to it
		* as an implicit parameter.
		*
		* Use only when you want a single independent actor.
		* For a bigger system, use [[ActorSystem]] and/or [[ActorBuilder]].
		*
		* @param behavior The behavior of the actor, responsible for handling incoming messages.
		* @return An initialized serial actor instance.
		*/
	inline def apply[Msg](behavior: Beh[Msg, Unit])(using ExecutionContext): Actor[Msg, Unit] =
		apply((), behavior, defBeat)


	/**
		* Creates a actor with the specified behavior.
		* The new actor runs on a serial dispatch queue.
		*
		* Use only when you want a single independent actor.
		* For a bigger system, use [[ActorSystem]] and/or [[ActorBuilder]].
		*
		* @param behavior The behavior of the actor, responsible for handling incoming messages.
		* @return An initialized serial actor instance.
		*/
	inline def serial[Msg](behavior: Beh[Msg, Unit]): Actor[Msg, Unit] = serial((), behavior, defBeat)
}
