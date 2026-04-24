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

  // ============ DROPWHILE tests (additional) ============

  test("DropWhile and throw exception with IGNORE") {
    val in = SourceStream[Int](FallbackStrategy.ignore)
    val out = in.dropWhile { n =>
      if (n == 2) throw new RuntimeException("DropWhile and throw exception")
      n < 3
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2 // p(2) throws with IGNORE, dropping stays true
    in ! 4 // p(4)=false, dropping becomes false, 4 is dispatched

    waitForResult(out, 4)
    assertEquals(res, 4)
  }

  // ============ SCAN tests ============

  test("Scan and throw exception with RETHROW") {
    val in = SourceStream[Int]()
    val out = in.scan(0) { (acc, n) =>
      if (n == 2) throw new RuntimeException("Scan and throw exception")
      acc + n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    interceptMessage("Scan and throw exception")(in ! 2)

    assert(res == 1)
  }

  test("Scan and throw exception with IGNORE") {
    val in = SourceStream[Int](FallbackStrategy.ignore)
    val out = in.scan(0) { (acc, n) =>
      if (n == 2) throw new RuntimeException("Scan and throw exception")
      acc + n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2 // ignored, accumulator stays at 1
    in ! 3 // 1 + 3 = 4

    waitForResult(out, 4)
    assertEquals(res, 5) // 1 + 4
  }

  // ============ GROUPBY tests ============

  test("GroupBy and throw exception with RETHROW") {
    val in = SourceStream[Int]()
    val out = in.groupBy { n =>
      if (n == 2) throw new RuntimeException("GroupBy and throw exception")
      n % 2 == 0
    }

    var res = Seq.empty[Seq[Int]]
    out.foreach { n =>
      res = res :+ n
    }
    in ! 1
    interceptMessage("GroupBy and throw exception")(in ! 2)

    assert(res.isEmpty)
  }

  test("GroupBy and throw exception with IGNORE") {
    val in = SourceStream[Int](FallbackStrategy.ignore)
    val out = in.groupBy { n =>
      if (n == 2) throw new RuntimeException("GroupBy and throw exception")
      n % 2 == 0
    }

    var res = Seq.empty[Seq[Int]]
    out.foreach { n =>
      res = res :+ n
    }
    in ! 1
    in ! 2 // groupBy throws, ignored, 2 not added to buffer
    in ! 3
    in ! 4 // groupBy(4) returns true, [1,3] is dispatched

    waitForResult(out, Seq(4))
    assertEquals(res, Seq(Seq(1, 3)))
  }

  // ============ TAKEWHILE tests ============

  test("TakeWhile and throw exception with IGNORE") {
    val in = SourceStream[Int](FallbackStrategy.ignore)
    val out = in.takeWhile { n =>
      if (n == 2) throw new RuntimeException("TakeWhile and throw exception")
      n < 3
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1 // p(1)=true, !p(1)=false, dispatches 1
    in ! 2 // p(2) throws with IGNORE, silently ignored
    in ! 3 // p(3)=false, !p(3)=true, closes the stream

    waitForResult(out, 1)
    assertEquals(res, 1)
    assert(out.isClosed)
  }

  // ============ FLATMAP tests ============

  test("FlatMap and throw exception with RETHROW") {
    val in = SourceStream[Int]()
    val streamA = SourceStream[String]()
    val streamB = SourceStream[String]()
    val out = in.flatMap { n =>
      if (n == 2) throw new RuntimeException("FlatMap and throw exception")
      if (n % 2 != 0) streamA else streamB
    }

    var res = ""
    out.foreach { n =>
      res += n
    }
    in ! 1
    streamA ! "A"
    streamB ! "B"
    waitForResult(out, "A")
    assertEquals(res, "A")
    interceptMessage("FlatMap and throw exception")(in ! 2)
  }

  test("FlatMap and throw exception with IGNORE") {
    val in = SourceStream[Int](FallbackStrategy.ignore)
    val streamA = SourceStream[String]()
    val streamB = SourceStream[String]()
    val out = in.flatMap { n =>
      if (n == 2) throw new RuntimeException("FlatMap and throw exception")
      if (n % 2 != 0) streamA else streamB
    }

    var res = ""
    out.foreach { n =>
      res += n
    }
    in ! 1
    streamA ! "A"
    streamB ! "B"
    waitForResult(out, "A")
    assertEquals(res, "A")

    in ! 2 // 2 is ignored so no change in what out is attached to
    streamA ! "A"
    streamB ! "B"
    waitForResult(out, "A")
    assertEquals(res, "AA")
  }
}
