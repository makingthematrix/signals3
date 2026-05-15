package io.github.makingthematrix.signals3

import testutils.*

class RecoverSignalSpec extends munit.FunSuite {
  private val eventContext = EventContext()

  given ec: DispatchQueue = SerialDispatchQueue()

  override def beforeEach(context: BeforeEach): Unit =
    eventContext.start()

  override def afterEach(context: AfterEach): Unit =
    eventContext.stop()

  // ============ MAP tests ============

  test("Map and throw exception") {
    val in = SourceSignal[Int]()
    val out = in.map {
      case 2 => throw new RuntimeException("Map and throw exception")
      case n => n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in !! 1
    interceptMessage("Map and throw exception")(in !! 2)
    in !! 3

    waitForResult(out, 3)
    assertEquals(res, 4)
  }

  test("Map and ignore exception") {
    val in = SourceSignal[Int]()
    val out = in.ignoreExceptions.map {
      case 2 => throw new RuntimeException("Map and ignore exception")
      case n => n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }

    in !! 1
    in !! 2 // should be ignored
    in !! 3

    waitForResult(out, 3)
    assertEquals(res, 4)
  }

  // ============ COLLECT tests ============

  test("Collect and throw exception") {
    val in = SourceSignal[Int]()
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
    val in = SourceSignal[Int]()
    val out = in.ignoreExceptions.collect {
      case 2 => throw new RuntimeException("Collect and throw exception")
      case n if n > 0 => n * 10
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 40)
  }

  // ============ FILTER tests ============

  test("Filter and throw exception") {
    val in = SourceSignal[Int]()
    val out = in.filter { n =>
      if (n == 2) throw new RuntimeException("Filter and throw exception")
      n % 2 == 0
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in !! 1
    interceptMessage("Filter and throw exception")(in !! 2)
    in !! 4

    waitForResult(out, 4)
    assertEquals(res, 4)
  }

  test("Filter and ignore exception") {
    val in = SourceSignal[Int]()
    val out = in.ignoreExceptions.filter { n =>
      if (n == 2) throw new RuntimeException("Filter and throw exception")
      n % 2 == 0
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in !! 1
    in !! 2
    in !! 4

    waitForResult(out, 4)
    assertEquals(res, 4)
  }

  // ============ DROPWHILE tests ============

  test("DropWhile and throw exception") {
    val in = SourceSignal[Int]()
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

    assert(res == 0)
  }

  test("DropWhile and ignore exception") {
    val in = SourceSignal[Int]()
    val out = in.ignoreExceptions.dropWhile { n =>
      if (n == 2) throw new RuntimeException("DropWhile and throw exception")
      n < 3
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in !! 1
    in !! 2
    in !! 4

    waitForResult(out, 4)
    assertEquals(res, 4)
  }

  // ============ Side effects tests ============

  test("Side effects are triggered on exception") {
    var sideEffectCalled = false
    val sideEffect: Throwable => Unit = _ => sideEffectCalled = true
    val in = SourceSignal[Int]()
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
    val in = SourceSignal[Int]()
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
    assertEquals(res, 8)
  }

  // ============ Edge cases ============

  test("Empty signal with IGNORE strategy") {
    val in = SourceSignal[Int]()
    val out: Signal[Int] = in.ignoreExceptions.map { n =>
      throw new RuntimeException("Should not be called")
    }

    var res = 0
    out.foreach { n =>
      res += n
    }

    assertEquals(res, 0)
  }

  test("Multiple exceptions in sequence: map then filter") {
    val in = SourceSignal[Int]()
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

  // ============ SCAN tests ============

  test("Scan and throw exception") {
    val in = SourceSignal[Int]()
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
    val in = SourceSignal[Int]()
    val out = in.ignoreExceptions.scan(0) { (acc, n) =>
      if (n == 2) throw new RuntimeException("Scan and throw exception")
      acc + n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in !! 1
    in !! 2
    in !! 3

    waitForResult(out, 4)
    assertEquals(res, 5)
  }

  // ============ GROUPBY tests ============

  test("GroupBy and throw exception") {
    val in = SourceSignal[Int]()
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
    val in = SourceSignal[Int]()
    val out = in.ignoreExceptions.groupBy { n =>
      if (n == 2) throw new RuntimeException("GroupBy and throw exception")
      n % 2 == 0
    }

    var res = Seq.empty[Seq[Int]]
    out.foreach { n =>
      res = res :+ n
    }
    in ! 1
    in ! 2
    in ! 3
    in ! 4

    waitForResult(out, Seq(4))
    assertEquals(res, Seq(Seq(1, 3)))
  }

  // ============ TAKEWHILE tests ============

  test("TakeWhile and ignore exception") {
    val in = SourceSignal[Int]()
    val out = in.ignoreExceptions.takeWhile { n =>
      if (n == 2) throw new RuntimeException("TakeWhile and throw exception")
      n < 3
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2
    in ! 3

    waitForResult(out, 1)
    assertEquals(res, 1)
    assert(out.isClosed)
  }

  // ============ FLATMAP tests ============

  test("FlatMap and throw exception") {
    val in = SourceSignal[Int]()
    val signalA = SourceSignal[String]()
    val signalB = SourceSignal[String]()
    val out = in.flatMap { n =>
      if (n == 2) throw new RuntimeException("FlatMap and throw exception")
      if (n % 2 != 0) signalA else signalB
    }

    var res = ""
    out.foreach { n =>
      res += n
    }
    in ! 1
    signalA ! "A"
    signalB ! "B"
    waitForResult(out, "A")
    assertEquals(res, "A")
    interceptMessage("FlatMap and throw exception")(in ! 2)
  }

  test("FlatMap and ignore exception") {
    given dq: DispatchQueue = SerialDispatchQueue()
    val in = SourceSignal[Int]()
    val signalA = SourceSignal[String]()
    val signalB = SourceSignal[String]()
    val out = in.ignoreExceptions.flatMap { n =>
      if (n == 2) throw new RuntimeException("FlatMap and throw exception")
      if (n % 2 != 0) signalA else signalB
    }

    var res = ""
    out.foreach { n =>
      res += n
    }
    in ! 1
    signalA ! "A"
    signalB ! "B"
    waitForResult(out, "A")
    assertEquals(res, "A")

    in ! 2
    signalA ! "A"
    signalB ! "B"
    waitForResult(out, "A")
    assertEquals(res, "A")

    in ! 4
    signalA ! "A"
    signalB ! "B"
    waitForResult(out, "B")
    assertEquals(res, "AB")
  }

  test("Close the signal at exception") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val in = SourceSignal[Int]()
    val s1 = in.closeable
    val s2 = s1.ignoreExceptions { _ =>
      s1.close()
    }
    val out = s2.map {
      case 2 => throw new RuntimeException("Map and throw exception")
      case n => n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    waitForResult(out, 1)
    assertEquals(res, 1)
    assert(!s1.isClosed)
    in ! 2
    awaitAllTasks
    assert(s1.isClosed)
    in ! 3
    awaitAllTasks
    assertEquals(res, 1)
    assert(s1.isClosed)
  }

  // ============ RECOVER tests ============

  test("Map and recover from exception") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val in = SourceSignal[Int]()
    val out = in.recover { t =>
      42
    }.map {
      case 2 => throw new RuntimeException("Map and throw exception")
      case n => n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    waitForResult(out, 1)
    assertEquals(res, 1)
    in ! 2 // exception is caught and recovered to 42
    waitForResult(out, 42)
    awaitAllTasks
    assertEquals(res, 43)
    in ! 3
    waitForResult(out, 3)
    awaitAllTasks
    assertEquals(res, 46)
  }

  // ============ RECOVERWITH tests ============

  test("Map and recoverWith from matching exception") {
    val in = SourceSignal[Int]()
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
    in ! 2
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 46)
  }

  test("Map and recoverWith does not catch non-matching exception") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val in = SourceSignal[Int]()
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
    interceptMessage("Map and throw exception")(in ! 2)
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 4)
  }

  test("Map and recoverWith recovers with transformed value") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val in = SourceSignal[Int]()
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
    in ! 2
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 14)
  }

  // ============ IGNOREEXCEPTIONSWITH tests ============

  test("Map and ignoreExceptionsWith for matching exception") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val in = SourceSignal[Int]()
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
    in ! 2
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 4)
  }

