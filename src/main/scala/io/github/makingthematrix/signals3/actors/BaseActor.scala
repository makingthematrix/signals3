package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.actors.Actor.*
import io.github.makingthematrix.signals3.actors.Actor.HeartBeatStrategy.{Agitated, Linear, Reactive}
import io.github.makingthematrix.signals3.generators.GeneratorStream
import io.github.makingthematrix.signals3.priv.DoneSignal
import io.github.makingthematrix.signals3.{Closeable, CloseableFuture, CloseableSourceStream, Pausable, SerialDispatchQueue, Signal, SourceStream, Stream}

import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.collection.mutable
import scala.collection.mutable.Queue as MQueue
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.chaining.*
import scala.util.{Failure, Success, Try}

/**
	* Represents an implementation of a mutable actor with a customizable state, behaviors, and heartbeat strategy.
	*
	* @tparam Msg   The type of messages processed by this actor.
	* @tparam Rsp   The type of responses returned by this actor.
	* @tparam State The type representing the internal state of the actor.
	*/
private[actors] class BaseActor[Msg, Rsp, State](override val id: String,
                                                 protected var _state: State,
                                                 override protected val heartbeat: HeartBeatStrategy = Actor.defBeat,
                                                 override val parent: Option[Actor[Msg, Rsp, State]] = None,
                                                 override val system: Option[ActorSystem[Msg, Rsp, State]] = None
                                                )(using ec: ExecutionContext)
	extends MutableActor[Msg, Rsp, State] with Closeable with Pausable{

	protected type MsgEntry = (msg: Msg, rsp: Option[Promise[Rsp]], behId: String)
	protected type SysEntry = (msg: SystemMsg, rsp: Option[Promise[SystemMsg]])

	protected var children: Map[String, Actor[Msg, Rsp, State]] = Map.empty

	// a mutable queue of messages incoming from other actors and other sources; see the ! operator.
	private val msgs = new AtomicReference[MQueue[MsgEntry]](MQueue.empty)
	// a stream that serves as a single entry for the msgs list to prevent concurrent modification; see the "!" operator.
	protected val msgStream: SourceStream[MsgEntry] = Stream[MsgEntry]()
	// a mutable queue of system messages incoming from the controller; see the ! operator.
	private val systemMsgs = new AtomicReference[MQueue[SysEntry]](MQueue.empty)
	// a stream that serves as a single entry for the systemMsgs list to prevent concurrent modification; see the "! operator.
	protected val systemStream: SourceStream[SysEntry] = Stream[SysEntry]()
	// a variable list of behaviors; a behavior is a partial function that tries to process an incoming message; see the processMessages method.
	protected var behaviors: List[Beh[Msg, Rsp, State]] = List[Beh[Msg, Rsp, State]]()
	private val behMap = mutable.HashMap[String, PF[Msg, Rsp, State]]()
	// the "beating heart" of the actor; depending on the strategy, accumulated messages are processed at each beat or when the message appears (reactive).
	private lazy val beat = GeneratorStream.heartbeat(() => interval())

	// the next agitation time of the actor in milliseconds); used to determine the interval between beats when using the Agitated heartbeat strategy.
	private var nextAgitation: Long = 0L

	override val isSerial: Boolean = ec.isInstanceOf[SerialDispatchQueue]

	inline private def enqueue(entry: MsgEntry): Unit = msgs.updateAndGet(_ :+ entry)

	inline private def enqueue(entry: SysEntry): Unit = systemMsgs.updateAndGet(_ :+ entry)

	inline private def flushMsgEntries(): MQueue[MsgEntry] = msgs.getAndSet(MQueue.empty)

	inline private def flushSystemEntries(): MQueue[SysEntry] = systemMsgs.getAndSet(MQueue.empty)

	// a method used every consecutive beat to calculate the time for the next beat
	private def interval(): FiniteDuration = heartbeat match {
		case Linear(ms, _) => ms.millis
		case Reactive(maxMs, _, _) => maxMs.millis
		case Agitated(minMs, _, _, _) if msgs.get().isEmpty && nextAgitation <= minMs => minMs.millis
		case Agitated(_, _, maxMs, _) if msgs.get().isEmpty && nextAgitation >= maxMs => maxMs.millis
		case Agitated(minMs, coeff, maxMs, _) if msgs.get().isEmpty =>
			nextAgitation = (nextAgitation * (1.0 * coeff)).toLong
			nextAgitation.millis
		case Agitated(minMs, _, _, _) =>
			nextAgitation = minMs
			nextAgitation.millis
	}

	override val in: CloseableSourceStream[Msg] = CloseableSourceStream[Msg]()
	in.map(msg => (msg, None, "")).pipeTo(msgStream)

	override val out: CloseableSourceStream[Rsp] = CloseableSourceStream[Rsp]()

	msgStream.foreach { msg =>
		enqueue(msg)
		heartbeat match {
			case Reactive(_, maxMsgs, _) if msgs.get().size >= maxMsgs => processMessages()
			case _ =>
		}
	}

	systemStream.foreach { msg =>
		enqueue(msg)
		heartbeat match {
			case Reactive(_, _, _) => processMessages()
			case _ =>
		}
	}

	/**
		* Adds a new behavior to the actor. If a behavior with the same id already exists, it will be replaced.
		* The behavior is appended to the list of existing behaviors,
		* meaning it will be executed only if all preceding behaviors fail to handle the message.
		*
		* @param behavior The behavior to be added, represented as a tuple containing a unique identifier
		*                 and a partial function that defines the behavior logic.
		*/
	private[actors] def addBehavior(behavior: Beh[Msg, Rsp, State]): Boolean =
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

	override def getBehavior(id: String): Option[Beh[Msg, Rsp, State]] = 
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
	inline private[actors] def addBehaviorPF(pf: PF[Msg, Rsp, State]): String =
		UUID.randomUUID().toString.tap { id => addBehavior(id -> pf) } // we assume uuids are unique

	// adds all new behavior functions in front of the list of behaviors but maintains their own internal order
	inline private[actors] def addBehaviorPFs(pfs: Iterable[PF[Msg, Rsp, State]]): Unit = 
		addBehaviors(pfs.map(pf => UUID.randomUUID().toString -> pf))
	
	private[actors] def addBehaviors(behs: Iterable[Beh[Msg, Rsp, State]]): Unit = {
		behaviors = behs.toList ::: behaviors
		behMap ++= behs.map(b => b.id -> b.pf)
	}

	override def ask(msg: SystemMsg): CloseableFuture[SystemMsg] =
		if (!isClosed) {
			val p = Promise[SystemMsg]()
			systemStream ! (msg, Some(p))
			CloseableFuture.from(p)
		} else ActorIsClosed[SystemMsg]

	override def ask(msg: Msg, path: ActorPath, behId: String): CloseableFuture[Rsp] = if (!isClosed) {
		import ActorPath.*
		inline def sendToStream() = CloseableFuture.from(Promise[Rsp]().tap { p => msgStream ! (msg, Some(p), behId) })
		path match {
			case Direct                                          => sendToStream()
			case Local(`id`)                                     => sendToStream()
			case Remote("", `id`)                                => sendToStream()
			case Remote(sId, `id`) if system.exists(_.id == sId) => sendToStream()
			case _ if system.nonEmpty                            => system.get.ask(msg, path, behId)
			case _                                               => Actor.wrongPath(path)
		}
	} else ActorIsClosed[Rsp]

	override def bang(msg: SystemMsg): Unit = if (!isClosed) {systemStream ! (msg, None)}

	override def bang(msg: Msg, path: ActorPath, behId: String): Unit = if (!isClosed) {
		import ActorPath.*
		path match {
			case Direct                                          => msgStream ! (msg, None, behId)
			case Local(`id`)                                     => msgStream ! (msg, None, behId)
			case Remote("", `id`)                                => msgStream ! (msg, None, behId)
			case Remote(sId, `id`) if system.exists(_.id == sId) => msgStream ! (msg, None, behId)
			case _ if system.nonEmpty                            => system.get.bang(msg, path, behId)
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

	inline protected def respond(pOpt: Option[Promise[SystemMsg]], rsp: SystemMsg): Unit =
		pOpt.foreach(p => Try(p.tryComplete(Success(rsp))))

	// Processes system messages; should NOT be called directly - always from `processMessages`
	private def processSystemMessages(): Unit = {
		val entries = flushSystemEntries()
		while (entries.nonEmpty) processSysEntry(entries.dequeue())
	}

	import SystemMsg.*

	protected def processSysEntry(msg: SysEntry): Unit = msg match {
		case (Pause, p)               => pause(); respond(p, Done)
		case (Unpause, p)             => unpause(); respond(p, Done)
		case (Close, p)               => if (p.isEmpty) close() else p.foreach(_.completeWith(shutdown().map(_ => Done)))
		case (AddBehavior(beh), p)    => addBehavior(beh); respond(p, Done)
		case (RemoveBehavior(id), p)  => removeBehavior(id); respond(p, Done)
		case (AddBehaviorPF(pf), p)   => addBehaviorPF(pf); respond(p, Done)
		case (data: Spawn, p)         => val rsp = spawn(data); respond(p, rsp)
		case (ActorClosed(id), p)     => removeChild(id); respond(p, Done)
		case _ => // @todo: log the unhandled messages
	}

	private def removeChild(id: String): Unit = {
		children = children - id
	}

	protected def spawn(data: SystemMsg.Spawn): SystemMsg =
		if (children.contains(data.actorId)) SystemMsg.InvalidId else {
			val b1 = ActorBuilder[Msg, Rsp, State](data.state.getOrElse(this.state))
				.withIdIf(data.actorId.nonEmpty, data.actorId)
				.withBehaviorsIf(data.behaviors.nonEmpty, data.behaviors, this.behaviors)
				.withHeartbeat(data.heartbeat.getOrElse(this.heartbeat))
				.withParent(this)
				.withSystemIf(system.nonEmpty, system.get)
				.withOnInitIf(data.onInit.nonEmpty, data.onInit.get)
			val b2 = data.executionContext.fold(b1)(b1.withParallelDispatch)
			val b3 = if (data.useSerialDispatch) b2.withSerialDispatch() else b2
			val child = b3.build()
			children = children + (child.id -> child)
			SystemMsg.NewChild(child)
		}

	// Processes regular messages; should NOT be called directly - always from `processMessages`
	// Note: A message may result in altering the list of behaviors, but the new behaviors will be used only in the next processing
	// This is actually consistent with sending a system message for altering the list of behaviors, as system messages are
	// processed before regular ones, so at the beginning of the next processing the lsit will be changed and that new list
	// will be used for that processing of regular messages.
	private def processRegularMessages(): Unit = {
		val entries = flushMsgEntries()
		while (!isPaused && !isClosed && entries.nonEmpty) {
			val (msg, pOpt, bId) = entries.dequeue()
			val pfOpt =
				if (bId.nonEmpty) getBehavior(bId).map(_.pf)
				else behaviors.collectFirst { case (_, pf) if pf.isDefinedAt(msg, this) => pf }
			val res = pfOpt match {
				case Some(pf) if isSerial => Try(pf(msg, this))
				case Some(pf)             => Try(Await.result(Future {pf(msg, this)}, heartbeat.timeout))
				case _                    => Ignored[Rsp]
			}
			pOpt.foreach(p => try {
				res match {
					case Success(Some(rsp)) => p.tryComplete(Try(rsp))
					case Success(None)      => p.tryComplete(NoResponse[Rsp])
					case Failure(t)         => p.tryComplete(Failure(t))
				}
			} catch {
				case _: IllegalStateException => // Promise already completed
			})
		}
	}

	private val initialized: AtomicBoolean = new AtomicBoolean(false)

	override def isInitialized: Boolean = initialized.get()

	// Calls the onInit functions and nitializes the heartbeat of the actor
	protected[actors] def initialize(): Unit =
		if (!isInitialized) try {
			_onInit.foreach(_(this))
			_onInit = Nil
			beat.foreach(_ => processMessages())
			system.foreach(s => s ! s.SystemMsg.Register(this))
			initialized.set(true)
		} finally {
			if (!isInitialized) closeAndCheck()
		}

	private var _onInit: List[MutableActor[Msg, Rsp, State] => Unit] = Nil

	// Registers a function that should be called exactly once when the actor is initialized
	private[actors] def onInit(f: MutableActor[Msg, Rsp, State] => Unit): Unit =
		_onInit ::= f

	override def isInitializedSignal(using ExecutionContext): Signal[Boolean] =
		DoneSignal().tap { signal =>
			if (isInitialized) signal.done() else onInit(_ => signal.done())
		}

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
	override def closeAndCheck(): Boolean = Try(Await.ready(shutdown(), heartbeat.timeout + 5.seconds)).isSuccess

	protected def shutdown(): Future[SystemMsg] = {
		super.closeAndCheck()
		children.values.foreach(child => child ! child.SystemMsg.Close)
		in.close()
		out.close()
		beat.closeAndCheck()
		flushMsgEntries().collect { case (_, Some(p), _) => p }.foreach(_.tryFailure(actorIsClosed))
		flushSystemEntries().collect { case (_, Some(p)) => p }.foreach(_.tryFailure(actorIsClosed))
		parent.foreach(p => p ! p.SystemMsg.ActorClosed(id))
		system.foreach(s => s ! s.SystemMsg.ActorClosed(id))
		beat.isClosedSignal.onTrue.map(_ => SystemMsg.ActorClosed(id))
	}

	override def state: State = _state

	override def state_=(newState: State): Unit = {
		_state = newState
	}
}
