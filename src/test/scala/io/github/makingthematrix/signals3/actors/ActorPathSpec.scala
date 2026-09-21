package io.github.makingthematrix.signals3.actors

import munit.FunSuite

/**
 * Unit tests for [[ActorPath]]: parsing of the string form and the `asString` format.
 */
class ActorPathSpec extends FunSuite {

  test("parsing an empty path yields Direct") {
    assertEquals(ActorPath.parse(""), Some(ActorPath.Direct))
  }

  test("parsing a local path yields Local") {
    assertEquals(ActorPath.parse("local://abc"), Some(ActorPath.Local("abc")))
  }

  test("parsing a remote path yields Remote") {
    assertEquals(ActorPath.parse("sys1://actor1"), Some(ActorPath.Remote("sys1", "actor1")))
  }

  test("parsing garbage yields None") {
    assertEquals(ActorPath.parse("no-slashes"), None)
    assertEquals(ActorPath.parse("://actor1"), None) // an empty system id is not a valid path
  }

  test("asString formats the system id and the actor id") {
    assertEquals(ActorPath.Remote("sys1", "a1").asString, "sys1://a1")
    assertEquals(ActorPath.Local("a1").asString, "://a1")
    assertEquals(ActorPath.Direct.asString, "://")
  }

  test("Remote paths round-trip through asString and parse") {
    val path = ActorPath.Remote("sys1", "a1")
    assertEquals(ActorPath.parse(path.asString), Some(path))
  }
}
