package io.github.makingthematrix.signals3
import testutils.waitForResult

class FallbackStrategySpec extends munit.FunSuite {
  import Threading.defaultContext

  // ============ MAP tests ============

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

  // ============ COLLECT tests ============

  test("Collect and throw exception with RETHROW") {
    val in = SourceStream[Int]()
    val out = in.collect {
      case 2 => throw new RuntimeException("Collect and throw exception")
      case n if n > 0 => n * 10
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    interceptMessage("Collect and throw exception")(in ! 2)
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 40)
  }

  test("Collect and throw exception with IGNORE") {
    val in = SourceStream[Int](FallbackStrategy.ignore)
    val out = in.collect {
      case 2 => throw new RuntimeException("Collect and throw exception")
      case n if n > 0 => n * 10
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2 // this should be ignored (collect predicate matches but function throws)
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 40)
  }

  test("Collect and throw exception with USEDEFAULT(-100)") {
    val in = SourceStream[Int](FallbackStrategy.useDefault(-100))
    val out = in.collect {
      case 2 => throw new RuntimeException("Collect and throw exception")
      case n if n > 0 => n * 10
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2 // this should emit -100
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, -60)
  }

  // ============ FILTER tests ============

  test("Filter and throw exception with RETHROW") {
    val in = SourceStream[Int]()
    val out = in.filter { n =>
      if (n == 2) throw new RuntimeException("Filter and throw exception")
      n % 2 == 0
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    interceptMessage("Filter and throw exception")(in ! 2)
    in ! 4

    waitForResult(out, 4)
    assertEquals(res, 4)
  }

  test("Filter and throw exception with IGNORE") {
    val in = SourceStream[Int](FallbackStrategy.ignore)
    val out = in.filter { n =>
      if (n == 2) throw new RuntimeException("Filter and throw exception")
      n % 2 == 0
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2 // predicate throws, should be ignored
    in ! 4

    waitForResult(out, 4)
    assertEquals(res, 4)
  }

  // ============ DROPWHILE tests ============

  test("DropWhile and throw exception with RETHROW") {
    val in = SourceStream[Int]()
    val out = in.dropWhile { n =>
      if (n == 2) throw new RuntimeException("DropWhile and throw exception")
      n < 3
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    interceptMessage("DropWhile and throw exception")(in ! 2)

    // After exception, stream is in undefined state, just verify it doesn't crash
    assert(res == 0)
  }



  // ============ Strategy merging tests ============

  test("Merged strategies: IGNORE overrides RETHROW in joined streams") {
    val in1 = SourceStream[Int](FallbackStrategy.rethrow)
    val in2 = SourceStream[Int](FallbackStrategy.ignore)
    val out = in1.join(in2)

    var res = 0
    out.foreach { n =>
      res += n
    }
    in1 ! 1
    in2 ! 2
    in1 ! 4

    waitForResult(out, 4)
    assertEquals(res, 7)
  }

  // ============ Retry tests ============

  test("UseDefault with retry") {
    var callCount = 0
    val in = SourceStream[Int](FallbackStrategy.UseDefault(-1, retryTimes = 2))
    val out = in.map { n =>
      if (n == 2) {
        callCount += 1
        throw new RuntimeException("Retry test")
      }
      n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2
    in ! 3

    waitForResult(out, 3)
    assertEquals(callCount, 3)
    assertEquals(res, 3)
  }

  // ============ Side effects tests ============

  test("Side effects are triggered on exception") {
    var sideEffectCalled = false
    val sideEffect: Throwable => Unit = _ => sideEffectCalled = true
    val in = SourceStream[Int](FallbackStrategy.Ignore(sideEffects = List(sideEffect)))
    val out = in.map { n =>
      if (n == 2) throw new RuntimeException("Side effect test")
      n
    }

    out.foreach { _ => () }
    in ! 1
    in ! 2
    in ! 3

    waitForResult(out, 3)
    assert(sideEffectCalled)
  }

  // ============ Chained operations ============

  test("Map with exception chained from another map with IGNORE") {
    val in = SourceStream[Int](FallbackStrategy.ignore)
    val out = in.map(_ * 2).map { n =>
      if (n == 4) throw new RuntimeException("Chained map test")
      n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 8) // 2 + 6 (4 was ignored)
  }

  // ============ Edge cases ============

  test("Empty stream with IGNORE strategy") {
    val in = SourceStream[Int](FallbackStrategy.ignore)
    val out: Stream[Int] = in.map { n =>
      throw new RuntimeException("Should not be called")
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    // Don't send any events

    assertEquals(res, 0)
  }

  test("Exception in first event with UseDefault") {
    val in = SourceStream[Int](FallbackStrategy.useDefault(-999))
    val out = in.map { n =>
      if (n == 1) throw new RuntimeException("First event throws")
      n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2

    waitForResult(out, 2)
    assertEquals(res, -997)
  }

  test("Multiple strategies in sequence: map then filter") {
    val in = SourceStream[Int](FallbackStrategy.ignore)
    val out = in.map { n =>
      if (n == 1) throw new RuntimeException("Map throws")
      n * 2
    }.filter { n =>
      if (n == 4) throw new RuntimeException("Filter throws")
      n > 0
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 6)
  }
}