  test("Map and ignoreExceptionsWith does not ignore non-matching exception") {
    val in = SourceSignal[Int]()
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
    interceptMessage("Map and throw exception")(in ! 2)
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 4)
  }

  test("Side effects are triggered on matching exception with ignoreExceptionsWith") {
    var sideEffectCalled = false
    val sideEffect: PartialFunction[Throwable, Unit] = {
      case _: IllegalArgumentException => sideEffectCalled = true
    }
    val in = SourceSignal[Int]()
    val out = in.ignoreExceptionsWith(sideEffect).map { n =>
      if (n == 2) throw new IllegalArgumentException("Side effect test")
      n
    }

    out.foreach { _ => () }
    in ! 1
    in ! 2
    in ! 3

    waitForResult(out, 3)
    assert(sideEffectCalled)
  }

  test("Side effects are NOT triggered on non-matching exception with ignoreExceptionsWith") {
    var sideEffectCalled = false
    val sideEffect: PartialFunction[Throwable, Unit] = {
      case _: IllegalArgumentException => sideEffectCalled = true
    }
    val in = SourceSignal[Int]()
    val out = in.ignoreExceptionsWith(sideEffect).map { n =>
      if (n == 2) throw new RuntimeException("Side effect test")
      n
    }

    out.foreach { _ => () }
    in ! 1
    interceptMessage("Side effect test")(in ! 2)

    assert(!sideEffectCalled)
  }

  test("Map with exception chained from another map with IGNOREWITH") {
    val in = SourceSignal[Int]()
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
    in ! 2
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 8)
  }

  test("recoverWith with partial function handles only specific exceptions") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val in = SourceSignal[Int]()
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
    in ! 1
    interceptMessage("do not recover")(in ! 2)
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 2)
  }

  test("ignoreExceptionsWith with multiple cases") {
    var handledIllegalArg = false
    var handledIllegalState = false
    val in = SourceSignal[Int]()
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
    given dq: DispatchQueue = SerialDispatchQueue()

    val in = SourceSignal[Int]()
    val out = in.withDefault(42).map {
      case 2 => throw new RuntimeException("Map and throw exception")
      case n => n
    }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 46)
  }

  test("withDefault on empty signal") {
    val in = SourceSignal[Int]()
    val s = in.withDefault(100)

    var res = 0
    s.foreach { n =>
      res += n
    }

    assertEquals(res, 0)
  }

  test("withDefault does not affect normal values") {
    val in = SourceSignal[Int]()
    val out = in.withDefault(42).map { n => n * 2 }

    var res = 0
    out.foreach { n =>
      res += n
    }
    in ! 1
    in ! 2
    in ! 3

    waitForResult(out, 3)
    assertEquals(res, 12)
  }
}
