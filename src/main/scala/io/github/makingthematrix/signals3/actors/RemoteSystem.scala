package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture

trait RemoteSystem[Msg, Rsp]{
	val id: String

	def bang(path: ActorPath, msg: Msg): Unit
	inline def !(tuple: (path: ActorPath, msg: Msg)): Unit = bang(tuple.path, tuple.msg)

	def ask(path: ActorPath, msg: Msg): CloseableFuture[Rsp]
	inline def ?(tuple: (path: ActorPath, msg: Msg)): CloseableFuture[Rsp] = ask(tuple.path, tuple.msg)
}
