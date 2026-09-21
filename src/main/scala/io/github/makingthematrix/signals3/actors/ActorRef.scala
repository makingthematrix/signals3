package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture

trait ActorRef[Msg, Rsp] {
	def bang(msg: Msg, behId: String): Unit
	inline def bang(msg: Msg): Unit = bang(msg, "")
	inline def !(msg: Msg): Unit = bang(msg)
	inline def !(tuple: (msg: Msg, behId: String)): Unit = bang(tuple.msg, tuple.behId)
	
	def ask(msg: Msg, behId: String): CloseableFuture[Rsp]
	inline def ask(msg: Msg): CloseableFuture[Rsp] = ask(msg, "")
	inline def ?(msg: Msg): CloseableFuture[Rsp] = ask(msg)
	inline def ?(tuple: (msg: Msg, behId: String)): CloseableFuture[Rsp] = ask(tuple.msg, tuple.behId)

	val path: ActorPath
	val isLocal: Boolean
}

// Local actor reference - direct to Actor instance
final class LocalActorRef[Msg, Rsp, State] private[actors] (private val actor: Actor[Msg, Rsp, State])
	extends ActorRef[Msg, Rsp] {
	override def bang(msg: Msg, behId: String): Unit = actor.bang(behId, msg)
	override def ask(msg: Msg, behId: String): CloseableFuture[Rsp] = actor.ask(behId, msg)
	override val path: ActorPath = ActorPath.Local(actor.id)
	override val isLocal: Boolean = true
}

// Remote actor reference - proxies to remote actor
final class RemoteActorRef[Msg, Rsp] private[actors](val path: ActorPath.Remote,
                                                     private val system: RemoteSystem[Msg, Rsp])
	extends ActorRef[Msg, Rsp]{
	override def bang(msg: Msg, behId: String): Unit = system.bang(msg, path, behId)
	override def ask(msg: Msg, behId: String): CloseableFuture[Rsp] = system.ask(msg, path, behId)

	override val isLocal: Boolean = false
}