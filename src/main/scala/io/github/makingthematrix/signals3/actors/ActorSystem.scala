package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture
import io.github.makingthematrix.signals3.actors.Actor.HeartBeatStrategy

import scala.concurrent.ExecutionContext
import scala.util.chaining.scalaUtilChainingOps

final class ActorSystem[Msg, Rsp, State](override val id: String,
                                         state: State,
                                         override protected val heartbeat: HeartBeatStrategy)
                                        (using ExecutionContext)
	extends ActorImpl[Msg, Rsp, State](id, state, heartbeat) with RemoteSystem[Msg, Rsp] {
	import SystemMsg.*
	import ActorPath.*
	
	private var actorRefs: Map[String, ActorRef[Msg, Rsp]] = Map.empty
	private var systems: Map[String, RemoteSystem[Msg, Rsp]] = Map.empty

	override protected def processSysEntry(msg: SysEntry): Unit = msg match {
		case (Register(actor), p) =>
			val ref = LocalActorRef(actor)
			actorRefs += (actor.id -> ref)
			respond(p, Ref(ref))
		case (Unregister(id), p) =>
			actorRefs -= id
			respond(p, Done)
		case (ActorClosed(id), _) =>
			actorRefs -= id
			super.processSysEntry(msg)
		case (AskForRef(id), p) =>
			respond(p, actorRefs.get(id).map(Ref(_)).getOrElse(InvalidId))
		case (AskForRefAsync(sender, id), p) =>
			val rsp = actorRefs.get(id).map(sender.SystemMsg.Ref(_)).getOrElse(sender.SystemMsg.InvalidId)
			sender ! rsp
			respond(p, Done)
		case (RegisterSystem(system), p) =>
			systems += (system.id -> system)
			respond(p, Done)
		case (UnregisterSystem(id), p) =>
			systems -= id
			respond(p, Done)
		case _ =>
			super.processSysEntry(msg)
	}

	override protected def spawn(data: SystemMsg.Spawn): SystemMsg =
		if (children.contains(data.id)) SystemMsg.InvalidId else {
			val b1 = ActorBuilder[Msg, Rsp, State](data.state.getOrElse(this.state))
				.withIdIf(data.id.nonEmpty, data.id)
				.withBehaviorsIf(data.behaviors.nonEmpty, data.behaviors, this.behaviors)
				.withHeartbeat(data.heartbeat.getOrElse(this.heartbeat))
				.withParent(this)
				.withSystem(this)
				.withSystemIf(system.nonEmpty, system.get)
				.withOnInitIf(data.onInit.nonEmpty, data.onInit.get)
			val b2 = data.executionContext.fold(b1)(b1.withParallelDispatch)
			val b3 = if (data.useSerialDispatch) b2.withSerialDispatch() else b2
			val child = b3.build()
			children = children + (child.id -> child)
			SystemMsg.NewChild(child)
		}

	override def bang(path: ActorPath, msg: Msg): Unit = path match {
		case Local(actorId)           if actorRefs.contains(actorId) => actorRefs(actorId) ! msg
		case Remote("local", actorId) if actorRefs.contains(actorId) => actorRefs(actorId) ! msg
		case Remote(`id`, actorId)    if actorRefs.contains(actorId) => actorRefs(actorId) ! msg
		case Remote(systemId, _)      if systems.contains(systemId)  => systems(systemId)  ! (path, msg)
		case _ => // invalid system or actor id
	}
	
	override def ask(path: ActorPath, msg: Msg): CloseableFuture[Rsp] = path match {
		case Local(actorId)           if actorRefs.contains(actorId) => actorRefs(actorId) ? msg
		case Remote("local", actorId) if actorRefs.contains(actorId) => actorRefs(actorId) ? msg
		case Remote(`id`, actorId)    if actorRefs.contains(actorId) => actorRefs(actorId) ? msg
		case Remote(systemId, _)      if systems.contains(systemId)  => systems(systemId)  ? (path, msg)
		case Local(actorId)                                          => CloseableFuture.failed(new IllegalArgumentException(s"Invalid actor id: $actorId"))
		case Remote(systemId, _)                                     => CloseableFuture.failed(new IllegalArgumentException(s"Invalid system id: $systemId"))
	}
}

object ActorSystem {
	def apply[Msg, Rsp, State](id: String, state: State, heartbeat: HeartBeatStrategy)(using ExecutionContext): ActorSystem[Msg, Rsp, State] =
		new ActorSystem(id, state, heartbeat).tap { s => 
			s.onInit { _ => s.actorRefs += (s.id -> LocalActorRef(s)) } // register yourself as a valid actor
			s.initialize() 
		}

	inline def apply[Msg, Rsp, State](state: State, heartbeat: HeartBeatStrategy)(using ExecutionContext): ActorSystem[Msg, Rsp, State] =
		apply(IdGenerator.generate("system"), state, heartbeat)
}