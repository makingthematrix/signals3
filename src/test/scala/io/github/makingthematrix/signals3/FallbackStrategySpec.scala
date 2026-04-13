package io.github.makingthematrix.signals3
import testutils.awaitAllTasks

class FallbackStrategySpec extends munit.FunSuite {
  import Threading.defaultContext

  test("Map and throw exception with RETHROW") {
    val in = SourceStream[Int]()
    val out = in.map {
      case 2 => throw new RuntimeException("Map and throw exception")
      case n => n
    }

    var res = 0
    out.foreach { n =>
      println(s"Received: $n")
      res += n
    }
    in ! 1
    interceptMessage("Map and throw exception")(in ! 2)
    in ! 3

    awaitAllTasks
    assertEquals(res, 4)
  }

  test("Map and throw exception with IGNORE") {
    val in = SourceStream[Int]()
    val out = in.map {
      case 2 => throw new RuntimeException("Map and throw exception")
      case n => n
    }

    var res = 0
    out.foreach { n =>
      println(s"Received: $n")
      res += n
    }
    in ! 1
    in ! 2
    in ! 3

    awaitAllTasks
    assertEquals(res, 4)
  }
}
