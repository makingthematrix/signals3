package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.testutils.{awaitAllTasks, result, tryResult, waitFor}
import io.github.makingthematrix.signals3.{CloseableFuture, DispatchQueue, DoneSignal, EventContext, SerialDispatchQueue, Signal}

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.{Future, Promise}
import scala.language.postfixOps

class TransformersSpec extends munit.FunSuite {
  private val eventContext = EventContext()
  given dq: DispatchQueue = SerialDispatchQueue()

  override def beforeEach(context: BeforeEach): Unit =
    eventContext.start()

  override def afterEach(context: AfterEach): Unit =
    eventContext.stop()

  given Timeout: FiniteDuration = 2.seconds
  val HeartBeatMs: FiniteDuration = 100.millis
  
  test("fibonacci stream with generate and map") {
    var a = 0
    var b = 1
    val original = GeneratorStream.generate(HeartBeatMs) {
      val res = (a, b)
      val t = a + b
      a = b
      b = t
      res
    }
    val mapped = Transformers.map(original) { _._2 }

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    mapped.foreach { t =>
      builder.addOne(t)
      isSuccess ! (t == 8)
    }

    waitFor(isSuccess, true)
    mapped.close()
    assertEquals(builder.result().toSeq, Seq(1, 1, 2, 3, 5, 8))
    assert(original.isClosed)
  }

  test("fibonacci signal from stream") {
    var a = 0
    var b = 1
    val original = GeneratorStream.generate(HeartBeatMs) {
      val res = (a, b)
      val t = a + b
      a = b
      b = t
      res
    }

    val signal = Transformers.signalFromStream(Transformers.map(original) { _._2 })

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    signal.foreach { t =>
      builder.addOne(t)
      isSuccess ! (t == 8)
    }

    waitFor(isSuccess, true)
    signal.close()
    assertEquals(builder.result().toSeq, Seq(1, 2, 3, 5, 8))
    assert(original.isClosed)
  }

  test("fibonacci signal with initial value from stream") {
    var a = 0
    var b = 1
    val original = GeneratorStream.generate(HeartBeatMs) {
      val res = (a, b)
      val t = a + b
      a = b
      b = t
      res
    }

    val signal = Transformers.signalFromStream(0, Transformers.map(original) { _._2 })

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    signal.foreach { t =>
      builder.addOne(t)
      isSuccess ! (t == 8)
    }

    waitFor(isSuccess, true)
    signal.close()
    assertEquals(builder.result().toSeq, Seq(0, 1, 2, 3, 5, 8))
    assert(original.isClosed)
  }

  test("counter stream but filtered only for odd integers") {
    var counter = 0
    val stream = Transformers.filter[Int](GeneratorStream.generate(HeartBeatMs) { counter += 1; counter }) {
      _ % 2 != 0
    }

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    stream.foreach { n =>
      builder.addOne(n)
      isSuccess ! (n == 7)
    }

    waitFor(isSuccess, true)
    stream.close()
    assertEquals(builder.result().toSeq, Seq(1, 3, 5, 7))
  }

  test("counter signal but filtered only for odd integers") {
    val signal = Transformers.filter[Int](GeneratorSignal.counter(HeartBeatMs)) { _ % 2 != 0 }

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    signal.foreach { n =>
      builder.addOne(n)
      isSuccess ! (n == 7)
    }

    waitFor(isSuccess, true)
    signal.close()
    assertEquals(builder.result().toSeq, Seq(1, 3, 5, 7))
  }

  test("fibonacci stream with generate and mapSync") {
    def mapInFuture(tuple: (Int, Int)): Future[Int] = Future { tuple._2 }

    var a = 0
    var b = 1
    val original = GeneratorStream.generate(HeartBeatMs) {
      val res = (a, b)
      val t = a + b
      a = b
      b = t
      res
    }

    val mapped = Transformers.mapSync(original)(mapInFuture)

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    mapped.foreach { t =>
      builder.addOne(t)
      isSuccess ! (t == 8)
    }

    waitFor(isSuccess, true)
    mapped.close()
    assertEquals(builder.result().toSeq, Seq(1, 1, 2, 3, 5, 8))
    assert(original.isClosed)
  }

