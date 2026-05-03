package io.github.makingthematrix.signals3
import testutils.{awaitAllTasks, waitForResult}

class FallbackStrategySpec extends munit.FunSuite {
  import Threading.defaultContext

  // ============ MAP tests ============

  test("Map and throw exception") {
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

  test("Map and ignore exception") {
    val in = SourceStream[Int]()
    val out = in.ignoreExceptions.map {
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

  test("Collect and throw exception") {
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

  test("Collect and ignore exception") {
    val in = SourceStream[Int]()
    val out = in.ignoreExceptions.collect {
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

  test("Filter and throw exception") {
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

  test("Filter and ignore exception") {
    val in = SourceStream[Int]()
    val out = in.ignoreExceptions.filter { n =>
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

  test("DropWhile and throw exception") {
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

  // ============ Side effects tests ============

  test("Side effects are triggered on exception") {
    var sideEffectCalled = false
    val sideEffect: Throwable => Unit = _ => sideEffectCalled = true
    val in = SourceStream[Int]()
    val out = in.ignoreExceptions(sideEffect).map { n =>
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
    val in = SourceStream[Int]()
    val out = in.ignoreExceptions.map(_ * 2).map { n =>
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
    val in = SourceStream[Int]()
    val out: Stream[Int] = in.ignoreExceptions.map { n =>
      throw new RuntimeException("Should not be called")
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    // Don't send any events

    assertEquals(res, 0)
  }

  test("Multiple exceptions in sequence: map then filter") {
    val in = SourceStream[Int]()
    val out = in.ignoreExceptions.map { n =>
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

  test("DropWhile and ignore exception") {
    val in = SourceStream[Int]()
    val out = in.ignoreExceptions.dropWhile { n =>
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

  test("Scan and throw exception") {
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

  test("Scan and ignore exception") {
    val in = SourceStream[Int]()
    val out = in.ignoreExceptions.scan(0) { (acc, n) =>
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

  test("GroupBy and throw exception") {
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

  test("GroupBy and ignore exception") {
    val in = SourceStream[Int]()
    val out = in.ignoreExceptions.groupBy { n =>
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

  test("TakeWhile and ignore exception") {
    val in = SourceStream[Int]()
    val out = in.ignoreExceptions.takeWhile { n =>
      if (n == 2) throw new RuntimeException("TakeWhile and throw exception")
      n < 3
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1 // p(1)=true, !p(1)=false, dispatches 1
    in ! 2 // p(2) silently ignored
    in ! 3 // p(3)=false, !p(3)=true, closes the stream

    waitForResult(out, 1)
    assertEquals(res, 1)
    assert(out.isClosed)
  }

  // ============ FLATMAP tests ============

  test("FlatMap and throw exception") {
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

  test("FlatMap and ignore exception") {
    val in = SourceStream[Int]()
    val streamA = SourceStream[String]()
    val streamB = SourceStream[String]()
    val out = in.ignoreExceptions.flatMap { n =>
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
    assertEquals(res, "A")


    in ! 4 // change to streamB
    streamA ! "A"
    streamB ! "B"
    waitForResult(out, "B")
    assertEquals(res, "AB")
  }

  test("Close the stream at exception") {
    val in = SourceStream[Int]()
    val s1 = in.closeable
    val s2 = s1.ignoreExceptions { _ => s1.close() }
    val out = s2.map {
      case 2 => throw new RuntimeException("Map and throw exception")
      case n => n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    awaitAllTasks
    assertEquals(res, 1)
    in ! 2 // this should be ignored and the stream should be closed
    in ! 3
    awaitAllTasks
    assertEquals(res, 1)
    assert(s1.isClosed)
  }

  // ============ RECOVERWITH tests ============

  test("Map and recoverWith from matching exception") {
    val in = SourceStream[Int]()
    val out = in.recoverWith {
      case _: IllegalArgumentException => 42
    }.map {
      case 2 => throw new IllegalArgumentException("Map and throw exception")
      case n => n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2 // IllegalArgumentException is caught and recovered to 42
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 46) // 1 + 42 + 3
  }

  test("Map and recoverWith does not catch non-matching exception") {
    val in = SourceStream[Int]()
    val out = in.recoverWith {
      case _: IllegalArgumentException => 42
    }.map {
      case 2 => throw new RuntimeException("Map and throw exception")
      case n => n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    interceptMessage("Map and throw exception")(in ! 2) // RuntimeException not caught
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 4)
  }

  test("Map and recoverWith recovers with transformed value") {
    val in = SourceStream[Int]()
    val out = in.recoverWith {
      case e: IllegalArgumentException => e.getMessage.length
    }.map {
      case 2 => throw new IllegalArgumentException("recover me")
      case n => n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2 // recovered to "recover me".length = 10
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 14) // 1 + 10 + 3
  }

  // ============ IGNOREECEPTIONSWITH tests ============

  test("Map and ignoreExceptionsWith for matching exception") {
    val in = SourceStream[Int]()
    val out = in.ignoreExceptionsWith {
      case _: IllegalArgumentException =>
    }.map {
      case 2 => throw new IllegalArgumentException("Map and throw exception")
      case n => n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2 // IllegalArgumentException is ignored
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 4)
  }

  test("Map and ignoreExceptionsWith does not ignore non-matching exception") {
    val in = SourceStream[Int]()
    val out = in.ignoreExceptionsWith {
      case _: IllegalArgumentException =>
    }.map {
      case 2 => throw new RuntimeException("Map and throw exception")
      case n => n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    interceptMessage("Map and throw exception")(in ! 2) // RuntimeException not ignored
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 4)
  }

  test("Side effects are triggered on matching exception with ignoreExceptionsWith") {
    var sideEffectCalled = false
    val sideEffect: PartialFunction[Throwable, Unit] = {
      case _: IllegalArgumentException => sideEffectCalled = true
    }
    val in = SourceStream[Int]()
    val out = in.ignoreExceptionsWith(sideEffect).map { n =>
      if (n == 2) throw new IllegalArgumentException("Side effect test")
      n
    }

    out.foreach { _ => () }
    in ! 1
    in ! 2 // IllegalArgumentException triggers side effect
    in ! 3

    waitForResult(out, 3)
    assert(sideEffectCalled)
  }

  test("Side effects are NOT triggered on non-matching exception with ignoreExceptionsWith") {
    var sideEffectCalled = false
    val sideEffect: PartialFunction[Throwable, Unit] = {
      case _: IllegalArgumentException => sideEffectCalled = true
    }
    val in = SourceStream[Int]()
    val out = in.ignoreExceptionsWith(sideEffect).map { n =>
      if (n == 2) throw new RuntimeException("Side effect test")
      n
    }

    out.foreach { _ => () }
    in ! 1
    interceptMessage("Side effect test")(in ! 2) // RuntimeException not caught, no side effect

    assert(!sideEffectCalled)
  }

  test("Map with exception chained from another map with IGNOREWITH") {
    val in = SourceStream[Int]()
    val out = in.ignoreExceptionsWith {
      case _: IllegalArgumentException =>
    }.map(_ * 2).map { n =>
      if (n == 4) throw new IllegalArgumentException("Chained map test")
      n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2 // 2*2=4, IllegalArgumentException is ignored
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 8) // 2 + 6 (4 was ignored)
  }

  test("recoverWith with partial function handles only specific exceptions") {
    val in = SourceStream[Int]()
    val out = in.recoverWith {
      case _: IllegalArgumentException => -1
    }.map { n =>
      if (n == 1) throw new IllegalArgumentException("recover me")
      if (n == 2) throw new RuntimeException("do not recover")
      n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1 // recovered to -1
    interceptMessage("do not recover")(in ! 2) // RuntimeException propagates
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 2) // -1 + 3
  }

  test("ignoreExceptionsWith with multiple cases") {
    val in = SourceStream[Int]()
    var handledIllegalArg = false
    var handledIllegalState = false
    
    val out = in.ignoreExceptionsWith {
      case _: IllegalArgumentException => handledIllegalArg = true
      case _: IllegalStateException => handledIllegalState = true
    }.map { n =>
      if (n == 1) throw new IllegalArgumentException("arg error")
      if (n == 2) throw new IllegalStateException("state error")
      if (n == 3) throw new RuntimeException("other error")
      n
    }

    out.foreach { _ => () }
    in ! 1
    in ! 2
    interceptMessage("other error")(in ! 3)

    assert(handledIllegalArg)
    assert(handledIllegalState)
  }

  // ============ WITHDEFAULT tests ============

  test("Map with withDefault recovers to default value") {
    val in = SourceStream[Int]()
    val out = in.withDefault(42).map {
      case 2 => throw new RuntimeException("Map and throw exception")
      case n => n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2 // exception occurs, recovered to default value 42
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 46) // 1 + 42 + 3
  }

  test("withDefault on empty stream") {
    val in = SourceStream[Int]()
    val out = in.withDefault(100)

    var res = 0
    out.foreach { n =>
      res += n
    }
    // Don't send any events

    assertEquals(res, 0)
  }

  test("withDefault does not affect normal values") {
    val in = SourceStream[Int]()
    val out = in.withDefault(42).map { n =>
      n * 2
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 12) // 2 + 4 + 6
  }
}
