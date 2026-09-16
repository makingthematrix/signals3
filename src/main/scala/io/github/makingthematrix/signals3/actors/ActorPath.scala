package io.github.makingthematrix.signals3.actors

import scala.annotation.static

sealed trait ActorPath {
	def asString: String
	def name: String
	def systemName: String
}

object ActorPath {
	@static private val LocalPattern = """local://(.+)""".r
	@static private val RemotePattern = """([^/:]+)://([^:]+):(\d+)/(.+)""".r

	// Local actor within the same JVM
	final case class Local(actorId: String) extends ActorPath {
		def asString: String = s"local://$actorId"
		def name: String = actorId
		def systemName: String = "local"
	}

	// Remote actor in a different JVM/process
	final case class Remote(systemName: String, host: String, port: Int, actorId: String) extends ActorPath {
		def asString: String = s"$systemName://$host:$port/$actorId"
		def name: String = actorId
	}
	
	def parse(path: String): ActorPath = path match {
		case LocalPattern(actorId) => Local(actorId)
		case RemotePattern(system, host, port, id) => Remote(system, host, port.toInt, id)
		case _ => throw new IllegalArgumentException(s"Invalid actor path: $path")
	}
}