package io.github.makingthematrix.signals3.actors

// this is on purpose not a sealed trait to enable the user to define their own tags
trait ActorTag (val name: String)

object ActorTag {
  case object Log    extends ActorTag("log")
	case object Stats  extends ActorTag("stats")
	case object System extends ActorTag("system")
}
