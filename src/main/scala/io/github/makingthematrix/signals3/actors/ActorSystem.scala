package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture
import io.github.makingthematrix.signals3.actors.Actor.{HeartBeatStrategy, invalidActorId, unhandledMsg}
import io.github.makingthematrix.signals3.actors.ActorSystem.invalidSystemId
import io.github.makingthematrix.signals3.actors.RemoteSystem.RemoteSystemMsg
import io.github.makingthematrix.signals3.actors.RemoteSystem.RemoteSystemMsg.SystemClosed

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}
import scala.util.chaining.scalaUtilChainingOps

final class ActorSystem[Msg, State] private(
  override val id: String,
  state: State,
  override protected val heartbeat: HeartBeatStrategy
)(using ExecutionContext) extends ActorImpl[Msg, State](id, state, heartbeat) with RemoteSystem[Msg] {
	import SystemMsg.*
	import ActorPath.*

	@volatile private var actorRefs: Map[String, ActorRef[Msg]] = Map.empty
	@volatile private var systems: Map[String, RemoteSystem[Msg]] = Map.empty

	override protected def processSysEntry(msg: SysEntry): Unit = msg match {
		case (Register(actor), p) =>
			val ref = LocalActorRef(actor)
			actorRefs += (actor.id -> ref)
			respond(p, Ref(ref))
		case (Unregister(actorId), p) =>
			actorRefs -= actorId
			respond(p, Done)
		case (ActorClosed(actorId), _) =>
			actorRefs -= actorId
			super.processSysEntry(msg)
		case (AskForRef(actorId, systemId), p) if systemId == "" || systemId == id =>
			actorRefs.get(actorId) match {
				case Some(ref) => respond(p, Ref(ref))
				case None => respond(p, invalidActorId(actorId).future)
			}
		case (AskForRefAsync(sender, actorId, systemId), p) if systemId == "" || systemId == id =>
			actorRefs.get(actorId) match {
				case Some(ref) =>
					sender ! sender.SystemMsg.Ref(ref)
					respond(p, Done)
				case None =>
					// Don't send InvalidId to sender - just fail the promise consistently
					respond(p, invalidActorId(actorId).future)
			}
		case (Requeue(actorId, msg, behId, tryNumber), p) =>
			actorRefs.get(actorId) match {
				case Some(ref)             => ref ! (msg, behId)
				case None if tryNumber < 2 => this ! Requeue(actorId, msg, behId, tryNumber + 1) // requeue
				case None                  => respond(p, invalidActorId(actorId).future)
			}
		case (AskForRef(actorId, systemId), p) =>
			systems.get(systemId) match {
				case None => respond(p, invalidSystemId(systemId).future)
				case Some(system) =>
					respond(p, (system ? RemoteSystemMsg.AskForRef(actorId)).flatMap {
						case RemoteSystemMsg.Ref(ref) => CloseableFuture.successful(Ref(ref.asInstanceOf[ActorRef[Msg]]))
						case _ => invalidActorId(actorId) // Handle unexpected responses
					}.future)
			}
		case (AskForRefAsync(sender, actorId, systemId), p) =>
			systems.get(systemId) match {
				case None =>
					respond(p, invalidSystemId(systemId).future)
				case Some(system) =>
					(system ? RemoteSystemMsg.AskForRef(actorId)).onComplete {
						case Success(RemoteSystemMsg.Ref(ref)) =>
							sender ! sender.SystemMsg.Ref(ref.asInstanceOf[ActorRef[Msg]])
							respond(p, Done)
						case Success(msg) =>
							respond(p, unhandledMsg[SystemMsg](msg.toString).future) // the only successful response should be RemoteSystemMsg.Ref
						case Failure(_: Actor.InvalidIdException) =>
							respond(p, invalidActorId(actorId).future)
						case Failure(t) =>
							p.foreach(_.failure(t))
					}
			}
		case _ =>
			super.processSysEntry(msg)
	}

	override protected def spawn(data: Spawn): SystemMsg =
		if (children.contains(data.actorId)) SystemMsg.InvalidId else {
			val b1 = ActorBuilder[Msg, State](data.state.getOrElse(this.state))
				.withIdIf(data.actorId.nonEmpty, data.actorId)
				.withBehaviorsIf(data.behaviors.nonEmpty, data.behaviors, this.behaviors)
				.withHeartbeat(data.heartbeat.getOrElse(this.heartbeat))
				.withParent(this)
				.withSystem(this)
				.withOnInitIf(data.onInit.nonEmpty, data.onInit.get)
			val b2 = data.executionContext.fold(b1)(b1.withParallelDispatch)
			val b3 = if (data.useSerialDispatch) b2.withSerialDispatch() else b2
			val child = b3.build()
			children = children + (child.id -> child)
			SystemMsg.NewChild(child)
		}

	override def tell(msg: Msg, path: ActorPath, behId: String): Unit = path match {
		case Direct                => msgStream ! (msg, None, behId)
		case Local(`id`)           => msgStream ! (msg, None, behId)
		case Remote("", `id`)      => msgStream ! (msg, None, behId)
		case Remote(`id`, `id`)    => msgStream ! (msg, None, behId)
		case Local(actorId)        => if (actorRefs.contains(actorId)) actorRefs(actorId) ! (msg, behId) else this ! Requeue(actorId, msg, behId)
		case Remote("", actorId)   => if (actorRefs.contains(actorId)) actorRefs(actorId) ! (msg, behId) else this ! Requeue(actorId, msg, behId)
		case Remote(`id`, actorId) => if (actorRefs.contains(actorId)) actorRefs(actorId) ! (msg, behId) else this ! Requeue(actorId, msg, behId)
		case Remote(systemId, _) if systemId != id && systems.contains(systemId)  => systems(systemId) ! (msg, path, behId)
		case _ => // invalid system or actor id
	}

	override def ask(msg: Msg, path: ActorPath, behId: String): CloseableFuture[Msg] = {
		inline def sendToStream() = CloseableFuture.from(Promise[Msg]().tap { p => msgStream ! (msg, Some(p), behId) })
		path match {
			case Direct                => sendToStream()
			case Local(`id`)           => sendToStream()
			case Remote("", `id`)      => sendToStream()
			case Remote(`id`, `id`)    => sendToStream()
			case Local(actorId)        => if (actorRefs.contains(actorId)) actorRefs(actorId) ? (msg, behId) else sendToStream()
			case Remote("", actorId)   => if (actorRefs.contains(actorId)) actorRefs(actorId) ? (msg, behId) else sendToStream()
			case Remote(`id`, actorId) => if (actorRefs.contains(actorId)) actorRefs(actorId) ? (msg, behId) else sendToStream()
			case Remote(systemId, _)   => if (systemId != id && systems.contains(systemId)) systems(systemId) ? (msg, path, behId) else invalidSystemId(systemId)
		}
	}

	override protected[actors] def initialize(): Unit = if (!isInitialized) {
		systems += (id -> this)
		actorRefs += (id -> LocalActorRef(this)) // register yourself as a valid actor
		super.initialize()
	}

	override protected def shutdown(): Future[SystemMsg] = {
		systems.collect { case (systemId, sys) if systemId != id => sys ! RemoteSystemMsg.SystemClosed(id) }
		super.shutdown()
	}

	override def ask(msg: RemoteSystem.RemoteSystemMsg): CloseableFuture[RemoteSystemMsg] = {
		inline def unregister(systemId: String) = { systems -= systemId; CloseableFuture.successful(RemoteSystemMsg.Done) }
		msg match {
			case RemoteSystemMsg.SystemClosed(systemId) => unregister(systemId)
			case RemoteSystemMsg.UnregisterSystem(systemId) => unregister(systemId)
			case RemoteSystemMsg.RegisterSystem(system) =>
				systems += (system.id -> system.asInstanceOf[RemoteSystem[Msg]])
				CloseableFuture.successful(RemoteSystemMsg.Done)
			case RemoteSystemMsg.AskForRef(actorId) =>
				(this ? AskForRef(actorId)).flatMap {
					case Ref(ref) => CloseableFuture.successful(RemoteSystemMsg.Ref(RemoteActorRef(ActorPath.Remote(id, actorId), this)))
					case _        => CloseableFuture.failed(Actor.InvalidIdException(actorId))
				}
			case _ => CloseableFuture.failed(Actor.UnhandledMsgException(msg.toString))
		}
	}

	override def tell(msg: RemoteSystem.RemoteSystemMsg): Unit = msg match {
		case RemoteSystemMsg.SystemClosed(systemId)     => systems -= systemId
		case RemoteSystemMsg.UnregisterSystem(systemId) => systems -= systemId
		case RemoteSystemMsg.RegisterSystem(system)     => systems += (system.id -> system.asInstanceOf[RemoteSystem[Msg]])
		case _ =>
	}
}

object ActorSystem {
	final case class InvalidSystemIdException(systemId: String) extends IllegalArgumentException(s"Invalid system id: $systemId")

	inline def invalidSystemId[Msg](systemId: String)(using ExecutionContext): CloseableFuture[Msg] =
		CloseableFuture.failed(InvalidSystemIdException(systemId))

	def apply[Msg, State](id: String, state: State, heartbeat: HeartBeatStrategy)(using ExecutionContext): ActorSystem[Msg, State] = {
		assert(id != "")
		new ActorSystem(id, state, heartbeat).tap { _.initialize() }
	}

	inline def apply[Msg, State](state: State, heartbeat: HeartBeatStrategy)(using ExecutionContext): ActorSystem[Msg, State] =
		apply(IdGenerator.generate("system"), state, heartbeat)
}