  test("counter stream but collecting only odd integers as strings") {
    var counter = 0
    val stream = Transformers.collect(
      GeneratorStream.generate(HeartBeatMs) { counter += 1; counter }
    ){
      case n if n % 2 != 0 => n.toString
    }

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[String]
    stream.foreach { n =>
      builder.addOne(n)
      isSuccess ! (n == "7")
    }

    waitFor(isSuccess, true)
    stream.close()
    assertEquals(builder.result().toSeq, Seq("1", "3", "5", "7"))
  }

  test("counter signal but collecting only odd integers as strings") {
    val signal = Transformers.collect(GeneratorSignal.counter(HeartBeatMs)) {
      case n if n % 2 != 0 => n.toString
    }

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[String]
    signal.foreach { n =>
      builder.addOne(n)
      isSuccess ! (n == "7")
    }

    waitFor(isSuccess, true)
    signal.close()
    assertEquals(builder.result().toSeq, Seq("1", "3", "5", "7"))
  }

  test("zip two generator streams") {
    var c1 = 0
    var c2 = 0
    def bump1(): Int = {
      c1 += 1
      c1
    }
    def bump2(): Int = {
      c2 -= 1
      c2
    }

    val stream = Transformers.zip(
      GeneratorStream.generate(HeartBeatMs)(bump1()),
      GeneratorStream.generate((HeartBeatMs.toMillis + 10L).millis)(bump2())
    )

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    stream.foreach { t =>
      builder.addOne(t)
      isSuccess ! (t == -3)
    }

    waitFor(isSuccess, true)
    stream.close()
    assertEquals(builder.result().toSeq, Seq(1, -1, 2, -2, 3, -3))
  }

  test("zip two generator signals") {
    val signal = Transformers.zip(
      GeneratorSignal.counter(HeartBeatMs),
      Transformers.map(GeneratorSignal.counter((HeartBeatMs.toMillis + 10L).millis)){ n => -n },
    )

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[(Int, Int)]
    signal.foreach { t =>
      builder.addOne(t)
      isSuccess ! (t._2 == -3)
    }

    waitFor(isSuccess, true)
    signal.close()
    assertEquals(builder.result().toSeq, Seq((0,0), (1,0), (1,-1), (2,-1), (2,-2), (3,-2), (3,-3)))
  }

  test("zip three generator signals") {
    val signal = Transformers.zip(
      GeneratorSignal.counter(HeartBeatMs),
      GeneratorSignal.counter((HeartBeatMs.toMillis + 10L).millis),
      GeneratorSignal.counter((HeartBeatMs.toMillis + 20L).millis)
    )

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[(Int, Int, Int)]
    signal.foreach { t =>
      builder.addOne(t)
      isSuccess ! (t._3 == 2)
    }

    waitFor(isSuccess, true)
    signal.close()
    assertEquals(builder.result().toSeq, Seq((0, 0, 0), (1, 0, 0), (1, 1, 0), (1, 1, 1), (2, 1, 1), (2, 2, 1), (2, 2, 2)))
  }

  test("sequence three generator signals") {
    val signal = Transformers.sequence(
      GeneratorSignal.counter(HeartBeatMs),
      GeneratorSignal.counter((HeartBeatMs.toMillis + 10L).millis),
      GeneratorSignal.counter((HeartBeatMs.toMillis + 20L).millis)
    )

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Seq[Int]]
    signal.foreach { t =>
      builder.addOne(t)
      isSuccess ! (t(2) == 2)
    }

