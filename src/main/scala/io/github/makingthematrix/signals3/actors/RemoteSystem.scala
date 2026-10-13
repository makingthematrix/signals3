package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture

trait RemoteSystem[Msg] {
	val id: String

	def ask(msg: SystemMsg): CloseableFuture[SystemMsg]
	def ask(msg: Msg, actorPath: ActorPath, behId: String): CloseableFuture[Msg]

	def tell(msg: SystemMsg): Unit
	def tell(msg: Msg, actorPath: ActorPath, behId: String): Unit
}

final class ConversionRemoteSystem[From, To](system: RemoteSystem[To])
                                            (using cFrom: Conversion[From, To], cTo: Conversion[To, From])
	extends RemoteSystem[From] {
	override val id: String = system.id

	override def ask(msg: SystemMsg): CloseableFuture[SystemMsg] = system.ask(msg)

	override def ask(msg: From, actorPath: ActorPath, behId: String): CloseableFuture[From] =
		system.ask(cFrom(msg), actorPath, behId).map(cTo)

	override def tell(msg: SystemMsg): Unit = system.tell(msg)

	override def tell(msg: From, actorPath: ActorPath, behId: String): Unit =
		system.tell(cFrom(msg), actorPath, behId)
}
