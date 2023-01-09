package io.makingthematrix.signals3

import CancellableFuture.CancelException
import CancellableFuture.toFuture
import scala.language.implicitConversions

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import testutils._

class CancellableFutureSpec extends munit.FunSuite {
  test("Transform between a future and a cancellable future") {
    import CancellableFuture._
    val f1: Future[Unit] = Future.successful(())
    val cf1 = f1.lift
    cf1 match {
      case _: CancellableFuture[Unit] =>
      case null => fail("Future[Unit] should be transformed into CancellableFuture[Unit]")
    }

    val f2 = cf1.future
    f2 match {
      case _: Future[Unit] =>
      case null => fail("CancellableFuture[Unit] should wrap over Future[Unit]")
    }

    val cf2 = CancellableFuture.lift(f2)
    cf2 match {
      case _: CancellableFuture[Unit] =>
      case null => fail("Future[Unit] should be lifted into CancellableFuture[Unit]")
    }
  }

  test("Create a cancellable future from the body") {
    import Threading.defaultContext
    var res = 0

    val cf = CancellableFuture { res = 1 }

    await(cf)

    assertEquals(res, 1)
  }

  test("Create an already completed cancellable future from the body") {
    var res = 0

    val cf = CancellableFuture.successful { res = 1 }

    assertEquals(res, 1)
  }

  test("Create an already failed cancellable future") {
    val cf = CancellableFuture.failed(new IllegalArgumentException)
    assert(cf.isCompleted)
  }

  test("Create an already cancelled cancellable future") {
    val cf = CancellableFuture.cancelled()
    assert(cf.isCompleted)
  }

  test(" A cancellable future succeeds just like a standard one") {
    implicit val ec: DispatchQueue = SerialDispatchQueue()
    var res = 0

    val p = Promise[Int]()
    val f = p.future
    f.foreach { res = _ }

    val cf = new Cancellable(p).onCancel { res = -1 }

    p.success(1)
    assertEquals(result(f), 1)

    assert(!cf.cancel())
    await(cf)
    assertEquals(res, 1) // cancellation doesn't change the result after the promise is completed
  }

  test("A cancellable future can be cancelled (wow! who would expect that)"){
    import Threading.defaultContext
    val res = Signal(0)

    val p = Promise[Int]()
    val f = p.future
    f.foreach { res ! _ }

    val cf = new Cancellable(p).onCancel { res ! -1 }

    assert(cf.cancel())
    await(cf)
    waitForResult(res, -1)
    intercept[java.lang.IllegalStateException](p.success(1)) // the promise is already cancelled
  }

  test("A cancellable future can't be cancelled twice") {
    import Threading.defaultContext
    val res = Signal(0)

    val p = Promise[Int]()
    val f = p.future
    f.foreach { res ! _ }

    val cf = new Cancellable(p).onCancel { res ! -1 }

    assert(cf.cancel())
    await(cf)
    waitForResult(res, -1)

    assert(!cf.cancel()) // if cancelling cf twice worked, this would be true
    await(cf)
    waitForResult(res, -1) // if cancelling cf twice worked, this would be -2*/
  }

  test(" Complete a delayed cancellable future") {
    var res = 0

    val cf: CancellableFuture[Unit] = CancellableFuture.delay(500.millis).map { _ => res = 1 }
    assertEquals(res, 0)

    await(cf)

    assertEquals(res, 1)
  }

  test("Complete a cancellable future delayed in another way") {
    import Threading.defaultContext
    var res = 0

    val cf: CancellableFuture[Unit] = CancellableFuture.delayed(500.millis) { res = 1 }
    assertEquals(res, 0)

    await(cf)

    assertEquals(res, 1)
  }

  test("Cancel a delayed future") {
    import Threading.defaultContext
    var res = 0

    val cf: CancellableFuture[Unit] = CancellableFuture.delayed(500.millis) { res = 1 }
    assertEquals(res, 0)
    assert(cf.cancel())

    await(cf)

    assertEquals(res, 0)
  }

  test("Repeat a task until cancelled") {
    import Threading.defaultContext

    var timestamps = Seq.empty[Long]
    val offset = System.currentTimeMillis
    val cf = CancellableFuture.repeat(100.millis){ timestamps :+= (System.currentTimeMillis - offset) }

    CancellableFuture.delayed(500.millis) { cf.cancel() }

    await(cf)

    assert(timestamps.size >= 4)
  }

