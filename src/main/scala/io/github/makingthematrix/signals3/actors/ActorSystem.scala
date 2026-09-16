package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture
import io.github.makingthematrix.signals3.Serialized.dispatcher
import ActorSystem.*

final class ActorSystem[Msg, Rsp, State](id: String, state: State) extends ActorImpl[Msg, Rsp, State](id, state) {
	private var reg: Map[String, ActorRef[Msg, Rsp]] = Map.empty
	import ActorSystem.Msg.{Register, GetRef}
	
	val behavior: Actor.PF[Msg, Rsp, State] = {
		case (Register(actor), _) =>
			reg = reg + (actor.id -> LocalActorRef(actor.asInstanceOf[Actor[Msg, Rsp, State]]))
			None
		case (Msg.GetRef(actor, id), _) => None
	}
	
	addBehavior("system" -> behavior)

	def bang(tuple: (path: ActorPath, msg: Msg)): Unit = {}
	inline def !(tuple: (path: ActorPath, msg: Msg)): Unit = bang(tuple)

	def ask(tuple: (path: ActorPath, msg: Msg)): CloseableFuture[Rsp] = CloseableFuture.failed(new IllegalArgumentException)
	inline def ?(tuple: (path: ActorPath, msg: Msg)): CloseableFuture[Rsp] = ask(tuple)
}

object ActorSystem {
	enum Msg {
		case Register[Msg, Rsp, State](actor: Actor[Msg, Rsp, State])
		case GetRef[Msg, Rsp, State](actor: Actor[Msg, Rsp, State], id: String)
	}
}