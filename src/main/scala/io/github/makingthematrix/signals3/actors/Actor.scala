package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.{Closeable, CloseableFuture, DispatchQueue, Pausable, Stream}
import io.github.makingthematrix.signals3.actors.Actor.{Behavior, F, Msg, NoResponse, PF}
import io.github.makingthematrix.signals3.generators.GeneratorStream

import java.util.UUID
import scala.annotation.static
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}
import scala.util.chaining.*

class Actor[Msg, Rsp, State](heartbeat: FiniteDuration,
                             private var state: State,
                             defBehavior: F[Msg, Rsp, State] = Actor.ignoreMsg)
                            (using ec: ExecutionContext)
	extends Closeable with Pausable {
	private lazy val beat = GeneratorStream.heartbeat(heartbeat)
	private var msgs      = List.empty[(Msg, Option[Promise[Rsp]])]
	private var behaviors = List.empty[Behavior[Msg, Rsp, State]]

	private val in = Stream[(Msg, Option[Promise[Rsp]])]()
	in.foreach { msg =>
		msgs ::= msg
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

	private def processMessages(): Unit = if (!isPaused && !isClosed && msgs.nonEmpty) {
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
	val DefaultHeartbeat: FiniteDuration = 100.millis

	type F[Msg, Rsp, State] = (Msg, Actor[Msg, Rsp, State]) => Option[Rsp]
	type PF[Msg, Rsp, State] = PartialFunction[(Msg, Actor[Msg, Rsp, State]), Option[Rsp]]
	type Behavior[Msg, Rsp, State] = (id: String, behavior: PF[Msg, Rsp, State])

	enum Msg {
		case Pause, Unpause, Close
	}

	inline def apply[Msg, Rsp, State](hearbeat: FiniteDuration, state: State, defBehavior: F[Msg, Rsp, State])
	                                 (using ec: ExecutionContext): Actor[Msg, Rsp, State] =
		new Actor(hearbeat, state, defBehavior).tap(_.initialize())

	inline def serial[Msg, Rsp, State](heartbeat: FiniteDuration, state: State, defBehavior: F[Msg, Rsp, State]): Actor[Msg, Rsp, State] =
		apply(heartbeat, state, defBehavior)(using DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))

	inline def apply[Msg, Rsp, State](state: State, defBehavior: F[Msg, Rsp, State])(using ec: ExecutionContext): Actor[Msg, Rsp, State] =
		apply(DefaultHeartbeat, state, defBehavior)

	inline def serial[Msg, Rsp, State](state: State, defBehavior: F[Msg, Rsp, State]): Actor[Msg, Rsp, State] =
		serial(DefaultHeartbeat, state, defBehavior)

	def apply[Msg, Rsp, State](heartbeat: FiniteDuration, state: State, pfs: List[PF[Msg, Rsp, State]])
	                          (using ec: ExecutionContext): Actor[Msg, Rsp, State] =
		new Actor[Msg, Rsp, State](heartbeat, state, ignoreMsg).tap { actor =>
			pfs.foreach(actor.addBehavior)
			actor.initialize()
		}

	inline def serial[Msg, Rsp, State](heartbeat: FiniteDuration, state: State, pfs: List[PF[Msg, Rsp, State]]): Actor[Msg, Rsp, State] =
		apply(heartbeat, state, pfs)(using DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))

	inline def apply[Msg, Rsp, State](state: State, pfs: List[PF[Msg, Rsp, State]])(using ec: ExecutionContext): Actor[Msg, Rsp, State] =
		apply(DefaultHeartbeat, state, pfs)

	inline def serial[Msg, Rsp, State](state: State, pfs: List[PF[Msg, Rsp, State]]): Actor[Msg, Rsp, State] =
		serial(DefaultHeartbeat, state, pfs)
}
