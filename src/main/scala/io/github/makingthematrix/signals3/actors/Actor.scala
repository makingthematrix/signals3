package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.{Closeable, CloseableFuture, DispatchQueue, Pausable, Stream}
import io.github.makingthematrix.signals3.actors.Actor.{Behavior, F, HeartBeatStrategy, Msg, NoResponse, PF}
import io.github.makingthematrix.signals3.generators.GeneratorStream

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.static
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.{Failure, Success, Try}
import scala.util.chaining.*
class Actor[Msg, Rsp, State](private var state: State,
                             private var defBehavior: F[Msg, Rsp, State] = Actor.ignoreMsg,
                             private val heartbeat: HeartBeatStrategy = HeartBeatStrategy.Linear(100L))
                            (using ec: ExecutionContext)
	extends Closeable with Pausable {
	import HeartBeatStrategy.*
	private var msgs      = List.empty[(Msg, Option[Promise[Rsp]])]
	private var behaviors = List.empty[Behavior[Msg, Rsp, State]]
	private lazy val beat = GeneratorStream.heartbeat(() => interval())

	private var currentAgitation: Long = 0L

	private def interval(): FiniteDuration = heartbeat match {
		case Linear(ms) => ms.millis
		case Reactive(maxMs, _) => maxMs.millis
		case Agitated(minMs, _, _) if msgs.isEmpty && currentAgitation <= minMs => minMs.millis
		case Agitated(_, _, maxMs) if msgs.isEmpty && currentAgitation >= maxMs => maxMs.millis
		case Agitated(minMs, coeff, maxMs) if msgs.isEmpty =>
			currentAgitation = (currentAgitation * (1.0 * coeff)).toLong
			currentAgitation.millis
		case Agitated(minMs, _, _) =>
			currentAgitation = minMs
			currentAgitation.millis
	}

	private val in = Stream[(Msg, Option[Promise[Rsp]])]()
	in.foreach { msg =>
		msgs ::= msg
		heartbeat match {
			case Reactive(_, maxMsgs) if msgs.size >= maxMsgs => Future { processMessages() }
			case _ =>
		}
	}

	inline def addBehavior(id: String, behavior: PF[Msg, Rsp, State]): Unit = {
		behaviors = behaviors.appended(id -> behavior)
	}

	inline def addBehavior(behavior: Behavior[Msg, Rsp, State]): Unit = {
		behaviors = behaviors.appended(behavior)
	}

	inline def addBehavior(pf: PF[Msg, Rsp, State]): String =
		UUID.randomUUID().toString.tap { name => addBehavior(name -> pf) }

	inline def +(pf: PF[Msg, Rsp, State]): String = addBehavior(pf)

	inline def removeBehavior(id: String): Unit = {
		behaviors = behaviors.filterNot(_.id == id)
	}

	inline def removeBehavior(pf: PF[Msg, Rsp, State]): Unit = {
		behaviors = behaviors.filterNot(_.behavior == pf)
	}

	inline def -(pf: PF[Msg, Rsp, State]): Unit = removeBehavior(pf)
	inline def -(id: String): Unit = removeBehavior(id)

	inline def getBehavior(id: String): Option[PF[Msg, Rsp, State]] =
		behaviors.collectFirst { case (name, pf) if name == id => pf }

	def ?(msg: Msg): CloseableFuture[Rsp] = {
		val p = Promise[Rsp]()
		in ! (msg, Some(p))
		CloseableFuture.from(p)
	}

	def !(msg: Actor.Msg): Unit = msg match {
		case Msg.Pause   => pause()
		case Msg.Unpause => unpause()
		case Msg.Close   => close()
	}

	inline def !(msg: Msg): Unit = {
		in ! (msg, None)
	}

	private def process(msg: Msg): Try[Option[Rsp]] =
		behaviors.map(_.behavior).find(_.isDefinedAt(msg, this)) match {
			case Some(f: PF[Msg, Rsp, State]) => Try(f(msg, this))
			case _ if defBehavior == Actor.ignoreMsg => Success[Option[Rsp]](None)
			case _ => Try(defBehavior(msg, this))
		}

	private val isProcessing = AtomicBoolean(false)

	private def processMessages(): Unit = if (!isPaused && !isClosed && msgs.nonEmpty && !isProcessing.getAndSet(true)) {
		msgs.foreach {
			case (msg, Some(p)) =>
				process(msg) match {
					case Success(Some(rsp)) => p.complete(Try(rsp))
					case Success(None)      => p.complete(NoResponse[Rsp])
					case Failure(t)         => p.complete(Failure(t))
				}
			case (msg, _) => process(msg)
		}
		msgs = Nil
		isProcessing.set(false)
	}

	private def initialize(): Unit = beat.foreach(_ => processMessages())

	override def closeAndCheck(): Boolean =
		if (beat.closeAndCheck()) {
			val f = if (msgs.nonEmpty) Future { processMessages() } else Future.successful(())
			f.onComplete(_ => super.closeAndCheck())
			true
		} else false
}

