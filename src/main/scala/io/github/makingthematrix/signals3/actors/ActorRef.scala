package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture

trait ActorRef[Msg, Rsp] {
	def !(msg: Msg): Unit
	def ?(msg: Msg): CloseableFuture[Rsp]
	
	val path: ActorPath
	val isLocal: Boolean
	val isValid: Boolean
}

// Local actor reference - direct to Actor instance
final case class LocalActorRef[Msg, Rsp, State] private[actors] (private val actor: Actor[Msg, Rsp, State]) 
	extends ActorRef[Msg, Rsp] {
	override def !(msg: Msg): Unit = actor ! msg
	override def ?(msg: Msg): CloseableFuture[Rsp] = actor ? msg
	override val path: ActorPath = ActorPath.Local(actor.id)
	override val isLocal: Boolean = true
	override val isValid: Boolean = !actor.isClosed
}

// Remote actor reference - proxies to remote actor
/*final case class RemoteActorRef[Msg, Rsp] private[actors](path: ActorPath.Remote,
                                                          private val system: ActorSystem[Msg, Rsp, ?])
	extends ActorRef[Msg, Rsp]{
	override def !(msg: Msg): Unit = system ! (path, msg)
	override def ?(msg: Msg): CloseableFuture[Rsp] = system ? (path, msg)
	
	override val isLocal: Boolean = false
	override val isValid: Boolean = true // Remote validity is connection-dependent
}*/