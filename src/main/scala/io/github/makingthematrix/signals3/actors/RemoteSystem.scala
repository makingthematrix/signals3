package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture

trait RemoteSystem[Msg, Rsp]{
	import RemoteSystem.RemoteSystemMsg
	
	val id: String

	def bang(path: ActorPath, msg: Msg): Unit
	inline def !(tuple: (path: ActorPath, msg: Msg)): Unit = bang(tuple.path, tuple.msg)
	
	def bang(msg: RemoteSystemMsg): Unit
	inline def !(msg: RemoteSystemMsg): Unit = bang(msg)

	def ask(path: ActorPath, msg: Msg): CloseableFuture[Rsp]
	inline def ?(tuple: (path: ActorPath, msg: Msg)): CloseableFuture[Rsp] = ask(tuple.path, tuple.msg)

	def ask(msg: RemoteSystemMsg): CloseableFuture[RemoteSystemMsg]
	inline def ?(msg: RemoteSystemMsg): CloseableFuture[RemoteSystemMsg] = ask(msg)
}

object RemoteSystem {
	enum RemoteSystemMsg {
		case Done, InvalidId
		case SystemClosed(systemId: String)
	}
}