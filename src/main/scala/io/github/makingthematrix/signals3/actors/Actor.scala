package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture
import io.github.makingthematrix.signals3.actors.Actor.{F, NoResponse, PF}
import io.github.makingthematrix.signals3.generators.GeneratorStream

import java.util.UUID
import scala.concurrent.{ExecutionContext, Promise}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}
import scala.util.chaining.*
import io.github.makingthematrix.signals3.Stream

class Actor[Msg, Rsp](heartbeat: FiniteDuration, onMsg: F[Msg, Rsp] = Actor.ignoreMsg)
                     (using ec: ExecutionContext) {
	private lazy val beat = GeneratorStream.heartbeat(heartbeat)
	private var msgs = List.empty[(Msg, Option[Promise[Rsp]])]
	private var processes = List.empty[(id: String, onMsg: PF[Msg, Rsp])]

	private val in = Stream[(Msg, Option[Promise[Rsp]])]()
	in.foreach { msg =>
		msgs = msg:: msgs
	}

	def addProcess(id: String, onMsg: PF[Msg, Rsp]): Unit = {
		processes = processes.appended(id -> onMsg)
	}

	def addProcess(t: (String, PF[Msg, Rsp])): Unit = {
		processes = processes.appended(t)
	}

	def addProcess(pf: PF[Msg, Rsp]): String =
		UUID.randomUUID().toString.tap { name => addProcess(name -> pf) }

	inline def +(pf: PF[Msg, Rsp]): String = addProcess(pf)

	def removeProcess(id: String): Unit = {
		processes = processes.filterNot(_.id == id)
	}

	def removeProcess(pf: PF[Msg, Rsp]): Unit = {
		processes = processes.filterNot(_.onMsg == pf)
	}

	inline def -(pf: PF[Msg, Rsp]): Unit = removeProcess(pf)

	def getProcess(id: String): Option[PF[Msg, Rsp]] = processes.collectFirst { case (name, pf) if name == id => pf }

	def ?(msg: Msg): CloseableFuture[Rsp] = {
		val p = Promise[Rsp]()
		in ! (msg, Some(p))
		CloseableFuture.from(p)
	}

	def !(msg: Msg): Unit = {
		in ! (msg, None)
	}

	private def process(msg: Msg): Try[Option[Rsp]] =
		processes.map(_.onMsg).find(_.isDefinedAt(msg)) match {
			case Some(f: PF[Msg, Rsp])         => Try(f(msg))
			case _ if onMsg == Actor.ignoreMsg => Success[Option[Rsp]](None)
			case _                             => Try(onMsg(msg))
		}

	private def init(): Unit = beat.foreach { _ =>
		msgs.foreach {
			case (msg, Some(p)) =>
				process(msg) match {
					case Success(Some(rsp)) => p.complete(Try(rsp))
					case Success(None)      => p.complete(NoResponse[Rsp])
					case Failure(t)         => p.complete(Failure(t))
				}
			case (msg, _) => process(msg)
		}
	}
}

object Actor {
	type F[Msg, Rsp] = Msg => Option[Rsp]
	type PF[Msg, Rsp] = PartialFunction[Msg, Option[Rsp]]
	def ignoreMsg[Msg, Rsp](msg: Msg): Option[Rsp] = None
	private val noResponse: Failure[Nothing] = Failure[Nothing](new IllegalStateException("No response"))
	def NoResponse[Rsp]: Failure[Rsp] = noResponse.asInstanceOf[Failure[Rsp]]

	val DefaultHeartbeat: FiniteDuration = 100.millis

	def apply[Msg, Rsp](hearbeat: FiniteDuration, onMsg: F[Msg, Rsp])(using ec: ExecutionContext): Actor[Msg, Rsp] =
		new Actor(hearbeat, onMsg).tap(_.init())

	def from[Msg](heatbeat: FiniteDuration, onMsg: F[Msg, Msg])(using ec:ExecutionContext): Actor[Msg, Msg] =
		apply[Msg, Msg](heatbeat, onMsg)

	def apply[Msg, Rsp](onMsg: F[Msg, Rsp])(using ec: ExecutionContext): Actor[Msg, Rsp] =
		apply(DefaultHeartbeat, onMsg)

	def from[Msg](onMsg: F[Msg, Msg])(using ec: ExecutionContext): Actor[Msg, Msg] =
		apply(DefaultHeartbeat, onMsg)

	def apply[Msg, Rsp](heartbeat: FiniteDuration, pfs: List[PF[Msg, Rsp]])(using ec: ExecutionContext): Actor[Msg, Rsp] =
		new Actor[Msg, Rsp](heartbeat).tap { actor =>
			pfs.foreach(actor.addProcess)
			actor.init()
		}

	def from[Msg](heartbeat: FiniteDuration, pfs: List[PF[Msg, Msg]])(using ec: ExecutionContext): Actor[Msg, Msg] =
		apply[Msg, Msg](heartbeat, pfs)

	def apply[Msg, Rsp](pfs: List[PF[Msg, Rsp]])(using ec: ExecutionContext): Actor[Msg, Rsp] =
		apply(DefaultHeartbeat, pfs)

	def from[Msg](pfs: List[PF[Msg, Msg]])(using ec: ExecutionContext): Actor[Msg, Msg] =
			apply[Msg, Msg](DefaultHeartbeat, pfs)
}