  test("Turn a sequence of cancellable futures into one") {
    import Threading.defaultContext

    var timestamps = Seq.empty[Long]
    val offset = System.currentTimeMillis
    val cf1 = CancellableFuture.delayed(100.millis) { timestamps :+= (System.currentTimeMillis - offset) }
    val cf2 = CancellableFuture.delayed(200.millis) { timestamps :+= (System.currentTimeMillis - offset) }
    val cfSeq = CancellableFuture.sequence(Seq(cf1, cf2))

    await(cfSeq)

    assert(timestamps.size == 2)
  }

  test("You can't cancel a lifted future") {
    import Threading.defaultContext
    import CancellableFuture._

    var theFlag = false // this should stay false if the future was cancelled (but it won't)
    var semaphore = false
    val f1: Future[Unit] = Future {
      while (!semaphore) Thread.sleep(100L)
      theFlag = true
    }

    val cf1 = f1.lift
    assert(!cf1.cancel())

    semaphore = true
    await(f1)

    assert(theFlag)
  }

  test("Traverse a sequence of tasks") {
    import Threading.defaultContext

    var timestamps = Seq.empty[Long]
    val offset = System.currentTimeMillis
    val millis = Seq(200, 100, 50, 150)
    val cf = CancellableFuture.traverse(millis) { t =>  CancellableFuture.delayed(t.millis) { timestamps :+= (System.currentTimeMillis - offset) }}

    await(cf)
    assert(timestamps.size == 4)
  }

  test("Traverse a sequence of tasks sequentially") {
    import Threading.defaultContext

    var timestamps = Seq.empty[(Int, Long)]
    val offset = System.currentTimeMillis
    val millis = Seq((1, 200), (2, 100), (3, 50), (4, 150))
    val cf = CancellableFuture.traverseSequential(millis) {
      case (i, t) =>  CancellableFuture.delayed(t.millis) { timestamps :+= (i, System.currentTimeMillis - offset) }
    }
    await(cf)
    assert(timestamps.size == 4)

    // indices of finished tasks are in the same order as the tuples
    timestamps.map(_._1).zip(millis.map(_._1)).foreach { case (t, m) => assertEquals(t, m) }
  }

  test("Traverse a sequence of tasks - quickest goes first") {
    import Threading.defaultContext

    var timestamps = Seq.empty[(Int, Long)]
    val offset = System.currentTimeMillis
    val millis = Seq((1, 200), (2, 100), (3, 50), (4, 150))
    val cf = CancellableFuture.traverse(millis) {
      case (i, t) =>  CancellableFuture.delayed(t.millis) { timestamps :+= (i, System.currentTimeMillis - offset) }
    }
    await(cf)

    assert(timestamps.size == 4)

    // indices of finished tasks are in the order from the one which finished first to the last
    timestamps.map(_._1).zip(millis.sortBy(_._2).map(_._1)).foreach { case (t, m) => assertEquals(t, m) }
  }

  test("Cancel a sequence of cancellable futures") {
    import Threading.defaultContext

    val onCancel = Signal(false)

    var timestamps = Seq.empty[Long]
    val offset = System.currentTimeMillis
    val cf1 = CancellableFuture.delayed(300.millis) { timestamps :+= (System.currentTimeMillis - offset) }
    val cf2 = CancellableFuture.delayed(400.millis) { timestamps :+= (System.currentTimeMillis - offset) }
    val cf3 = CancellableFuture.delayed(500.millis) { timestamps :+= (System.currentTimeMillis - offset) }
    val cf4 = CancellableFuture.delayed(600.millis) { timestamps :+= (System.currentTimeMillis - offset) }

    val cfSeq = CancellableFuture.sequence(Seq(cf1, cf2, cf3, cf4)).onCancel { onCancel ! true }
    Thread.sleep(50)
    cfSeq.cancel()
    waitForResult(onCancel, true)

    assert(timestamps.isEmpty)
  }

