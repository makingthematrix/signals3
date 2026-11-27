package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.actors.Actor.*
import io.github.makingthematrix.signals3.actors.HeartBeatStrategy.*
import io.github.makingthematrix.signals3.generators.GeneratorStream
import io.github.makingthematrix.signals3.{Behaviors, Closeable, CloseableFuture, CloseableSourceStream, Initializable, Pausable, SerialDispatchQueue, SourceStream, Stream}
import io.github.makingthematrix.signals3.Behaviors.*
import io.github.makingthematrix.signals3.actors.SystemMsg.Done

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.Conversion.into
import scala.annotation.static
import scala.collection.mutable
import scala.collection.mutable.Queue as MQueue
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.reflect.TypeTest
import scala.util.chaining.*
import scala.util.{Failure, Success, Try}

/**
	* Represents an implementation of a mutable actor with a customizable state, behaviors, and heartbeat strategy.
	*
	* @tparam Msg   The type of messages processed by this actor.
	* @tparam State The type representing the internal state of the actor.
	*/
private[actors] class ActorImpl[Msg, State](override val id: String,
                                            protected var _state: State,
                                            protected val heartbeat: HeartBeatStrategy = Actor.defBeat,
                                            override val tags: Set[ActorTag] = Set.empty,
                                            override val parent: Option[ActorRef[Msg]] = None,
                                            override val system: Option[ActorSystem[Msg, ?]] = None
                                           )(using ec: ExecutionContext)
	extends MutableActor[Msg, State] with Closeable with Pausable with Initializable {
	import ActorImpl.*

	protected type MsgEntry = (msg: Msg, rsp: Option[Promise[Msg]], behId: String)
	protected type SysEntry = (msg: SystemMsg, rsp: Option[Promise[SystemMsg]])

	protected var children: Map[String, ActorRef[Msg]] = Map.empty

	// a mutable queue of messages incoming from other actors and other sources; see the ! operator.
	private val msgs = new AtomicReference[MQueue[MsgEntry]](emptyQueue)
	// a stream that serves as a single entry for the msgs list to prevent concurrent modification; see the "!" operator.
	protected val msgStream: SourceStream[MsgEntry] = Stream[MsgEntry]()
	// a mutable queue of system messages incoming from the controller; see the ! operator.
	private val systemMsgs = new AtomicReference[MQueue[SysEntry]](emptyQueue)
	// a stream that serves as a single entry for the systemMsgs list to prevent concurrent modification; see the "! operator.
	protected val systemStream: SourceStream[SysEntry] = Stream[SysEntry]()
	// the id of the actor's behavior - a partial function which tries to process an
	// incoming message; the functions live in `Behaviors` and are retrieved by the id at the moment
	// a message is processed; see the processMessages method.
	private val behaviorStack = mutable.Stack[String]("")
	protected def currentBehavior: String = behaviorStack.head

	// the "beating heart" of the actor; depending on the strategy, accumulated messages are processed at each beat or when the message appears (reactive).
	private lazy val beat = GeneratorStream.heartbeat(() => interval())
	
	override val isSerial: Boolean = ec.isInstanceOf[SerialDispatchQueue]

	inline private def flushMsgEntries(): MQueue[MsgEntry] =
		if (msgs.get() != EmptyQueue) msgs.getAndSet(emptyQueue) else emptyQueue[MsgEntry]
	inline private def flushSystemEntries(): MQueue[SysEntry] =
		if (systemMsgs.get() != EmptyQueue) systemMsgs.getAndSet(emptyQueue) else emptyQueue[SysEntry]

	// a method used every consecutive beat to calculate the time for the next beat
	private def interval(): FiniteDuration = {
		inline def hasMsg: Boolean = msgs.get().nonEmpty || systemMsgs.get().nonEmpty
		heartbeat match {
			case Linear(time, _)                => time
			case Reactive(time, _, _) if hasMsg => time
			case Reactive(_, time, _)           => time
			case CustomLinear(interval, _)      => interval()
			case CustomReactive(interval, _)    => interval(hasMsg)
		}
	}

	override val in: CloseableSourceStream[Msg] = CloseableSourceStream[Msg]()
	in.map(msg => (msg, None, "")).pipeTo(msgStream)

	override val out: CloseableSourceStream[Msg] = CloseableSourceStream[Msg]()

	private def updateReactiveBeat(): Unit = {
		inline def update(time: FiniteDuration): Unit =
			if (time.length == 0L) beat.force() else CloseableFuture.delayed(time) { beat.force() }
		heartbeat match {
			case Reactive(time, _, _)        => update(time)
			case CustomReactive(interval, _) => update(interval(true))
			case _ =>
		}
	}

	msgStream.foreach { msg => msgs.updateAndGet(_ :+ msg); updateReactiveBeat() }
	systemStream.foreach { msg => systemMsgs.updateAndGet(_ :+ msg); updateReactiveBeat() }

	/**
		* Sets the partial behavior which handles the incoming messages without a behavior id.
		* The behavior - a partial function registered in [[Behaviors]] - is not stored here:
		* the actor keeps only the id, and the function is retrieved from `Behaviors` at the moment
		* a message is given to it. Setting a behavior pushes its id onto the actor's behavior stack;
		* the top of the stack is the current behavior. The previous behaviors stay on the stack -
		* they can be returned to with [[unwindBehavior]] or invoked by their id through a message
		* which carries one. Setting the current behavior again is a no-op.
		*
		* @param id The id of a behavior registered in [[Behaviors]].
		*/
	private[actors] def setBehavior(id: String, p: Option[Promise[SystemMsg]] = None): Unit =
		if (id == currentBehavior) respond(p, Done)
		else Behaviors.getPBeh[Msg, State](id) match {
			case Some(_) =>
				behaviorStack.push(id)
				respond(p, Done)
			case _ => respond(p, invalidId(id))
		}

	/**
		* Unwinds the actor's behavior stack: the current behavior is popped and the previously set
		* one becomes current again. The bottom of the stack is the empty-id sentinel - an actor with
		* no behavior - which cannot be unwound: unwinding it is a silent no-op.
		*/
	private def unwindBehavior(p: Option[Promise[SystemMsg]]): Unit = {
		if (currentBehavior.nonEmpty) behaviorStack.pop()
		respond(p, Done)
	}

	override def ask(msg: SystemMsg): CloseableFuture[SystemMsg] =
		if (!isClosed) {
			val p = Promise[SystemMsg]()
			systemStream ! (msg, Some(p))
			CloseableFuture.from(p)
		} else ActorIsClosed[SystemMsg]

	override def ask(msg: into[Msg], path: ActorPath, behId: String): CloseableFuture[Msg] = if (!isClosed) {
		import ActorPath.*
		inline def sendToStream() = CloseableFuture.from(Promise[Msg]().tap { p => msgStream ! (msg, Some(p), behId) })
		path match {
			case Direct                                          => sendToStream()
			case Local(`id`)                                     => sendToStream()
			case Remote("", `id`)                                => sendToStream()
			case Remote(sId, `id`) if system.exists(_.id == sId) => sendToStream()
			case _ if system.nonEmpty                            => system.get.ask(msg, path, behId)
			case _                                               => Actor.wrongPath(path)
		}
	} else ActorIsClosed[Msg]

	override def tell(msg: SystemMsg): Unit = if (!isClosed) { systemStream ! (msg, None) }

	override def tell(msg: into[Msg], path: ActorPath, behId: String): Unit = if (!isClosed) {
		import ActorPath.*
		path match {
			case Direct                                          => msgStream ! (msg, None, behId)
			case Local(`id`)                                     => msgStream ! (msg, None, behId)
			case Remote("", `id`)                                => msgStream ! (msg, None, behId)
			case Remote(sId, `id`) if system.exists(_.id == sId) => msgStream ! (msg, None, behId)
			case _ if system.nonEmpty                            => system.get.tell(msg, path, behId)
			case _ => // wrong path
		}
	}

	private val isProcessing = AtomicBoolean(false)

	// Processes awaiting messages and system messages
	// Should NOT be called directly - always only through `inStream` or wrapped in a future.
	private def processMessages(): Unit = {
		def process(): Unit = {
			processSystemMessages()
			if (!isClosed && !isPaused) processRegularMessages()
		}

		if (!isProcessing.getAndSet(true)) {
			if (isSerial) { process(); isProcessing.set(false) }
			else Future(process()).onComplete(_ => isProcessing.set(false))
		}
	}

	inline final protected def respond(pOpt: Option[Promise[SystemMsg]], rsp: SystemMsg): Unit =
		pOpt.foreach(p => Try(p.tryComplete(Success(rsp))))

	inline final protected def respond(pOpt: Option[Promise[SystemMsg]], rsp: Future[SystemMsg]): Unit =
		pOpt.foreach(_.completeWith(rsp))

	inline final protected def respond(pOpt: Option[Promise[SystemMsg]], rsp: CloseableFuture[SystemMsg]): Unit =
		respond(pOpt, rsp.future)

	// Processes system messages; should NOT be called directly - always from `processMessages`
	private def processSystemMessages(): Unit = {
		val entries = flushSystemEntries()
		while (entries.nonEmpty) processSysEntry(entries.dequeue())
	}

	import SystemMsg.*

	// The type arguments of `SystemMsg` cases like `Spawn[State]` are erased at runtime,
	// so instead of unchecked type patterns - which the compiler reports as warnings - these cases are matched
	// through trusted `TypeTest` instances created by `SystemMsg.trusted`. Each test verifies only the runtime
	// class of the message and takes the type arguments on trust - see `SystemMsg.trusted` for the details.
	private given SpawnT: TypeTest[SystemMsg, Spawn[State]] = trusted[SystemMsg, Spawn[State]]

	protected def processSysEntry(msg: SysEntry): Unit = msg match {
		case (Pause, p)              => pause(); respond(p, Done)
		case (Unpause, p)            => unpause(); respond(p, Done)
		case (Close, p)              => if (p.isEmpty) close() else respond(p, shutdown().map(_ => Done))
		case (SetBehavior(pbehId), p) => setBehavior(pbehId, p)
		case (UnwindBehavior, p)     => unwindBehavior(p)
		case (SpawnT(data), p)       => spawn(data, p)
		case (ActorClosed(id), p)    => removeChild(id, p)
		case (AddOnBeat(tbehId), p)  => addOnBeat(tbehId, p)
		case (AddOnInit(behId), p)       => addOnInit(behId, p)
		case (AddOnPause(behId), p)     => addOnPause(behId, p)
		case (AddOnUnpause(behId), p)   => addOnUnpause(behId, p)
		case (AddOnClose(behId), p)     => addOnClose(behId, p)
		case (RemoveOnBeat(tbehId), p) => removeOnBeat(tbehId); respond(p, Done)
		case (RemoveOnInit(behId), p)   => removeOnInit(behId); respond(p, Done)
		case (RemoveOnPause(behId), p)  => removeOnPause(behId); respond(p, Done)
		case (RemoveOnUnpause(behId), p) => removeOnUnpause(behId); respond(p, Done)
		case (RemoveOnClose(behId), p)  => removeOnClose(behId); respond(p, Done)
		case (ResetOnBeat, p)        => resetOnBeat(); respond(p, Done)
		case (ResetOnInit, p)           => resetOnInit(); respond(p, Done)
		case (ResetOnPause, p)          => resetOnPause(); respond(p, Done)
		case (ResetOnUnpause, p)        => resetOnUnpause(); respond(p, Done)
		case (ResetOnClose, p)          => resetOnClose(); respond(p, Done)
		case (other, p)              => respond(p, unhandledMsg(other.toString))
	}

	private def addOnBeat(tbehId: String, p: Option[Promise[SystemMsg]]): Unit = {
		if (isClosed) respond(p, ActorIsClosed[SystemMsg])
		else if (onBeat(tbehId)) respond(p, Done)
		else respond(p, invalidId(tbehId))
	}

	private def addOnInit(behId: String, p: Option[Promise[SystemMsg]]): Unit = {
		if (isInitialized) respond(p, ActorIsInit[SystemMsg])
		else if (onInit(behId)) respond(p, Done)
		else respond(p, invalidId(behId))
	}

	private def addOnPause(behId: String, p: Option[Promise[SystemMsg]]): Unit = {
		if (isClosed) respond(p, ActorIsClosed[SystemMsg])
		else if (onPause(behId)) respond(p, Done)
		else respond(p, invalidId(behId))
	}

	private def addOnUnpause(behId: String, p: Option[Promise[SystemMsg]]): Unit = {
		if (isClosed) respond(p, ActorIsClosed[SystemMsg])
		else if (onUnpause(behId)) respond(p, Done)
		else respond(p, invalidId(behId))
	}

	private def addOnClose(behId: String, p: Option[Promise[SystemMsg]]): Unit = {
		if (isClosed) respond(p, ActorIsClosed[SystemMsg])
		else if (onClose(behId)) respond(p, Done)
		else respond(p, invalidId(behId))
	}

	private def processMsgEntry(entry: MsgEntry): Unit = {
		val (msg, pOpt, bId) = entry
		val res = if (bId.nonEmpty) onMessage(msg, bId) else Try(onMessage(msg)).flatten
		pOpt.foreach(p => try {
			res match {
				case Success(Some(rsp)) => p.tryComplete(Try(rsp))
				case Success(None)      => p.tryComplete(FailToRespond[Msg])
				case Failure(t)         => p.tryComplete(Failure(t))
			}
		} catch {
			case _: IllegalStateException => // Promise already completed
		})
	}

	private def onMessage(msg: Msg, bId: String): Try[Option[Msg]] = {
		// The functions are retrieved from Behaviors by the stored ids at the moment of the processing;
		// an id whose function is no longer registered is a silent no-op (the message ends up unhandled).
		// The unnamed dispatch goes to the current behavior - the top of the behavior stack. A message
		// with a behavior id is routed to the behavior with that id directly, even if it is not the current one.
		val pfOpt: Option[PF[Msg, State]] =
			Behaviors.getPBeh[Msg, State](if (bId.isEmpty) currentBehavior else bId).map(_.pf)
		pfOpt match {
			case Some(pf) if isSerial => Try(pf(msg, this))
			case Some(pf)             => Try(Await.result(Future {pf(msg, this)}, heartbeat.timeout))
			case _                    => NoResponse[Msg]
		}
	}

	protected def onMessage(msg: Msg): Try[Option[Msg]] = onMessage(msg, "")

	protected def removeChild(id: String, p: Option[Promise[SystemMsg]] = None): Unit =
		if (children.contains(id)) {
			children = children - id
			respond(p, Done)
		} else respond(p, invalidId(id))

	protected def spawn(data: SystemMsg.Spawn[State], p: Option[Promise[SystemMsg]] = None): Unit = {
		if (children.contains(data.actorId)) respond(p, invalidId(data.actorId)) else {
			val b1 = ActorBuilder[Msg, State](data.state.getOrElse(this.state))
				.withIdIf(data.actorId.nonEmpty, data.actorId)
				.setBehaviorIf(data.behavior.nonEmpty, data.behavior, this.currentBehavior)
				.withHeartbeat(data.heartbeat.getOrElse(this.heartbeat))
				.withTags(tags + ActorTag.Child)
				.withParent(this)
				.withSystemIf(system.nonEmpty, system.get)
				.addOnInits(data.onInit)
				.addOnBeats(data.onBeat)
				.addOnPauses(data.onPause)
				.addOnUnpauses(data.onUnpause)
				.addOnCloses(data.onClose)
			val b2 = data.executionContext.fold(b1)(b1.withParallelDispatch)
			val b3 = if (data.useSerialDispatch) b2.withSerialDispatch() else b2
			val child = LocalActorRef(b3.build())
			children = children + (child.id -> child)
			respond(p, NewChild(child))
		}
	}

	// Processes regular messages; should NOT be called directly - always from `processMessages`
	// Note: A message may result in altering the behavior stack, but the new current behavior will be used only in the next processing.
	// This is actually consistent with sending a system message for altering the behavior stack, as system messages are
	// processed before regular ones, so at the beginning of the next processing the stack will be changed and its new top
	// will be used for that processing of regular messages.
	private def processRegularMessages(): Unit = {
		val entries = flushMsgEntries()
		while (!isPaused && !isClosed && entries.nonEmpty) processMsgEntry(entries.dequeue())
	}

	onInit { () => 
		beat.foreach { _ =>
			processMessages()
			if (!isPaused) runOnBeat()
		}
		system.foreach(_.tell(Register(this, tags)))
	}

	onInitFailed { t =>
		println(s"Failed to initialize actor $id: ${t.getMessage}")
		close()
	}

	onPauseFailed { t => println(s"Failed to pause actor $id: ${t.getMessage}") }
	onCloseFailed { t =>  println(s"Failed to close actor $id: ${t.getMessage}") }

	override def initialize(): Boolean = if (!initialized.getAndSet(true)) {
		if (runOnInit(this)) true else {
			initialized.set(false)
			false
		}
	} else false

	override def pause(): Boolean =
		if (!isClosed && !paused.getAndSet(true)) {
			if (runOnPause(this)) true
			else {
				paused.set(false)
				false
			}
		}
		else false

	override def unpause(): Boolean =
		if (!isClosed && paused.getAndSet(false)) {
			if (runOnUnpause(this)) true
			else {
				paused.set(true)
				false
			}
		}
		else false


	/**
		* Closes the actor and performs necessary checks to ensure all messages are completed before finalizing the closure.
		*
		* The actor's own `onClose` behaviors run first (through `closeAndCheck` of `CanBeClosed`). If any of them throws,
		* the close is aborted: `closeAndCheck` returns `false`, the actor stays open, closed is rolled back, and the
		* failure is reported to the `onCloseFailed` behaviors. The close can then be retried.
		*
		* If the user behaviors succeed, this method closes the heartbeat, processes any pending messages,
		* closes the `in`/`out` streams and the children, notifies the parent and the system, and returns
		* a future which succeeds when the heartbeat is closed.
		*
		* @return `true` if the actor and its heartbeat are successfully closed, `false` otherwise.
		*/
	override def closeAndCheck(): Boolean = Try(Await.ready(shutdown(), heartbeat.timeout + 5.seconds)).isSuccess

	protected def shutdown(): Future[SystemMsg] = {
		if (closed.getAndSet(true)) Future.failed(CloseFailedException(id))
		else if (!runOnClose(this)) {
			closed.set(false)
			Future.failed(CloseFailedException(id))
		} else {
			runOnClose(this)
			children.values.foreach(child => child ! SystemMsg.Close)
			in.close()
			out.close()
			beat.closeAndCheck()
			flushMsgEntries().collect { case (_, Some(p), _) => p }.foreach(_.tryFailure(actorIsClosed))
			flushSystemEntries().collect { case (_, Some(p)) => p }.foreach(_.tryFailure(actorIsClosed))
			parent.foreach(_ ! ActorClosed(id))
			system.foreach(_ ! ActorClosed(id))
			beat.isClosedSignal.onTrue.map(_ => ActorClosed(id))
		}
	}

	override def state: State = _state

	override def state_=(newState: State): Unit = { _state = newState }
}

object ActorImpl {
	@static private val EmptyQueue = MQueue.empty[Nothing]
	inline def emptyQueue[T]: MQueue[T] = EmptyQueue.asInstanceOf[MQueue[T]]
}
