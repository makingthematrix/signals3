package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture

trait ActorRef[Msg] {
	def bang(msg: Msg, behId: String): Unit
	inline def bang(msg: Msg): Unit = bang(msg, "")
	inline def !(msg: Msg): Unit = bang(msg)
	inline def !(tuple: (msg: Msg, behId: String)): Unit = bang(tuple.msg, tuple.behId)
	
	def ask(msg: Msg, behId: String): CloseableFuture[Msg]
	inline def ask(msg: Msg): CloseableFuture[Msg] = ask(msg, "")
	inline def ?(msg: Msg): CloseableFuture[Msg] = ask(msg)
	inline def ?(tuple: (msg: Msg, behId: String)): CloseableFuture[Msg] = ask(tuple.msg, tuple.behId)

	val path: ActorPath
	val isLocal: Boolean
}

// Local actor reference - direct to Actor instance
final class LocalActorRef[Msg, State] private[actors] (private val actor: Actor[Msg, State])
	extends ActorRef[Msg] {
	override def bang(msg: Msg, behId: String): Unit = actor.tell(behId, msg)
	override def ask(msg: Msg, behId: String): CloseableFuture[Msg] = actor.ask(behId, msg)
	override val path: ActorPath = ActorPath.Local(actor.id)
	override val isLocal: Boolean = true
}

// Remote actor reference - proxies to remote actor
final class RemoteActorRef[Msg] private[actors](val path: ActorPath.Remote,
                                                     private val system: RemoteSystem[Msg])
	extends ActorRef[Msg]{
	override def bang(msg: Msg, behId: String): Unit = system.tell(msg, path, behId)
	override def ask(msg: Msg, behId: String): CloseableFuture[Msg] = system.ask(msg, path, behId)

	override val isLocal: Boolean = false
}