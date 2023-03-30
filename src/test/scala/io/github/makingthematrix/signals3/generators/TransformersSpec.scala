package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.testutils.{awaitAllTasks, result, tryResult, waitForResult}
import io.github.makingthematrix.signals3.{CloseableFuture, EventContext, Signal, Threading}

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.{Future, Promise}
import scala.language.postfixOps

class TransformersSpec extends munit.FunSuite:
  import EventContext.Implicits.global
  import Threading.defaultContext

  test("fibonacci stream with generate and map") {
    var a = 0
    var b = 1
    val original = GeneratorStream.generate(200.millis) {
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

    waitForResult(isSuccess, true)
    mapped.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(1, 1, 2, 3, 5, 8))
    assert(original.isClosed)
  }

  test("fibonacci signal from stream") {
    var a = 0
    var b = 1
    val original = GeneratorStream.generate(200.millis) {
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

    waitForResult(isSuccess, true)
    signal.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(1, 2, 3, 5, 8))
    assert(original.isClosed)
  }

  test("fibonacci signal with initial value from stream") {
    var a = 0
    var b = 1
    val original = GeneratorStream.generate(200.millis) {
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

    waitForResult(isSuccess, true)
    signal.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(0, 1, 2, 3, 5, 8))
    assert(original.isClosed)
  }

  test("counter stream but filtered only for odd integers") {
    var counter = 0
    val stream = Transformers.filter[Int](GeneratorStream.generate(200.millis) { counter += 1; counter }) {
      _ % 2 != 0
    }

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    stream.foreach { n =>
      builder.addOne(n)
      isSuccess ! (n == 7)
    }

    waitForResult(isSuccess, true)
    stream.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(1, 3, 5, 7))
  }

  test("counter signal but filtered only for odd integers") {
    val signal = Transformers.filter[Int](GeneratorSignal.counter(200.millis)) { _ % 2 != 0 }

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    signal.foreach { n =>
      builder.addOne(n)
      isSuccess ! (n == 7)
    }

    waitForResult(isSuccess, true)
    signal.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(1, 3, 5, 7))
  }

  test("fibonacci stream with generate and mapSync") {
    def mapInFuture(tuple: (Int, Int)): Future[Int] = Future { tuple._2 }

    var a = 0
    var b = 1
    val original = GeneratorStream.generate(200.millis) {
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

    waitForResult(isSuccess, true)
    mapped.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(1, 1, 2, 3, 5, 8))
    assert(original.isClosed)
  }

  test("counter stream but collecting only odd integers as strings") {
    var counter = 0
    val stream = Transformers.collect(
      GeneratorStream.generate(200.millis) { counter += 1; counter }
    ){
      case n if n % 2 != 0 => n.toString
    }

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[String]
    stream.foreach { n =>
      builder.addOne(n)
      isSuccess ! (n == "7")
    }

    waitForResult(isSuccess, true)
    stream.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq("1", "3", "5", "7"))
  }

  test("counter signal but collecting only odd integers as strings") {
    val signal = Transformers.collect(GeneratorSignal.counter(200.millis)) {
      case n if n % 2 != 0 => n.toString
    }

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[String]
    signal.foreach { n =>
      builder.addOne(n)
      isSuccess ! (n == "7")
    }

    waitForResult(isSuccess, true)
    signal.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq("1", "3", "5", "7"))
  }

  test("zip two generator streams") {
    var c1 = 0
    var c2 = 0
    def bump1(): Int =
      c1 += 1
      c1
    def bump2(): Int =
      c2 -= 1
      c2

    val stream = Transformers.zip(
      GeneratorStream.generate(200.millis)(bump1()),
      GeneratorStream.generate(220.millis)(bump2())
    )

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Int]
    stream.foreach { t =>
      builder.addOne(t)
      isSuccess ! (t == -3)
    }

    waitForResult(isSuccess, true)
    stream.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(1, -1, 2, -2, 3, -3))
  }

  test("zip two generator signals") {
    val signal = Transformers.zip(
      GeneratorSignal.counter(200.millis),
      Transformers.map(GeneratorSignal.counter(210.millis)){ n => -n },
    )

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[(Int, Int)]
    signal.foreach { t =>
      builder.addOne(t)
      isSuccess ! (t._2 == -3)
    }

    waitForResult(isSuccess, true)
    signal.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq((0,0), (1,0), (1,-1), (2,-1), (2,-2), (3,-2), (3,-3)))
  }

  test("zip three generator signals") {
    val signal = Transformers.zip(
      GeneratorSignal.counter(200.millis),
      GeneratorSignal.counter(210.millis),
      GeneratorSignal.counter(220.millis)
    )

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[(Int, Int, Int)]
    signal.foreach { t =>
      builder.addOne(t)
      isSuccess ! (t._3 == 2)
    }

    waitForResult(isSuccess, true)
    signal.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq((0, 0, 0), (1, 0, 0), (1, 1, 0), (1, 1, 1), (2, 1, 1), (2, 2, 1), (2, 2, 2)))
  }

  test("zip four generator signals") {
    val signal = Transformers.zip(
      GeneratorSignal.counter(200.millis),
      Transformers.map(GeneratorSignal.counter(210.millis)){ n => -n.toDouble },
      GeneratorSignal.counter(220.millis),
      Transformers.map(GeneratorSignal.counter(230.millis)){ n => -n.toDouble },
    )

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[(Int, Double, Int, Double)]
    signal.foreach { t =>
      builder.addOne(t)
      isSuccess ! (t._4 == -1.0)
    }

    waitForResult(isSuccess, true)
    signal.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq((0, 0.0, 0, 0.0), (1, 0.0, 0, 0.0), (1, -1.0, 0, 0.0), (1, -1.0, 1, 0.0), (1, -1.0, 1, -1.0)))
  }

  test("zip five generator signals") {
    val signal = Transformers.zip(
      GeneratorSignal.counter(200.millis),
      Transformers.map(GeneratorSignal.counter(210.millis)) { n => -n.toDouble },
      GeneratorSignal.counter(220.millis),
      Transformers.map(GeneratorSignal.counter(230.millis)) { n => -n.toDouble },
      Transformers.map(GeneratorSignal.counter(240.millis)) { _.toString },
    )

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[(Int, Double, Int, Double, String)]
    signal.foreach { t =>
      builder.addOne(t)
      isSuccess ! (t._5 == "1")
    }

    waitForResult(isSuccess, true)
    signal.close()
    awaitAllTasks
    assertEquals(
      builder.result().toSeq,
      Seq((0, 0.0, 0, 0.0, "0"), (1, 0.0, 0, 0.0, "0"), (1, -1.0, 0, 0.0, "0"), (1, -1.0, 1, 0.0, "0"), (1, -1.0, 1, -1.0, "0"), (1, -1.0, 1, -1.0, "1"))
    )
  }

  test("zip six generator signals") {
    val signal = Transformers.zip(
      GeneratorSignal.counter(200.millis),
      Transformers.map(GeneratorSignal.counter(210.millis)) { n => -n.toDouble },
      GeneratorSignal.counter(220.millis),
      Transformers.map(GeneratorSignal.counter(230.millis)) { n => -n.toDouble },
      Transformers.map(GeneratorSignal.counter(240.millis)) { _.toString },
      Transformers.map(GeneratorSignal.counter(250.millis)) { n => (-n).toString },
    )

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[(Int, Double, Int, Double, String, String)]
    signal.foreach { t =>
      builder.addOne(t)
      isSuccess ! (t._6 == "-1")
    }

    waitForResult(isSuccess, true)
    signal.close()
    awaitAllTasks
    assertEquals(
      builder.result().toSeq,
      Seq((0, 0.0, 0, 0.0, "0", "0"), (1, 0.0, 0, 0.0, "0", "0"), (1, -1.0, 0, 0.0, "0", "0"),
          (1, -1.0, 1, 0.0, "0", "0"), (1, -1.0, 1, -1.0, "0", "0"), (1, -1.0, 1, -1.0, "1", "0"),
          (1, -1.0, 1, -1.0, "1", "-1"))
    )
  }

  test("sequence three generator signals") {
    val signal = Transformers.sequence(
      GeneratorSignal.counter(200.millis),
      GeneratorSignal.counter(210.millis),
      GeneratorSignal.counter(220.millis)
    )

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[Seq[Int]]
    signal.foreach { t =>
      builder.addOne(t)
      isSuccess ! (t(2) == 2)
    }

    waitForResult(isSuccess, true)
    signal.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq(Seq(0, 0, 0), Seq(1, 0, 0), Seq(1, 1, 0), Seq(1, 1, 1), Seq(2, 1, 1), Seq(2, 2, 1), Seq(2, 2, 2)))
  }

  test("combine two generator signals") {
    val signal = Transformers.combine(
      GeneratorSignal.counter(200.millis),
      Transformers.map(GeneratorSignal.counter(210.millis)) { n => -n },
    ) {
      case (a, b) => s"$a:$b"
    }

    val isSuccess = Signal(false)
    val builder = mutable.ArrayBuilder.make[String]
    signal.foreach { t =>
      builder.addOne(t)
      isSuccess ! (t == "2:-2")
    }

    waitForResult(isSuccess, true)
    signal.close()
    awaitAllTasks
    assertEquals(builder.result().toSeq, Seq("0:0", "1:0", "1:-1", "2:-1", "2:-2"))
  }

  test("emit an event after delay by wrapping a closeable future") {
    val promise = Promise[Long]()
    val t = System.currentTimeMillis()
    val stream = Transformers.streamFromFuture(CloseableFuture.delay(1 seconds))

    stream.onCurrent { _ => promise.success(System.currentTimeMillis() - t) }

    assert(result(promise.future) >= 1000L)
  }

  test("don't emit an event from a closeable future after delay if the stream closed before") {
    val promise = Promise[Long]()
    val t = System.currentTimeMillis()
    val cFuture = CloseableFuture.delay(1 seconds)
    val stream = Transformers.streamFromFuture(cFuture)

    stream.onCurrent { _ => promise.success(System.currentTimeMillis() - t) }

    stream.close()

    assert(tryResult(cFuture.future)(using 1 seconds).isFailure)
    assert(stream.isClosed)
    assert(cFuture.isClosed)
  }

  test("update the signal after delay by wrapping a closeable future") {
    val promise = Promise[Long]()
    val t = System.currentTimeMillis()
    val signal = Transformers.signalFromFuture(CloseableFuture.delay(1 seconds))

    signal.onCurrent { _ => promise.success(System.currentTimeMillis() - t) }

    assert(result(promise.future) >= 1000L)
  }

  test("don't update the signal from a closeable future after delay if it's closed before") {
    val promise = Promise[Long]()
    val t = System.currentTimeMillis()
    val cFuture = CloseableFuture.delay(1 seconds)
    val signal = Transformers.signalFromFuture(cFuture)

    signal.onCurrent { _ => promise.success(System.currentTimeMillis() - t) }

    signal.close()

    assert(tryResult(cFuture.future)(using 1 seconds).isFailure)
    assert(signal.isClosed)
    assert(cFuture.isClosed)
  }

  test("Transformed stream calls onClose exactly once") {
    val original = GeneratorStream.heartbeat(200.millis)
    val mapped = Transformers.map(original)(_ => "foo")

    val isClosed = Signal(0)
    mapped.onClose { isClosed.mutate(_ + 1)  }

    mapped.close()
    awaitAllTasks
    waitForResult(isClosed, 1)
  }

  test("Original stream calls onClose exactly once") {
    val original = GeneratorStream.heartbeat(200.millis)
    val mapped = Transformers.map(original)(_ => "foo")

    val isClosed = Signal(0)
    original.onClose { isClosed.mutate(_ + 1) }

    mapped.close()

    awaitAllTasks
    waitForResult(isClosed, 1)
  }

  test("Closing the original stream calls onClose on the transformed one") {
    val original = GeneratorStream.heartbeat(200.millis)
    val mapped = Transformers.map(original)(_ => "foo")

    val isClosed = Signal(0)
    mapped.onClose { isClosed.mutate(_ + 1) }

    original.close()

    awaitAllTasks
    waitForResult(isClosed, 1)
    assert(original.isClosed)
    assert(mapped.isClosed)
  }

  test("In a zipped stream, closing the transformed one closes all originals") {
    val original1 = GeneratorStream.heartbeat(200.millis)
    val original2 = GeneratorStream.heartbeat(300.millis)
    val zipped = Transformers.zip(original1, original2)

    zipped.close()

    awaitAllTasks
    assert(zipped.isClosed)
    assert(original1.isClosed)
    assert(original2.isClosed)
  }

  test("In a zipped stream, closing the transformed one closes all originals") {
    val original1 = GeneratorStream.heartbeat(200.millis)
    val original2 = GeneratorStream.heartbeat(300.millis)
    val zipped = Transformers.zip(original1, original2)

    zipped.close()

    awaitAllTasks
    assert(zipped.isClosed)
    assert(original1.isClosed)
    assert(original2.isClosed)
  }

  test("In a zipped stream, closing the transformed one calls all onClose") {
    val isClosed = Signal(0)
    val original1 = GeneratorStream.heartbeat(200.millis)
    original1.onClose { isClosed.mutate(_ + 1) }
    val original2 = GeneratorStream.heartbeat(300.millis)
    original2.onClose { isClosed.mutate(_ + 1) }
    val zipped = Transformers.zip(original1, original2)
    zipped.onClose { isClosed.mutate(_ + 1) }

    zipped.close()

    awaitAllTasks
    waitForResult(isClosed, 3)
    assert(zipped.isClosed)
    assert(original1.isClosed)
    assert(original2.isClosed)
  }

  test("In a zipped stream, closing the original ones closes the transformed ones too") {
    val isClosed = Signal(0)
    val original1 = GeneratorStream.heartbeat(200.millis)
    original1.onClose { isClosed.mutate(_ + 1) }
    val original2 = GeneratorStream.heartbeat(300.millis)
    original2.onClose { isClosed.mutate(_ + 1) }
    val zipped = Transformers.zip(original1, original2)
    zipped.onClose { isClosed.mutate(_ + 1) }

    original1.close()

    awaitAllTasks
    waitForResult(isClosed, 1)
    assert(original1.isClosed)
    assert(!original2.isClosed)
    assert(!zipped.isClosed)

    original2.close()

    awaitAllTasks
    waitForResult(isClosed, 3)
    assert(original1.isClosed)
    assert(original2.isClosed)
    assert(zipped.isClosed)
  }