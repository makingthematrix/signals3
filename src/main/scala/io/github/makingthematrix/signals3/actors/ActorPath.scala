package io.github.makingthematrix.signals3.actors

import scala.annotation.static

sealed trait ActorPath {
	val actorId: String
	val systemId: String
	val asString: String = s"$systemId://$actorId"
}

object ActorPath {
	@static private val LocalPattern = """local://(.+)""".r
	@static private val RemotePattern = """([^/:]+)://(.+)""".r

	// Local actor within the same actor system
	final case class Local(actorId: String) extends ActorPath {
		val systemId: String = "local"
	}

	// Remote actor in a different JVM/process
	final case class Remote(systemId: String, actorId: String) extends ActorPath
	
	def parse(path: String): Option[ActorPath] = path match {
		case LocalPattern(actorId) => Some(Local(actorId))
		case RemotePattern(system, id) => Some(Remote(system, id))
		case _ => None
	}
}