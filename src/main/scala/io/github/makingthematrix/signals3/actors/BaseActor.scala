package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.actors.Actor.{HeartBeatStrategy, InvalidIdException, UnhandledMsgException}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

/** This is an Actor subclass you may use as your base superclass if you prefer to implement actor's logic
	* through overriding `onMessage` and by adding new private fields directly to the new subclass instead of
	* working with state and behaviors.
	*
	* Note that this solution also assumes that you will use the same type for messages and responses.
	* If you prefer not to use responses at all, simply end processing of each message with `noResponse[Msg]`
	*
	*/
abstract class BaseActor[Msg](override val id: String,
                              override protected val heartbeat: HeartBeatStrategy = Actor.defBeat,
                              override val parent: Option[Actor[Msg, Unit]] = None,
                              override val system: Option[ActorSystem[Msg, Unit]] = None
                             )(using ExecutionContext)
  extends ActorImpl[Msg, Unit](id, (), heartbeat, parent, system) {
	inline protected def reply(msg: Msg): Try[Option[Msg]] = Success(Some(msg))
	inline protected def noResponse: Try[Option[Msg]] = Actor.NoResponse[Msg]
	inline protected def fail(error: String): Try[Option[Msg]] = Failure(new IllegalStateException(error))
	inline protected def invalidActorId(actorId: String): Try[Option[Msg]] = Failure(InvalidIdException(actorId))
	inline protected def unhandledMsg(msg: Msg): Try[Option[Msg]] = Failure(UnhandledMsgException(msg.toString))

	override protected def spawn(data: SystemMsg.Spawn): SystemMsg
	
	override protected def onMessage(msg: Msg): Try[Option[Msg]]
}
