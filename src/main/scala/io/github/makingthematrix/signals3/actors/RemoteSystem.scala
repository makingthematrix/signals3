package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture

trait RemoteSystem[Msg]{
	import RemoteSystem.RemoteSystemMsg
	
	val id: String

	def tell(msg: Msg, path: ActorPath, behId: String): Unit
	inline def !(tuple: (msg: Msg, path: ActorPath, behId: String)): Unit = tell(tuple.msg, tuple.path, tuple.behId)
	
	def tell(msg: RemoteSystemMsg): Unit
	inline def !(msg: RemoteSystemMsg): Unit = tell(msg)

	def ask(msg: Msg, path: ActorPath, behId: String): CloseableFuture[Msg]
	inline def ?(tuple: (msg: Msg, path: ActorPath, behId: String)): CloseableFuture[Msg] = ask(tuple.msg, tuple.path, tuple.behId)

	def ask(msg: RemoteSystemMsg): CloseableFuture[RemoteSystemMsg]
	inline def ?(msg: RemoteSystemMsg): CloseableFuture[RemoteSystemMsg] = ask(msg)
}

object RemoteSystem {
	enum RemoteSystemMsg {
		case Done
		case SystemClosed(systemId: String)
		case AskForRef(actorId: String)
		case Ref[Msg](ref: ActorRef[Msg])
		case RegisterSystem[Msg](system: RemoteSystem[Msg])
		case UnregisterSystem(systemId: String)
	}
}
