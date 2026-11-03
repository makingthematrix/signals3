package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture
import io.github.makingthematrix.signals3.actors.ActorPath.Direct

import scala.Conversion.into

trait RemoteSystem[Msg] {
	val id: String

	def ask(msg: SystemMsg, path: ActorPath): CloseableFuture[SystemMsg]
	def ask(msg: into[Msg], path: ActorPath, behId: String): CloseableFuture[Msg]
	def tell(msg: SystemMsg, path: ActorPath): Unit
	def tell(msg: into[Msg], path: ActorPath, behId: String): Unit
	
	inline def ?(t: (SystemMsg, ActorPath)): CloseableFuture[SystemMsg] = ask(t._1, t._2)
	inline def ask(msg: SystemMsg): CloseableFuture[SystemMsg] = ask(msg, Direct)
	inline def ?(msg: SystemMsg): CloseableFuture[SystemMsg] = ask(msg)
	
	inline def !(t: (SystemMsg, ActorPath)): Unit = tell(t._1, t._2)
	inline def tell(msg: SystemMsg): Unit = tell(msg, Direct)
	inline def !(msg: SystemMsg): Unit = tell(msg)
}

final class ConversionRemoteSystem[Msg, Rsp](system: RemoteSystem[Rsp])
                                            (using cMsg: Conversion[Msg, Rsp], cRsp: Conversion[Rsp, Msg])
	extends RemoteSystem[Msg] {
	override val id: String = system.id

	override def ask(msg: SystemMsg, path: ActorPath): CloseableFuture[SystemMsg] = system.ask(msg, path)
	
	override def ask(msg: into[Msg], path: ActorPath, behId: String): CloseableFuture[Msg] = 
		system.ask(cMsg(msg), path, behId).map(cRsp)

	override def tell(msg: SystemMsg, path: ActorPath): Unit = system.tell(msg, path)

	override def tell(msg: into[Msg], path: ActorPath, behId: String): Unit = system.tell(cMsg(msg), path, behId)
}