  test("After a successful execution of one future in the sequence, cancel the rest") {
    import Threading.defaultContext

    val onCancel = EventStream[Unit]()

    var timestamps = Seq.empty[Long]
    val offset = System.currentTimeMillis
    val cancelOthers = () => {
      timestamps :+= (System.currentTimeMillis - offset)
      onCancel ! ()
    }
    val cf1 = CancellableFuture.delayed(300.millis)(cancelOthers())
    val cf2 = CancellableFuture.delayed(50.millis)(cancelOthers())
    val cf3 = CancellableFuture.delayed(100.millis)(cancelOthers())
    val cf4 = CancellableFuture.delayed(200.millis)(cancelOthers())

    val cfSeq = CancellableFuture.sequence(Seq(cf1, cf2, cf3, cf4))
    onCancel.foreach(_ => cfSeq.cancel())

    waitForResult(onCancel, ())
    await(cfSeq)

    assertEquals(timestamps.size, 1)
  }

  test("Cancel a traverse after the first task finishes with success") {
    import Threading.defaultContext

    val onCancel = EventStream[Unit]()
    var timestamps = Seq.empty[Long]
    val offset = System.currentTimeMillis
    val cancelOthers = () => {
      timestamps :+= (System.currentTimeMillis - offset)
      onCancel ! ()
    }

    val millis = Seq(400, 500, 50, 300)
    val cf = CancellableFuture.traverse(millis) { t =>
      CancellableFuture.delayed(t.millis) { cancelOthers() }
    }
    onCancel.foreach(_ => cf.cancel())

    waitForResult(onCancel, ())
    await(cf)

    assertEquals(timestamps.size, 1)
  }

  test("Cancel a sequential traverse after the first task finishes with success") {
    import Threading.defaultContext

    val onCancel = EventStream[Unit]()
    var timestamps = Seq.empty[Long]
    val offset = System.currentTimeMillis
    val cancelOthers = () => {
      timestamps :+= (System.currentTimeMillis - offset)
      onCancel ! ()
    }

    val millis = Seq(50, 500, 250, 300)
    val cf = CancellableFuture.traverseSequential(millis) { t =>
      CancellableFuture.delayed(t.millis) { cancelOthers() }
    }
    onCancel.foreach(_ => cf.cancel())

    waitForResult(onCancel, ())
    await(cf)

    assertEquals(timestamps.size, 1)
  }

  test("Sequence two cancellable futures and get the result") {
    import Threading.defaultContext

    val cf1: CancellableFuture[String] = CancellableFuture.delayed(100.millis) { "foo" }
    val cf2: CancellableFuture[String] = CancellableFuture.delayed(150.millis) { "bar" }
    val cf: CancellableFuture[Iterable[String]] = CancellableFuture.sequence(Vector(cf1, cf2))

    val res = result(cf).toVector
    assertEquals(res.size, 2)
    assertEquals(res(0), "foo")
    assertEquals(res(1), "bar")
  }

  test("Zip two cancellable futures") {
    import Threading.defaultContext

    val cf1: CancellableFuture[String] = CancellableFuture.delayed(100.millis) { "foo" }
    val cf2: CancellableFuture[Int] = CancellableFuture.delayed(150.millis) { 666 }
    val cf: CancellableFuture[(String, Int)] = CancellableFuture.zip(cf1, cf2)
    assertEquals(result(cf), ("foo", 666))
  }

  test("Cancel zipped futures") {
    import Threading.defaultContext

    val s = Signal(0)

    val cf1 = CancellableFuture.delayed(200.millis) { s ! 1; "foo" }
    val cf2 = CancellableFuture.delayed(250.millis) { s ! 2; 666 }
    val cf = CancellableFuture.zip(cf1, cf2)

    CancellableFuture.delayed(50.millis) { s ! 3; assert(cf.cancel()) }

    await(cf)

    assertEquals(result(s.head), 3)
  }

  test("You can't cancel an uncancellable future") {
    import Threading.defaultContext

    val s = Signal(0)

    val cf = CancellableFuture.delayed(200.millis) { s ! 1 }.toUncancellable
    assert(!cf.isCancellable)

    CancellableFuture.delayed(50.millis) { s ! 3; assert(!cf.cancel()) }

    await(cf)

    assertEquals(result(s.head), 1)
  }

