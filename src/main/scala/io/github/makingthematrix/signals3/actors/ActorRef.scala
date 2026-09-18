package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture

trait ActorRef[Msg, Rsp] {
	def !(msg: Msg): Unit
	def ?(msg: Msg): CloseableFuture[Rsp]

	val path: ActorPath
	val isLocal: Boolean
}

// Local actor reference - direct to Actor instance
final class LocalActorRef[Msg, Rsp, State] private[actors] (private val actor: Actor[Msg, Rsp, State])
	extends ActorRef[Msg, Rsp] {
	override def !(msg: Msg): Unit = actor ! msg
	override def ?(msg: Msg): CloseableFuture[Rsp] = actor ? msg
	override val path: ActorPath = ActorPath.Local(actor.id)
	override val isLocal: Boolean = true
}

// Remote actor reference - proxies to remote actor
final class RemoteActorRef[Msg, Rsp] private[actors](val path: ActorPath.Remote,
                                                     private val system: RemoteSystem[Msg, Rsp])
	extends ActorRef[Msg, Rsp]{
	override def !(msg: Msg): Unit = system ! (path, msg)
	override def ?(msg: Msg): CloseableFuture[Rsp] = system ? (path, msg)

	override val isLocal: Boolean = false
}