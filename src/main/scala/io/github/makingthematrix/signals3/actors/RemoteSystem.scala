package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture
import io.github.makingthematrix.signals3.actors.Actor.SystemMsg

trait RemoteSystem[Msg] extends Actor[Msg, ?] {
	val id: String

	def tell(msg: Msg, path: ActorPath, behId: String): Unit
	def !(tuple: (msg: Msg, path: ActorPath, behId: String)): Unit = tell(tuple.msg, tuple.path, tuple.behId)
	
	def tell(msg: SystemMsg): Unit
	
	def ask(msg: Msg, path: ActorPath, behId: String): CloseableFuture[Msg]
	def ?(tuple: (msg: Msg, path: ActorPath, behId: String)): CloseableFuture[Msg] = ask(tuple.msg, tuple.path, tuple.behId)

	def ask(msg: SystemMsg): CloseableFuture[SystemMsg]
}
