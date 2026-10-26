package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture

import scala.Conversion.into

trait RemoteSystem[Msg] {
	val id: String

	def ask(msg: SystemMsg): CloseableFuture[SystemMsg]
	def ask(msg: SystemMsg, path: ActorPath): CloseableFuture[SystemMsg]
	def ask(msg: into[Msg], path: ActorPath, behId: String): CloseableFuture[Msg]

	def tell(msg: SystemMsg): Unit
	def tell(msg: SystemMsg, path: ActorPath): Unit
	def tell(msg: into[Msg], path: ActorPath, behId: String): Unit
}

final class ConversionRemoteSystem[Msg, Rsp](system: RemoteSystem[Rsp])
                                            (using cMsg: Conversion[Msg, Rsp], cRsp: Conversion[Rsp, Msg])
	extends RemoteSystem[Msg] {
	override val id: String = system.id

	override def ask(msg: SystemMsg): CloseableFuture[SystemMsg] = system.ask(msg)
	override def ask(msg: SystemMsg, path: ActorPath): CloseableFuture[SystemMsg] = system.ask(msg, path)
	
	override def ask(msg: into[Msg], path: ActorPath, behId: String): CloseableFuture[Msg] =
		system.ask(cMsg(msg), path, behId).map(cRsp)

	override def tell(msg: SystemMsg): Unit = system.tell(msg)
	override def tell(msg: SystemMsg, path: ActorPath): Unit = system.tell(msg, path)

	override def tell(msg: into[Msg], path: ActorPath, behId: String): Unit =
		system.tell(cMsg(msg), path, behId)
}
