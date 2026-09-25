package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture
import io.github.makingthematrix.signals3.actors.Actor.HeartBeatStrategy
import io.github.makingthematrix.signals3.actors.RemoteSystem.RemoteSystemMsg
import io.github.makingthematrix.signals3.actors.RemoteSystem.RemoteSystemMsg.SystemClosed

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Try, Success, Failure}
import scala.util.chaining.scalaUtilChainingOps

final class ActorSystem[Msg, Rsp, State] private(
  override val id: String,
  state: State,
  override protected val heartbeat: HeartBeatStrategy
)(using ExecutionContext) extends BaseActor[Msg, Rsp, State](id, state, heartbeat) with RemoteSystem[Msg, Rsp] {
	import SystemMsg.*
	import ActorPath.*

	@volatile private var actorRefs: Map[String, ActorRef[Msg, Rsp]] = Map.empty
	@volatile private var systems: Map[String, RemoteSystem[Msg, Rsp]] = Map.empty

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
		case (RegisterSystem(system), p) =>
			systems += (system.id -> system)
			respond(p, Done)
		case (UnregisterSystem(systemId), p) =>
			systems -= systemId
			respond(p, Done)
		case (AskForRef(actorId, systemId), p) if systemId == "" || systemId == id =>
			respond(p, actorRefs.get(actorId).map(Ref(_)).getOrElse(InvalidId))
		case (AskForRefAsync(sender, actorId, systemId), p) if systemId == "" || systemId == id =>
			val rsp = actorRefs.get(actorId)
				.map(sender.SystemMsg.Ref(_))
				.getOrElse(sender.SystemMsg.InvalidId)
			sender ! rsp
			respond(p, Done)
		case (AskForRef(actorId, systemId), p) =>
			val rspCf = systems.get(systemId)
				.map { _ ? RemoteSystemMsg.AskForRef(actorId) }
				.map { _.collect {
					case RemoteSystemMsg.Ref(ref) => Ref(ref.asInstanceOf[ActorRef[Msg, Rsp]])
				}}.getOrElse(ActorSystem.invalidSystemId(systemId))
			p.foreach(_.completeWith(rspCf.future))
		case (AskForRefAsync(sender, actorId, systemId), p) =>
			systems.get(systemId)
				.map { _ ? RemoteSystemMsg.AskForRef(actorId) }
				.map { _.collect {
					case RemoteSystemMsg.Ref(ref) => sender.SystemMsg.Ref(ref.asInstanceOf[ActorRef[Msg, Rsp]])
				}}
				.getOrElse(ActorSystem.invalidSystemId(systemId))
				.onComplete {
					case Success(rsp) => sender ! rsp; respond(p, Done)
					case Failure(t)   => p.foreach(_.failure(t))
				}
		case _ =>
			super.processSysEntry(msg)
	}

	override protected def spawn(data: SystemMsg.Spawn): SystemMsg =
		if (children.contains(data.actorId)) SystemMsg.InvalidId else {
			val b1 = ActorBuilder[Msg, Rsp, State](data.state.getOrElse(this.state))
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

	override def bang(msg: Msg, path: ActorPath, behId: String): Unit = path match {
		case Direct                                               => msgStream          ! (msg, None, behId)
		case Local(`id`)                                          => msgStream          ! (msg, None, behId)
		case Remote("", `id`)                                     => msgStream          ! (msg, None, behId)
		case Remote(`id`, `id`)                                   => msgStream          ! (msg, None, behId)
		case Local(actorId)        if actorRefs.contains(actorId) => actorRefs(actorId) ! (msg, behId)
		case Remote("", actorId)   if actorRefs.contains(actorId) => actorRefs(actorId) ! (msg, behId)
		case Remote(`id`, actorId) if actorRefs.contains(actorId) => actorRefs(actorId) ! (msg, behId)
		case Remote(systemId, _)   if systemId != id && systems.contains(systemId)  => systems(systemId)  ! (msg, path, behId)
		case _ => // invalid system or actor id
	}

	override def ask(msg: Msg, path: ActorPath, behId: String): CloseableFuture[Rsp] = {
		inline def sendToStream() = CloseableFuture.from(Promise[Rsp]().tap { p => msgStream ! (msg, Some(p), behId) })
		path match {
			case Direct                                               => sendToStream()
			case Local(`id`)                                          => sendToStream()
			case Remote("", `id`)                                     => sendToStream()
			case Remote(`id`, `id`)                                   => sendToStream()
			case Local(actorId)        if actorRefs.contains(actorId) => actorRefs(actorId) ? (msg, behId)
			case Remote("", actorId)   if actorRefs.contains(actorId) => actorRefs(actorId) ? (msg, behId)
			case Remote(`id`, actorId) if actorRefs.contains(actorId) => actorRefs(actorId) ? (msg, behId)
			case Remote(systemId, _)   if systemId != id && systems.contains(systemId)  => systems(systemId) ? (msg, path, behId)
			case Local(actorId)                                       => Actor.invalidActorId(actorId)
			case Remote(systemId, _)                                  => ActorSystem.invalidSystemId(systemId)
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

	// @todo This is a clunky way to convert one type of messages into another; implement a more generic one
	override def ask(msg: RemoteSystem.RemoteSystemMsg): CloseableFuture[RemoteSystemMsg] = msg match {
		case SystemClosed(systemId) =>
			(this ? UnregisterSystem(systemId)).collect { case Done => RemoteSystemMsg.Done }
		case RemoteSystemMsg.AskForRef(actorId) =>
			(this ? AskForRef(actorId)).flatMap {
				case Ref(ref) => CloseableFuture.successful(RemoteSystemMsg.Ref(RemoteActorRef(ActorPath.Remote(id, actorId), this)))
				case _        => Actor.invalidActorId(actorId)
			}
		case _ => Actor.unhandledMsg(msg)
	}

	override def bang(msg: RemoteSystem.RemoteSystemMsg): Unit = msg match {
		case SystemClosed(systemId) => this ! UnregisterSystem(systemId)
		case _ =>
	}
}

object ActorSystem {
	inline def invalidSystemId[Rsp](systemId: String)(using ExecutionContext): CloseableFuture[Rsp] =
		CloseableFuture.failed(new IllegalArgumentException(s"Invalid system id: $systemId"))

	def apply[Msg, Rsp, State](id: String, state: State, heartbeat: HeartBeatStrategy)(using ExecutionContext): ActorSystem[Msg, Rsp, State] = {
		assert(id != "")
		new ActorSystem(id, state, heartbeat).tap { _.initialize() }
	}

	inline def apply[Msg, Rsp, State](state: State, heartbeat: HeartBeatStrategy)(using ExecutionContext): ActorSystem[Msg, Rsp, State] =
		apply(IdGenerator.generate("system"), state, heartbeat)
}