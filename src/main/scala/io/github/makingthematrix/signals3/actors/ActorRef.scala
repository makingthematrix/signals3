package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture
import Actor.OtherActor

trait ActorRef[Msg] {
	def tell(msg: Msg, behId: String): Unit
	inline def tell(msg: Msg): Unit = tell(msg, "")
	inline def !(msg: Msg): Unit = tell(msg)
	inline def !(tuple: (msg: Msg, behId: String)): Unit = tell(tuple.msg, tuple.behId)
	
	def ask(msg: Msg, behId: String): CloseableFuture[Msg]
	inline def ask(msg: Msg): CloseableFuture[Msg] = ask(msg, "")
	inline def ?(msg: Msg): CloseableFuture[Msg] = ask(msg)
	inline def ?(tuple: (msg: Msg, behId: String)): CloseableFuture[Msg] = ask(tuple.msg, tuple.behId)

	val path: ActorPath
	val isLocal: Boolean
}

// Local actor reference - direct to Actor instance
final class LocalActorRef[Msg] private[actors] (private val actor: OtherActor[Msg])
	extends ActorRef[Msg] {
	override def tell(msg: Msg, behId: String): Unit = actor.tell(behId, msg)
	override def ask(msg: Msg, behId: String): CloseableFuture[Msg] = actor.ask(behId, msg)
	override val path: ActorPath = ActorPath.Local(actor.id)
	override val isLocal: Boolean = true
}

object LocalActorRef {
	def apply[Msg](actor: Actor[Msg, ?]): LocalActorRef[Msg] = new LocalActorRef(OtherActor(actor))
}

// Remote actor reference - proxies to remote actor
final class RemoteActorRef[Msg] private[actors](val path: ActorPath.Remote,
                                                     private val system: RemoteSystem[Msg])
	extends ActorRef[Msg]{
	override def tell(msg: Msg, behId: String): Unit = system.tell(msg, path, behId)
	override def ask(msg: Msg, behId: String): CloseableFuture[Msg] = system.ask(msg, path, behId)

	override val isLocal: Boolean = false
}