  test("Cancelling a future triggers its onCancel task") {
    import Threading.defaultContext

    val s = Signal(0)

    val cf = CancellableFuture { Thread.sleep(200); s ! 1 }.onCancel { s ! 2 }
    assert(cf.isCancellable)

    CancellableFuture.delayed(50.millis) { assert(cf.cancel()) }

    await(cf)

    assert(waitForResult(s, 2))
  }

  test("Cancelling a delayed future triggers its onCancel task") {
    import Threading.defaultContext

    val s = Signal(0)

    val cf = CancellableFuture.delayed(200.millis){ s ! 1 }.onCancel { s ! 2 }
    assert(cf.isCancellable)

    CancellableFuture.delayed(50.millis) { assert(cf.cancel()) }

    await(cf)

    assert(waitForResult(s, 2))
  }

  test("A failed but not cancelled future does not trigger onCancel") {
    import Threading.defaultContext
    var res = 0

    val cf: CancellableFuture[Unit] = CancellableFuture { Thread.sleep(500); res = 1 }.onCancel { res = 2 }

    assertEquals(res, 0)
    case object FailureException extends Throwable
    assert(cf.fail(FailureException))

    await(cf)

    assertEquals(res, 0)
  }

  test("onCancel added after the future is already finished won't work") {
    import Threading.defaultContext
    var res = 0

    val cf: CancellableFuture[Unit] = CancellableFuture { res = 1 }
    await(cf)
    assertEquals(res, 1)
    cf.onCancel { res = 2 }
    cf.cancel()
    await(cf)

    assertEquals(res, 1)
  }

  test("onCancel added after the future is already cancelled won't work") {
    import Threading.defaultContext
    var res = 0

    val cf: CancellableFuture[Unit] = CancellableFuture { Thread.sleep(500); res = 1 }
    cf.cancel()
    await(cf)
    cf.onCancel { res = 2 }
    await(cf)

    assertEquals(res, 0)
  }

  test("Map a cancellable future") {
    import Threading.defaultContext

    val cf1 = CancellableFuture { Thread.sleep(50); "foo" }
    val cf2 = cf1.map(_.toUpperCase)

    assertEquals(result(cf2), "FOO")
  }

  /**
    * This is an important feature. It means that we can treat a chain of mappings as if we were using a builder
    * of a future, and then we can use the future produced by the whole chain exactly the same way as if it was
    * created in one step. One of the requirements for that is that cancelling that future should work up the chain
    * and cancel all intermediate futures and the original one too.
    * Also, this behaviour is in agreement with how cancelling a future produced by `.sequence` cancels original
    * futures as well (and `.traverse`, and `.zip`, etc.)
    */
  test("Cancelling a mapped future also cancels the original one") {
    import Threading.defaultContext

    val s = Signal("")

    val cf1 = CancellableFuture { Thread.sleep(500); s ! "foo"; "foo" }.onCancel { s ! "bar" }
    val cf2 = cf1.map(_.toUpperCase)

    assert(cf2.cancel())

    assert(waitForResult(s, "bar"))
  }

  test("Flat-map a cancellable future") {
    import Threading.defaultContext

    val cf1 = CancellableFuture { Thread.sleep(50); "foo" }
    val cf2 = cf1.flatMap {
      case ""    => CancellableFuture.successful("boo")
      case other => CancellableFuture { Thread.sleep(50); other.toUpperCase }
    }

    assertEquals(result(cf2), "FOO")
  }

  test("Cancelling a flat-mapped future also cancels the original one") {
    import Threading.defaultContext

    val s = Signal("")

    val cf1 = CancellableFuture { Thread.sleep(500); s ! "foo"; "foo" }.onCancel { s ! "bar" }
    val cf2 = cf1.flatMap {
      case ""    => CancellableFuture.successful("boo")
      case other => CancellableFuture { Thread.sleep(500); other.toUpperCase }
    }

    assert(cf2.cancel())

    assert(waitForResult(s, "bar"))
  }

  test("recover from a cancelled future") {
    import Threading.defaultContext

    val cf1 = CancellableFuture { Thread.sleep(500); "foo" }
    val cf2 = cf1.recover {
      case CancelException => "bar"
    }

    assert(cf1.cancel())

    assertEquals(result(cf2), "bar")
  }
}