object Actor {
	// todo: Pausable, v
	// todo: pausing and closing through special messages, v
	// todo: private var state: State for keeping and modifying internal state, v
	// todo: behaviors must have access to this actor to be able to mutate the state v
	// todo: heartbeat should be a strategy: Linear(ms), Agitated(min, coeff, max), Reactive
	// todo: spawning sub-actors that are closed with the parent
	// todo: The onBeat function enabling the actor to generate messages, not only respond to others
	// todo: ActorBuilder

	@static private val noResponse: Failure[Nothing] = Failure[Nothing](new IllegalStateException("No response"))
	inline def NoResponse[Rsp]: Failure[Rsp] = noResponse.asInstanceOf[Failure[Rsp]]
	private def ignoreMsg[Msg, Rsp, State](msg: Msg, actor: Actor[Msg, Rsp, State]): Option[Rsp] = None

	type F[Msg, Rsp, State] = (Msg, Actor[Msg, Rsp, State]) => Option[Rsp]
	type PF[Msg, Rsp, State] = PartialFunction[(Msg, Actor[Msg, Rsp, State]), Option[Rsp]]
	type Behavior[Msg, Rsp, State] = (id: String, behavior: PF[Msg, Rsp, State])

	enum Msg {
		case Pause, Unpause, Close
	}

	enum HeartBeatStrategy {
		case Linear(ms: Long)
		case Agitated(minMs: Long, coeff: Double, maxMs: Long)
		case Reactive(maxMs: Long, maxMsgs: Int)
	}

	val defBeat: HeartBeatStrategy = HeartBeatStrategy.Linear(100L)

	inline def apply[Msg, Rsp, State](state: State, defBehavior: F[Msg, Rsp, State], beat: HeartBeatStrategy)
	                                 (using ExecutionContext): Actor[Msg, Rsp, State] =
		new Actor(state, defBehavior, beat).tap(_.initialize())

	inline def serial[Msg, Rsp, State](state: State, defBehavior: F[Msg, Rsp, State], beat: HeartBeatStrategy): Actor[Msg, Rsp, State] =
		apply(state, defBehavior, beat)(using DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))

	inline def apply[Msg, Rsp, State](state: State, defBehavior: F[Msg, Rsp, State])(using ExecutionContext): Actor[Msg, Rsp, State] =
		apply(state, defBehavior, defBeat)

	inline def serial[Msg, Rsp, State](state: State, defBehavior: F[Msg, Rsp, State]): Actor[Msg, Rsp, State] =
		serial(state, defBehavior, defBeat)

	def apply[Msg, Rsp, State](state: State, pfs: List[PF[Msg, Rsp, State]], beat: HeartBeatStrategy)
	                          (using ExecutionContext): Actor[Msg, Rsp, State] =
		new Actor[Msg, Rsp, State](state, ignoreMsg, beat).tap { actor =>
			pfs.foreach(actor.addBehavior)
			actor.initialize()
		}

	inline def serial[Msg, Rsp, State](state: State, pfs: List[PF[Msg, Rsp, State]], beat: HeartBeatStrategy): Actor[Msg, Rsp, State] =
		apply(state, pfs, beat)(using DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))

	inline def apply[Msg, Rsp, State](state: State, pfs: List[PF[Msg, Rsp, State]])(using ec: ExecutionContext): Actor[Msg, Rsp, State] =
		apply(state, pfs, defBeat)

	inline def serial[Msg, Rsp, State](state: State, pfs: List[PF[Msg, Rsp, State]]): Actor[Msg, Rsp, State] =
		serial(state, pfs, defBeat)
}
