package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.actors.Actor.HeartBeatStrategy

import scala.concurrent.ExecutionContext
import scala.util.Try

/** This is an Actor subclass you may use as your base superclass if you prefer to implement actor's logic
	* through overriding `onMessage` and adding private fields instead of working with more functional,
	* but also more complex `ActorImpl`.
	*
	* Note that this solution also assumes that you will use the same type for messages and responses.
	* If you prefer not to use responses at all, simply end processing of each message with `Actor.NoResponse[Msg]`
	*
	*/
abstract class BaseActor[Msg](override val id: String,
                              override protected val heartbeat: HeartBeatStrategy = Actor.defBeat,
                              override val parent: Option[Actor[Msg, Msg, Unit]] = None,
                              override val system: Option[ActorSystem[Msg, Msg, Unit]] = None
                             )(using ExecutionContext)
  extends ActorImpl[Msg, Msg, Unit](id, (), heartbeat, parent, system) {
	override protected def onMessage(msg: Msg): Try[Option[Msg]]
}
