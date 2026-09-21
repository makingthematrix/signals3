package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture

trait RemoteSystem[Msg, Rsp]{
	import RemoteSystem.RemoteSystemMsg
	
	val id: String

	def bang(msg: Msg, path: ActorPath, behId: String): Unit
	inline def !(tuple: (msg: Msg, path: ActorPath, behId: String)): Unit = bang(tuple.msg, tuple.path, tuple.behId)
	
	def bang(msg: RemoteSystemMsg): Unit
	inline def !(msg: RemoteSystemMsg): Unit = bang(msg)

	def ask(msg: Msg, path: ActorPath, behId: String): CloseableFuture[Rsp]
	inline def ?(tuple: (msg: Msg, path: ActorPath, behId: String)): CloseableFuture[Rsp] = ask(tuple.msg, tuple.path, tuple.behId)

	def ask(msg: RemoteSystemMsg): CloseableFuture[RemoteSystemMsg]
	inline def ?(msg: RemoteSystemMsg): CloseableFuture[RemoteSystemMsg] = ask(msg)
}

object RemoteSystem {
	enum RemoteSystemMsg {
		case Done
		case SystemClosed(systemId: String)
		case AskForRef(actorId: String)
		case Ref[Msg, Rsp](ref: ActorRef[Msg, Rsp])
	}
}
