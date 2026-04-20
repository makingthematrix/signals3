package io.github.makingthematrix.signals3
import testutils.waitForResult

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
      res += n
    }
    in ! 1
    interceptMessage("Map and throw exception")(in ! 2)
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 4)
  }

  test("Map and throw exception with IGNORE") {
    val in = SourceStream[Int](FallbackStrategy.ignore)
    val out = in.map {
      case 2 => throw new RuntimeException("Map and throw exception")
      case n => n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2 // this should be ignored
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 4)
  }

  test("Map and throw exception with USEDEFAULT(-4)") {
    val in = SourceStream[Int](FallbackStrategy.useDefault(-4))
    val out = in.map {
      case 2 => throw new RuntimeException("Map and throw exception")
      case n => n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2 // this should be replaced with -4
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 0)
  }
}