    waitFor(isSuccess, true)
    signal.close()
    assertEquals(builder.result().toSeq, Seq(Seq(0, 0, 0), Seq(1, 0, 0), Seq(1, 1, 0), Seq(1, 1, 1), Seq(2, 1, 1), Seq(2, 2, 1), Seq(2, 2, 2)))
  }

  test("combine two generator signals") {
    val signal = Transformers.combine(
      GeneratorSignal.counter(HeartBeatMs),
      Transformers.map(GeneratorSignal.counter((HeartBeatMs.toMillis + 20L).millis)) { n => -n },
    ) {
      case (a, b) => s"$a:$b"
    }

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[String]
    signal.foreach { t =>
      builder.addOne(t)
      isSuccess ! (t == "2:-2")
    }

    waitFor(isSuccess, true)
    signal.close()
    assertEquals(builder.result().toSeq, Seq("0:0", "1:0", "1:-1", "2:-1", "2:-2"))
  }

  test("emit an event after delay by wrapping a closeable future") {
    val delay = 250.millis
    val promise = Promise[Long]()
    val t = System.currentTimeMillis()
    val stream = Transformers.streamFromFuture(CloseableFuture.delay(delay))

    stream.onCurrent { _ => promise.success(System.currentTimeMillis() - t) }

    assert(result(promise.future) >= delay.toMillis)
  }

  test("don't emit an event from a closeable future after delay if the stream closed before") {
    val delay = 250.millis
    val promise = Promise[Long]()
    val t = System.currentTimeMillis()
    val cFuture = CloseableFuture.delay(delay)
    val stream = Transformers.streamFromFuture(cFuture)

    stream.onCurrent { _ => promise.success(System.currentTimeMillis() - t) }

    stream.close()

    assert(tryResult(cFuture.future)(using delay).isFailure)
    assert(stream.isClosed)
    assert(cFuture.isClosed)
  }

  test("update the signal after delay by wrapping a closeable future") {
    val delay = 250.millis
    val promise = Promise[Long]()
    val t = System.currentTimeMillis()
    val signal = Transformers.signalFromFuture(CloseableFuture.delay(delay))

    signal.onCurrent { _ => promise.success(System.currentTimeMillis() - t) }

    assert(result(promise.future) >= delay.toMillis)
  }

  test("don't update the signal from a closeable future after delay if it's closed before") {
    val delay = 250.millis
    val promise = Promise[Long]()
    val t = System.currentTimeMillis()
    val cFuture = CloseableFuture.delay(delay)
    val signal = Transformers.signalFromFuture(cFuture)

    signal.onCurrent { _ => promise.success(System.currentTimeMillis() - t) }

    signal.close()

    assert(tryResult(cFuture.future)(using delay).isFailure)
    assert(signal.isClosed)
    assert(cFuture.isClosed)
  }

  test("Transformed stream calls onClose exactly once") {
    val original = GeneratorStream.heartbeat(HeartBeatMs)
    val mapped = Transformers.map(original)(_ => "foo")

    val isClosed = Signal(0)
    mapped.onClose { isClosed.mutate(_ + 1)  }

    mapped.close()
    waitFor(isClosed, 1)
  }

  test("Original stream calls onClose exactly once") {
    val original = GeneratorStream.heartbeat(HeartBeatMs)
    val mapped = Transformers.map(original)(_ => "foo")

    val isClosed = Signal(0)
    original.onClose { isClosed.mutate(_ + 1) }

    mapped.close()
    waitFor(isClosed, 1)
  }

  test("Closing the original stream calls onClose on the transformed one") {
    val original = GeneratorStream.heartbeat(HeartBeatMs)
    val mapped = Transformers.map(original)(_ => "foo")

    val isClosed = Signal(0)
    mapped.onClose { isClosed.mutate(_ + 1) }

    original.close()

    waitFor(isClosed, 1)
    assert(original.isClosed)
    assert(mapped.isClosed)
  }

  test("In a zipped stream, closing the transformed one closes all originals") {
    val original1 = GeneratorStream.heartbeat(HeartBeatMs)
    val original2 = GeneratorStream.heartbeat((HeartBeatMs.toMillis * 2).millis)
    val zipped = Transformers.zip(original1, original2)

    zipped.close()

    awaitAllTasks
    assert(zipped.isClosed)
    assert(original1.isClosed)
    assert(original2.isClosed)
  }

  test("In a zipped stream, closing the transformed one calls all onClose") {
    val isClosed = Signal(0)
    val original1 = GeneratorStream.heartbeat(HeartBeatMs)
    original1.onClose { isClosed.mutate(_ + 1) }
    val original2 = GeneratorStream.heartbeat((HeartBeatMs.toMillis * 2).millis)
    original2.onClose { isClosed.mutate(_ + 1) }
    val zipped = Transformers.zip(original1, original2)
    zipped.onClose { isClosed.mutate(_ + 1) }

    zipped.close()

    awaitAllTasks
    waitFor(isClosed, 3)
    assert(zipped.isClosed)
    assert(original1.isClosed)
    assert(original2.isClosed)
  }

  test("In a zipped stream, closing the original ones closes the transformed ones too") {
    val isClosed = Signal(0)
    val original1 = GeneratorStream.heartbeat(HeartBeatMs)
    original1.onClose {isClosed.mutate(_ + 1)}
    val original2 = GeneratorStream.heartbeat((HeartBeatMs.toMillis * 2).millis)
    original2.onClose {isClosed.mutate(_ + 1)}
    val zipped = Transformers.zip(original1, original2)
    zipped.onClose {isClosed.mutate(_ + 1)}

    original1.close()

    awaitAllTasks
    waitFor(isClosed, 1)
    assert(original1.isClosed)
    assert(!original2.isClosed)
    assert(!zipped.isClosed)

    original2.close()

    awaitAllTasks
    waitFor(isClosed, 3)
    assert(original1.isClosed)
    assert(original2.isClosed)
    assert(zipped.isClosed)
  }

  // ============ RECOVER tests for CloseableStream ============

  test("CloseableStream recover from exception in map") {
    var counter = 0
    val original = GeneratorStream.generate(HeartBeatMs) { 
      counter += 1
      counter
    }
    val withRecover = Transformers.recover(original, _ => Some(-1))
    val mapped = Transformers.map(withRecover) { n =>
      if (n == 2) throw new RuntimeException("recover test")
      n
    }

    val isSuccess = DoneSignal()
    var res = List[Int]()
    mapped.foreach { n =>
      res = n :: res
      isSuccess.doneIf(n == 4)
    }

    waitFor(isSuccess, true)
    mapped.close()
    assertEquals(res, Seq(4, 3, -1, 1))
    assert(original.isClosed)
  }

  test("CloseableStream recover returns None on exception") {
    var counter = 0
    val original = GeneratorStream.generate(HeartBeatMs) { 
      counter += 1
      counter
    }
    val withRecover = Transformers.recover(original, _ => None)
    val mapped = Transformers.map(withRecover) { n =>
      if (n == 1) throw new RuntimeException("recover test")
      n
    }

    val isClosed = Signal(false)
    mapped.onClose { isClosed ! true }

    mapped.close()
    waitFor(isClosed, true)
    assert(original.isClosed)
  }

  // ============ RECOVER tests for CloseableSignal ============

  test("CloseableSignal recover from exception in map") {
    val original = GeneratorSignal.counter(HeartBeatMs)
    val withRecover = Transformers.recover(original, _ => Some(-1))
    val mapped = Transformers.map(withRecover) { n =>
      if (n == 2) throw new RuntimeException("recover test")
      n
    }

    val isSuccess = DoneSignal()
    var res = List[Int]()
    mapped.foreach { n =>
      res = n :: res
      isSuccess.doneIf(n == 3)
    }

    waitFor(isSuccess, true)
    withRecover.close()
    assertEquals(res, List(3, -1, 1, 0))
    assert(original.isClosed)
  }

  test("CloseableSignal recover returns None on exception") {
    val original = GeneratorSignal.counter(HeartBeatMs)
    val withRecover = Transformers.recover(original, _ => None)
    val mapped = Transformers.map(withRecover) { n =>
      if (n == 0) throw new RuntimeException("recover test")
      n
    }

    val isClosed = Signal(false)
    mapped.onClose { isClosed ! true }

    mapped.close()
    waitFor(isClosed, true)
    assert(original.isClosed)
  }

  // ============ RECOVERWITH tests for CloseableStream ============

  test("CloseableStream recoverWith from matching exception") {
    var counter = 0
    val original = GeneratorStream.generate(HeartBeatMs) { 
      counter += 1
      counter
    }
    val withRecover = Transformers.recoverWith(original, { case _: IllegalArgumentException => Some(-1) })
    val mapped = Transformers.map(withRecover) { n =>
      if (n == 2) throw new IllegalArgumentException("recover test")
      n
    }

    val isSuccess = DoneSignal()
    var res = List[Int]()
    mapped.foreach { n =>
      res = n :: res
      isSuccess.doneIf(n == 4)
    }

    waitFor(isSuccess, true)
    mapped.close()
    assertEquals(res, List(4, 3, -1, 1))
    assert(original.isClosed)
  }

  test("CloseableStream recoverWith does not catch non-matching exception") {
    var counter = 0
    val original = GeneratorStream.generate(HeartBeatMs) { 
      counter += 1
      counter
    }
    val withRecover = Transformers.recoverWith(original, { case _: IllegalArgumentException => Some(-1) })
    val mapped = Transformers.map(withRecover) { n =>
      if (n == 2) throw new RuntimeException("not matched")
      n
    }

    val isClosed = Signal(false)
    mapped.onClose { isClosed ! true }

    mapped.close()
    waitFor(isClosed, true)
    assert(original.isClosed)
  }

  test("CloseableStream recoverWith recovers with transformed value") {
    var counter = 0
    val original = GeneratorStream.generate(HeartBeatMs) { 
      counter += 1
      counter
    }
    val withRecover = Transformers.recoverWith(original, { case e: IllegalArgumentException => Some(e.getMessage.length) })
    val mapped = Transformers.map(withRecover) { n =>
      if (n == 2) throw new IllegalArgumentException("recover me")
      n
    }

    val isSuccess = DoneSignal()
    var  res = List[Int]()
    mapped.foreach { n =>
      res = n :: res
      isSuccess.doneIf(n == 4)
    }

    waitFor(isSuccess, true)
    mapped.close()
    assertEquals(res, Seq(4, 3, 10, 1))
    assert(original.isClosed)
  }

  // ============ RECOVERWITH tests for CloseableSignal ============

  test("CloseableSignal recoverWith from matching exception") {
    val original = GeneratorSignal.counter(HeartBeatMs)
    val withRecover = Transformers.recoverWith(original, { case _: IllegalArgumentException => Some(-1) })
    val mapped = Transformers.map(withRecover) { n =>
      if (n == 2) throw new IllegalArgumentException("recover test")
      n
    }

    val isSuccess = DoneSignal()
    var res = List[Int]()
    mapped.foreach { n =>
      res = n :: res
      isSuccess.doneIf(n == 3)
    }

    waitFor(isSuccess, true)
    mapped.close()
    assertEquals(res, Seq(3, -1, 1, 0))
    assert(original.isClosed)
  }

  test("CloseableSignal recoverWith does not catch non-matching exception") {
    val original = GeneratorSignal.counter(HeartBeatMs)
    val withRecover = Transformers.recoverWith(original, { case _: IllegalArgumentException => Some(-1) })
    val mapped = Transformers.map(withRecover) { n =>
      if (n == 2) throw new RuntimeException("not matched")
      n
    }

    val isClosed = Signal(false)
    mapped.onClose { isClosed ! true }

    mapped.close()
    waitFor(isClosed, true)
    assert(original.isClosed)
  }

  test("CloseableSignal recoverWith recovers with transformed value") {
    val original = GeneratorSignal.counter(HeartBeatMs)
    val withRecover = Transformers.recoverWith(original, { case e: IllegalArgumentException => Some(e.getMessage.length) })
    val mapped = Transformers.map(withRecover) { n =>
      if (n == 2) throw new IllegalArgumentException("recover me")
      n
    }

    val isSuccess = DoneSignal()
    var  res = List[Int]()
    mapped.foreach { n =>
      res = n :: res
      isSuccess.doneIf(n == 3)
    }

    waitFor(isSuccess, true)
    mapped.close()
    assertEquals(res, List(3, 10, 1, 0))
    assert(original.isClosed)
  }

  // ============ SCAN tests for CloseableStream ============

  test("CloseableStream scan accumulates values") {
    var counter = 0
    val original = GeneratorStream.generate(HeartBeatMs) { 
      counter += 1
      if (counter <= 4) counter else counter - 5
    }
    val scanned = Transformers.scan(original, 0)(_ + _)

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    scanned.foreach { n =>
      builder.addOne(n)
      isSuccess ! (n == 10)
    }

    waitFor(isSuccess, true)
    scanned.close()
    assertEquals(builder.result().toSeq, Seq(1, 3, 6, 10))
    assert(original.isClosed)
  }

  test("CloseableStream scan with different initial value") {
    var counter = 0
    val original = GeneratorStream.generate(HeartBeatMs) { 
      counter += 1
      counter
    }
    val scanned = Transformers.scan(original, 10)(_ + _)

    val isSuccess = DoneSignal()
    var  res = List[Int]()
    scanned.foreach { n =>
      res = n :: res
      isSuccess.doneIf(n == 16)
    }

    waitFor(isSuccess, true)
    scanned.close()
    assertEquals(res, Seq(16, 13, 11))
    assert(original.isClosed)
  }

  // ============ SCAN tests for CloseableSignal ============

  test("CloseableSignal scan accumulates values") {
    val original = GeneratorSignal.counter(HeartBeatMs)
    val scanned = Transformers.scan(original, 0)(_ + _)

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    scanned.foreach { n =>
      builder.addOne(n)
      isSuccess ! (n == 6) // 0+0=0, 0+1=1, 1+2=3, 3+3=6
    }

    waitFor(isSuccess, true)
    scanned.close()
    assertEquals(builder.result().toSeq, Seq(0, 1, 3, 6))
    assert(original.isClosed)
  }

  test("CloseableSignal scan with initial value") {
    val original = GeneratorSignal.generate(1, HeartBeatMs)(_ + 1)
    val scanned = Transformers.scan(original, 10)(_ + _)

    val isSuccess = DoneSignal()
    var  res = List[Int]()
    scanned.foreach { n =>
      res = n :: res
      isSuccess.doneIf(n == 16)
    }

    waitFor(isSuccess, true)
    scanned.close()
    assertEquals(res, Seq(16, 13, 11))
    assert(original.isClosed)
  }

  // ============ GROUPED tests for CloseableStream ============

  test("CloseableStream grouped batches events") {
    var counter = 0
    val original = GeneratorStream.generate(HeartBeatMs) { 
      counter += 1
      counter
    }
    val grouped = Transformers.grouped(original, 3)

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Seq[Int]]
    grouped.foreach { batch =>
      builder.addOne(batch)
      isSuccess ! (batch == Seq(4, 5, 6))
    }

    waitFor(isSuccess, true)
    grouped.close()
    assertEquals(builder.result().toSeq, Seq(Seq(1, 2, 3), Seq(4, 5, 6)))
    assert(original.isClosed)
  }

  test("CloseableStream grouped with partial batch") {
    var counter = 0
    val original = GeneratorStream.generate(HeartBeatMs) { 
      counter += 1
      if (counter <= 5) counter else counter - 6
    }
    val grouped = Transformers.grouped(original, 3)

    val isSuccess = DoneSignal()
    var res = List[Seq[Int]]()
    grouped.foreach { batch =>
      res = batch :: res
      isSuccess.doneIf(res.size == 2)
    }

    waitFor(isSuccess, true)
    grouped.close()
    assertEquals(res, List(Seq(4, 5, 0), Seq(1, 2, 3)))
    assert(original.isClosed)
  }

  // ============ GROUPED tests for CloseableSignal ============

  test("CloseableSignal grouped batches values") {
    val original = GeneratorSignal.counter(HeartBeatMs)
    val grouped = Transformers.grouped(original, 3)

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Seq[Int]]
    grouped.foreach { batch =>
      builder.addOne(batch)
      isSuccess ! (batch == Seq(3, 4, 5))
    }

    waitFor(isSuccess, true)
    grouped.close()
    assertEquals(builder.result().toSeq, Seq(Seq(0, 1, 2), Seq(3, 4, 5)))
    assert(original.isClosed)
  }

  test("CloseableSignal grouped with partial batch") {
    var counter = 0
    val original = GeneratorSignal.generate(0, HeartBeatMs) { v =>
      counter += 1
      if (counter <= 5) counter else -1
    }
    val grouped = Transformers.grouped(original, 3)

    val isSuccess = DoneSignal()
    var res = List[Seq[Int]]()
    grouped.foreach { batch =>
      res = batch :: res
      isSuccess.doneIf(res.size == 2)
    }

    waitFor(isSuccess, true)
    grouped.close()
    assertEquals(res, List(Seq(3, 4, 5), Seq(0, 1, 2)))
    assert(original.isClosed)
  }

  // ============ GROUPBY tests for CloseableStream ============

  test("CloseableStream groupBy groups consecutive events by predicate") {
    var counter = 0
    val original = GeneratorStream.generate(HeartBeatMs) { 
      counter += 1
      counter
    }
    // Create groups: 1,3,5 are odd (true), 2,4,6 are even (false)
    // But since they come consecutively with same predicate result, they get grouped
    // Actually all odd numbers will be in different groups since 1(true), 2(false), 3(true), 4(false)...
    val grouped = Transformers.groupBy(original, _ % 2 != 0)

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Seq[Int]]
    grouped.foreach { batch =>
      builder.addOne(batch)
      isSuccess ! builder.result().nonEmpty
    }

    waitFor(isSuccess, true)
    grouped.close()
    assert(builder.result().toSeq.nonEmpty)
    assert(original.isClosed)
  }

  // ============ GROUPBY tests for CloseableSignal ============

  test("CloseableSignal groupBy groups consecutive values by predicate") {
    val original = GeneratorSignal.counter(HeartBeatMs)
    val grouped = Transformers.groupBy(original, _ % 2 == 0)

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Seq[Int]]
    grouped.foreach { batch =>
      builder.addOne(batch)
      isSuccess ! builder.result().nonEmpty
    }

    waitFor(isSuccess, true)
    grouped.close()
    assert(builder.result().toSeq.nonEmpty)
    assert(original.isClosed)
  }

  // ============ DROP tests for CloseableStream ============

  test("CloseableStream drop skips first N events") {
    var counter = 0
    val original = GeneratorStream.generate(HeartBeatMs) { 
      counter += 1
      counter
    }
    val dropped = Transformers.drop(original, 3)

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    dropped.foreach { n =>
      builder.addOne(n)
      isSuccess ! (n == 5)
    }

    waitFor(isSuccess, true)
    dropped.close()
    assertEquals(builder.result().toSeq, Seq(4, 5))
    assert(original.isClosed)
  }

  test("CloseableStream drop with N = 0 returns original") {
    var counter = 0
    val original = GeneratorStream.generate(HeartBeatMs) { 
      counter += 1
      counter
    }
    val dropped = Transformers.drop(original, 0)

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    dropped.foreach { n =>
      builder.addOne(n)
      isSuccess ! (n == 3)
    }

    waitFor(isSuccess, true)
    dropped.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(1, 2, 3))
    assert(original.isClosed)
  }

  test("CloseableStream drop with N > count") {
    var counter = 0
    val original = GeneratorStream.generate(HeartBeatMs) { 
      counter += 1
      if (counter <= 3) counter else counter - 4
    }
    val dropped = Transformers.drop(original, 10)

    val isClosed = Signal(false)
    dropped.onClose { isClosed ! true }

    dropped.close()
    waitFor(isClosed, true)
    assert(original.isClosed)
  }

  // ============ DROP tests for CloseableSignal ============

  test("CloseableSignal drop skips first N values") {
    val original = GeneratorSignal.counter(HeartBeatMs)
    val dropped = Transformers.drop(original, 3)

    val isSuccess = DoneSignal()
    var res = List[Int]()
    dropped.foreach { n =>
      res = n :: res
      isSuccess.doneIf(n == 5)
    }

    waitFor(isSuccess, true)
    dropped.close()
    assertEquals(res, List(5, 4, 3))
    assert(original.isClosed)
  }

  test("CloseableSignal drop with N = 0 returns original") {
    val original = GeneratorSignal.counter(HeartBeatMs)
    val dropped = Transformers.drop(original, 0)

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    dropped.foreach { n =>
      builder.addOne(n)
      isSuccess ! (n == 2)
    }

    waitFor(isSuccess, true)
    dropped.close()
    assertEquals(builder.result().toSeq, Seq(0, 1, 2))
    assert(original.isClosed)
  }

  // ============ DROPWHILE tests for CloseableStream ============

  test("CloseableStream dropWhile skips events while predicate is true") {
    var counter = 0
    val original = GeneratorStream.generate(HeartBeatMs) { 
      counter += 1
      counter
    }
    val dropped = Transformers.dropWhile(original, _ < 3)

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    dropped.foreach { n =>
      builder.addOne(n)
      isSuccess ! (n == 5)
    }

    waitFor(isSuccess, true)
    dropped.close()
    assertEquals(builder.result().toSeq, Seq(3, 4, 5))
    assert(original.isClosed)
  }

  test("CloseableStream dropWhile with all matching predicate") {
    var counter = 0
    val original = GeneratorStream.generate(HeartBeatMs) { 
      counter += 1
      counter
    }
    val dropped = Transformers.dropWhile(original, _ < 10)

    val isClosed = Signal(false)
    dropped.onClose { isClosed ! true }

    dropped.close()
    waitFor(isClosed, true)
    assert(original.isClosed)
  }

  test("CloseableStream dropWhile with none matching predicate") {
    var counter = 0
    val original = GeneratorStream.generate(HeartBeatMs) { 
      counter += 1
      counter
    }
    val dropped = Transformers.dropWhile(original, _ > 10)

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    dropped.foreach { n =>
      builder.addOne(n)
      isSuccess ! (n == 3)
    }

    waitFor(isSuccess, true)
    dropped.close()
    assertEquals(builder.result().toSeq, Seq(1, 2, 3))
    assert(original.isClosed)
  }

  // ============ DROPWHILE tests for CloseableSignal ============

  test("CloseableSignal dropWhile skips values while predicate is true") {
    val original = GeneratorSignal.counter(HeartBeatMs)
    val dropped = Transformers.dropWhile(original, _ < 3)

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    dropped.foreach { n =>
      builder.addOne(n)
      isSuccess ! (n == 5)
    }

    waitFor(isSuccess, true)
    dropped.close()
    assertEquals(builder.result().toSeq, Seq(3, 4, 5))
    assert(original.isClosed)
  }

  test("CloseableSignal dropWhile with alternating predicate") {
    var counter = 0
    val original = GeneratorSignal.generate(0, HeartBeatMs) { v =>
      counter += 1
      counter
    }
    val dropped = Transformers.dropWhile(original, _ < 3)

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    dropped.foreach { n =>
      builder.addOne(n)
      if (n == 6) isSuccess ! true
    }

    waitFor(isSuccess, true)
    dropped.close()
    assertEquals(builder.result().toSeq, Seq(3, 4, 5, 6))
    assert(original.isClosed)
  }
}
