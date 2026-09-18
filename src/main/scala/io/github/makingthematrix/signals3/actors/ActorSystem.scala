package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.actors.Actor.HeartBeatStrategy

import scala.concurrent.ExecutionContext
import scala.util.chaining.scalaUtilChainingOps

final class ActorSystem[Msg, Rsp, State](override val id: String,
                                         state: State,
                                         override protected val heartbeat: HeartBeatStrategy)
                                        (using ExecutionContext)
	extends ActorImpl[Msg, Rsp, State](id, state, heartbeat) {
	import SystemMsg.*
	private var reg: Map[String, ActorRef[Msg, Rsp]] = Map.empty

	override protected def processSysEntry(msg: SysEntry): Unit = msg match {
		case (Register(actor), p) =>
			val ref = LocalActorRef(actor)
			reg = reg + (actor.id -> ref)
			respond(p, Ref(ref))
		case (ActorClosed(id), _) =>
			reg = reg - id
			super.processSysEntry(msg)
		case (AskForRef(id), p) =>
			respond(p, reg.get(id).map(Ref(_)).getOrElse(InvalidId))
		case (AskForRefAsync(sender, id), p) =>
			val rsp = reg.get(id).map(sender.SystemMsg.Ref(_)).getOrElse(sender.SystemMsg.InvalidId)
			sender ! rsp
			respond(p, Done)
		case _ =>
			super.processSysEntry(msg)
	}

	override protected def spawn(data: SystemMsg.Spawn): SystemMsg =
		if (children.contains(data.id)) SystemMsg.InvalidId else {
			val b1 = ActorBuilder[Msg, Rsp, State]()
				.withIdIf(data.id.nonEmpty, data.id)
				.withState(data.state.getOrElse(this.state))
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

	// @todo: Receiving messages for remote actors - not implemented yet
/*	private[actors] def bang(tuple: (path: ActorPath, msg: Msg)): Unit = {}
	inline private[actors] def !(tuple: (path: ActorPath, msg: Msg)): Unit = bang(tuple)

	private[actors] def ask(tuple: (path: ActorPath, msg: Msg)): CloseableFuture[Rsp] = CloseableFuture.failed(new IllegalArgumentException)
	inline private[actors] def ?(tuple: (path: ActorPath, msg: Msg)): CloseableFuture[Rsp] = ask(tuple)*/
}

object ActorSystem {
	def apply[Msg, Rsp, State](id: String, state: State, heartbeat: HeartBeatStrategy)(using ExecutionContext): ActorSystem[Msg, Rsp, State] =
		new ActorSystem(id, state, heartbeat).tap { _.initialize() }

	inline def apply[Msg, Rsp, State](state: State, heartbeat: HeartBeatStrategy)(using ExecutionContext): ActorSystem[Msg, Rsp, State] =
		apply(IdGenerator.generate("system"), state, heartbeat)
}