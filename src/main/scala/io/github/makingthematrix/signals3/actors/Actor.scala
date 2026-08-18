package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.{Closeable, CloseableFuture, DispatchQueue, FlagSignal, Pausable, Stream}
import io.github.makingthematrix.signals3.actors.Actor.{Behavior, F, Msg, NoResponse, PF}
import io.github.makingthematrix.signals3.generators.GeneratorStream

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}
import scala.util.chaining.*

class Actor[Msg, Rsp](heartbeat: FiniteDuration, onMsg: F[Msg, Rsp] = Actor.ignoreMsg)
                     (using ec: ExecutionContext)
	extends Closeable with Pausable {
	private lazy val beat = GeneratorStream.heartbeat(heartbeat)
	private var msgs      = List.empty[(Msg, Option[Promise[Rsp]])]
	private var behaviors = List.empty[Behavior[Msg, Rsp]]

	private val in = Stream[(Msg, Option[Promise[Rsp]])]()
	in.foreach { msg =>
		msgs = msg:: msgs
	}

	inline def addBehavior(id: String, onMsg: PF[Msg, Rsp]): Unit = {
		behaviors = behaviors.appended(id -> onMsg)
	}

	inline def addBehavior(behavior: Behavior[Msg, Rsp]): Unit = {
		behaviors = behaviors.appended(behavior)
	}

	inline def addBehavior(pf: PF[Msg, Rsp]): String =
		UUID.randomUUID().toString.tap { name => addBehavior(name -> pf) }

	inline def +(pf: PF[Msg, Rsp]): String = addBehavior(pf)

	inline def removeBehavior(id: String): Unit = {
		behaviors = behaviors.filterNot(_.id == id)
	}

	inline def removeBehavior(pf: PF[Msg, Rsp]): Unit = {
		behaviors = behaviors.filterNot(_.onMsg == pf)
	}

	inline def -(pf: PF[Msg, Rsp]): Unit = removeBehavior(pf)

	inline def getBehavior(id: String): Option[PF[Msg, Rsp]] =
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
		behaviors.map(_.onMsg).find(_.isDefinedAt(msg)) match {
			case Some(f: PF[Msg, Rsp]) => Try(f(msg))
			case _ if onMsg == Actor.ignoreMsg => Success[Option[Rsp]](None)
			case _ => Try(onMsg(msg))
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
	// todo: private var state: State for keeping and modifying internal state
	// todo: behaviors must have access to this actor to be able to mutate the state
	// todo: heartbeat should be a strategy: Linear(ms), Agitated(min, coeff, max), Reactive
	// todo: spawning sub-actors that are closed with the parent
	// todo: The onBeat function enabling the actor to generate messages, not only respond to others
	// todo: ActorBuilder
	type F[Msg, Rsp] = Msg => Option[Rsp]
	type PF[Msg, Rsp] = PartialFunction[Msg, Option[Rsp]]
	type Behavior[Msg, Rsp] = (id: String, onMsg: PF[Msg, Rsp])

	enum Msg {
		case Pause, Unpause, Close
	}

	inline def NoResponse[Rsp]: Failure[Rsp] = noResponse.asInstanceOf[Failure[Rsp]]

	private def ignoreMsg[Msg, Rsp](msg: Msg): Option[Rsp] = None
	private val noResponse: Failure[Nothing] = Failure[Nothing](new IllegalStateException("No response"))

	val DefaultHeartbeat: FiniteDuration = 100.millis

	inline def apply[Msg, Rsp](hearbeat: FiniteDuration, onMsg: F[Msg, Rsp])(using ec: ExecutionContext): Actor[Msg, Rsp] =
		new Actor(hearbeat, onMsg).tap(_.initialize())

	inline def serial[Msg, Rsp](heartbeat: FiniteDuration, onMsg: F[Msg, Rsp]): Actor[Msg, Rsp] =
		apply(heartbeat, onMsg)(using DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))

	inline def apply[Msg, Rsp](onMsg: F[Msg, Rsp])(using ec: ExecutionContext): Actor[Msg, Rsp] =
		apply(DefaultHeartbeat, onMsg)

	inline def serial[Msg, Rsp](onMsg: F[Msg, Rsp]): Actor[Msg, Rsp] =
		serial(DefaultHeartbeat, onMsg)

	def apply[Msg, Rsp](heartbeat: FiniteDuration, pfs: List[PF[Msg, Rsp]])(using ec: ExecutionContext): Actor[Msg, Rsp] =
		new Actor[Msg, Rsp](heartbeat).tap { actor =>
			pfs.foreach(actor.addBehavior)
			actor.initialize()
		}

	inline def serial[Msg, Rsp](heartbeat: FiniteDuration, pfs: List[PF[Msg, Rsp]]): Actor[Msg, Rsp] =
		apply(heartbeat, pfs)(using DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))

	inline def apply[Msg, Rsp](pfs: List[PF[Msg, Rsp]])(using ec: ExecutionContext): Actor[Msg, Rsp] =
		apply(DefaultHeartbeat, pfs)

	inline def serial[Msg, Rsp](pfs: List[PF[Msg, Rsp]]): Actor[Msg, Rsp] =
		serial(DefaultHeartbeat, pfs)
}
