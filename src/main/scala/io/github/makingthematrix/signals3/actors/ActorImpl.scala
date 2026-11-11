package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.actors.Actor.*
import io.github.makingthematrix.signals3.actors.HeartBeatStrategy.*
import io.github.makingthematrix.signals3.generators.GeneratorStream
import io.github.makingthematrix.signals3.{Closeable, CloseableFuture, CloseableSourceStream, Initializable, Pausable, SerialDispatchQueue, SourceStream, Stream}
import io.github.makingthematrix.signals3.Behaviors.*

import java.util.UUID
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
	// a variable list of behaviors; a behavior is a partial function that tries to process an incoming message; see the processMessages method.
	protected var behaviors: List[PBeh[Msg, State]] = List[PBeh[Msg, State]]()
	private val behMap = mutable.HashMap[String, PF[Msg, State]]()
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
		* Adds a new behavior to the actor. If a behavior with the same id already exists, it will be replaced.
		* The behavior is appended to the list of existing behaviors,
		* meaning it will be executed only if all preceding behaviors fail to handle the message.
		*
		* @param behavior The behavior to be added, represented as a tuple containing a unique identifier
		*                 and a partial function that defines the behavior logic.
		*/
	private[actors] def addBehavior(behavior: PBeh[Msg, State]): Boolean =
		if (behMap.contains(behavior.id)) false
		else {
			behMap += behavior.id -> behavior.pf
			behaviors ::= behavior
			true
		}

	/**
		* Removes a behavior from the actor's list of behaviors based on its unique identifier.
		* The specified behavior will no longer be part of the message processing sequence.
		*
		* @param id The unique identifier of the behavior to be removed.
		*/
	private def removeBehavior(id: String): Unit = {
		behaviors = behaviors.filterNot(_.id == id)
		behMap -= id
	}

	private def getBehavior(id: String): Option[PBeh[Msg, State]] =
		behMap.collectFirst { case (behId, pf) if behId == id => behId -> pf }

	/**
		* Adds a behavior function to the actor and returns a unique identifier for it.
		* The behavior is prepended to the list of existing behaviors, which are evaluated
		* in order when processing a message. The newly added behavior can then take over
		* processing of a message from another behavior if their domains overlap.
		*
		* @param pf A partial function that represents the behavior logic.
		* @return A unique identifier for the newly added behavior.
		*/
	inline private[actors] def addBehaviorPF(pf: PF[Msg, State]): String =
		UUID.randomUUID().toString.tap { id => addBehavior(id -> pf) } // we assume uuids are unique

	// adds all new behavior functions in front of the list of behaviors but maintains their own internal order
	inline private[actors] def addBehaviorPFs(pfs: Iterable[PF[Msg, State]]): Unit =
		addBehaviors(pfs.map(pf => UUID.randomUUID().toString -> pf))

	private[actors] def addBehaviors(behs: Iterable[PBeh[Msg, State]]): Unit = {
		behaviors = behs.toList ::: behaviors
		behMap ++= behs.map(b => b.id -> b.pf)
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

	// The type arguments of `SystemMsg` cases like `AddBehavior[Msg, State]` are erased at runtime,
	// so instead of unchecked type patterns - which the compiler reports as warnings - these cases are matched
	// through trusted `TypeTest` instances created by `SystemMsg.trusted`. Each test verifies only the runtime
	// class of the message and takes the type arguments on trust - see `SystemMsg.trusted` for the details.
	private given AddBehaviorT: TypeTest[SystemMsg, AddBehavior[Msg, State]] = trusted[SystemMsg, AddBehavior[Msg, State]]
	private given AddBehaviorPFT: TypeTest[SystemMsg, AddBehaviorPF[Msg, State]] = trusted[SystemMsg, AddBehaviorPF[Msg, State]]
	private given SpawnT: TypeTest[SystemMsg, Spawn[Msg, State]] = trusted[SystemMsg, Spawn[Msg, State]]
	private given AddOnBeatT: TypeTest[SystemMsg, AddOnBeat[Msg, State]] = trusted[SystemMsg, AddOnBeat[Msg, State]]

	protected def processSysEntry(msg: SysEntry): Unit = msg match {
		case (Pause, p)              => pause(); respond(p, Done)
		case (Unpause, p)            => unpause(); respond(p, Done)
		case (Close, p)              => if (p.isEmpty) close() else respond(p, shutdown().map(_ => Done))
		case (AddBehaviorT(ab), p)   => addBehavior(ab.beh); respond(p, Done)
		case (RemoveBehavior(id), p) => removeBehavior(id); respond(p, Done)
		case (AddBehaviorPFT(ab), p) => addBehaviorPF(ab.pf); respond(p, Done)
		case (SpawnT(data), p)       => spawn(data, p)
		case (ActorClosed(id), p)    => removeChild(id, p)
		case (AddOnBeatT(sob), p)    => onBeat(sob.tbeh); respond(p, Done)
		case (ResetOnBeat, p)        => resetOnBeat(); respond(p, Done)
		case (other, p)              => respond(p, unhandledMsg(other.toString))
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
		val pfOpt =
			if (bId.nonEmpty) getBehavior(bId).map(_.pf)
			else behaviors.collectFirst { case (_, pf) if pf.isDefinedAt(msg, this) => pf }
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

	protected def spawn(data: SystemMsg.Spawn[Msg, State], p: Option[Promise[SystemMsg]] = None): Unit = {
		if (children.contains(data.actorId)) respond(p, invalidId(data.actorId)) else {
			val b1 = ActorBuilder[Msg, State](data.state.getOrElse(this.state))
				.withIdIf(data.actorId.nonEmpty, data.actorId)
				.withBehaviorsIf(data.behaviors.nonEmpty, data.behaviors, this.behaviors)
				.withHeartbeat(data.heartbeat.getOrElse(this.heartbeat))
				.withTags(tags + ActorTag.Child)
				.withParent(this)
				.withSystemIf(system.nonEmpty, system.get)
				.withOnInit(data.onInit)
				.withOnBeat(data.onBeat)
			val b2 = data.executionContext.fold(b1)(b1.withParallelDispatch)
			val b3 = if (data.useSerialDispatch) b2.withSerialDispatch() else b2
			val child = LocalActorRef(b3.build())
			children = children + (child.id -> child)
			respond(p, NewChild(child))
		}
	}

	// Processes regular messages; should NOT be called directly - always from `processMessages`
	// Note: A message may result in altering the list of behaviors, but the new behaviors will be used only in the next processing
	// This is actually consistent with sending a system message for altering the list of behaviors, as system messages are
	// processed before regular ones, so at the beginning of the next processing the lsit will be changed and that new list
	// will be used for that processing of regular messages.
	private def processRegularMessages(): Unit = {
		val entries = flushMsgEntries()
		while (!isPaused && !isClosed && entries.nonEmpty) processMsgEntry(entries.dequeue())
	}

	onInit {
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

	override def pause()(using ExecutionContext): Unit = if (!isClosed) super.pause()

	override def unpause()(using ExecutionContext): Unit = if (!isClosed) super.unpause()

	onPauseFailed { t => println(s"Failed to pause actor $id: ${t.getMessage}") }

	onCloseFailed { t =>  println(s"Failed to close actor $id: ${t.getMessage}") }

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

	protected def shutdown(): Future[SystemMsg] =
		if (!super.closeAndCheck()) Future.failed(CloseFailedException(id))
		else {
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

	override def state: State = _state

	override def state_=(newState: State): Unit = { _state = newState }
}

object ActorImpl {
	@static private val EmptyQueue = MQueue.empty[Nothing]
	inline def emptyQueue[T]: MQueue[T] = EmptyQueue.asInstanceOf[MQueue[T]]
}
