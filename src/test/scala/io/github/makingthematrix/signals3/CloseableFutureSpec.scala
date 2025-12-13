package io.github.makingthematrix.signals3

import CloseableFuture.{Closed, toCloseable, toFuture}

import scala.language.implicitConversions
import scala.util.chaining.scalaUtilChainingOps
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.*
import testutils.*

class CloseableFutureSpec extends munit.FunSuite {
  import EventContext.Implicits.global
  import Threading.defaultContext

  test("Transform between a future and a closeable future") {
    val f1: Future[Unit] = Future.successful(())
    val cf1 = f1.toCloseable
    cf1 match {
      case _: CloseableFuture[Unit] =>
      case null => fail("Future[Unit] should be transformed into CloseableFuture[Unit]")
    }

    val f2 = cf1.future
    f2 match {
      case _: Future[Unit] =>
      case null => fail("CloseableFuture[Unit] should wrap over Future[Unit]")
    }

    val cf2 = CloseableFuture.lift(f2)
    cf2 match {
      case _: CloseableFuture[Unit] =>
      case null => fail("Future[Unit] should be lifted into CloseableFuture[Unit]")
    }
  }

  test("Create a closeable future from the body") {
    var res = 0

    val cf = CloseableFuture { res = 1 }

    await(cf)

    assertEquals(res, 1)
  }

  test("Create an already completed closeable future from the body") {
    var res = 0

    val cf = CloseableFuture.successful { res = 1 }

    assertEquals(res, 1)
  }

  test("Create an already failed closeable future") {
    val cf = CloseableFuture.failed(new IllegalArgumentException)
    assert(cf.isCompleted)
  }

  test("Create an already closed closeable future") {
    val cf = CloseableFuture.closed
    assert(cf.isCompleted)
  }

  test(" A closeable future succeeds just like a standard one") {
    var res = 0

    val p = Promise[Int]()
    val f = p.future
    f.foreach { res = _ }

    val cf = CloseableFuture.from(p).tap { _.onClose { res = -1 } }
    awaitAllTasks

    p.success(1)
    assertEquals(result(f), 1)

    assert(!cf.closeAndCheck())
    await(cf)
    assertEquals(res, 1) // closing doesn't change the result after the promise is completed
  }

  test("A closeable future can be closed (wow! who would expect that)"){
    val res = Signal(0)

    val p = Promise[Int]()
    val f = p.future
    f.foreach { res ! _ }

    val cf = CloseableFuture.from(p).tap { _.onClose { res ! -1 } }

    assert(cf.closeAndCheck())
    await(cf)
    waitForResult(res, -1)
    intercept[java.lang.IllegalStateException](p.success(1)) // the promise is already closed
  }

  test("A closeable future can't be closed twice") {
    val res = Signal(0)

    val p = Promise[Int]()
    val f = p.future
    f.foreach { res ! _ }

    val cf = CloseableFuture.from(p).tap { _.onClose { res ! -1 } }

    assert(cf.closeAndCheck())
    await(cf)
    waitForResult(res, -1)

    assert(!cf.closeAndCheck()) // if closing cf twice worked, this would be true
    await(cf)
    waitForResult(res, -1) // if closing cf twice worked, this would be -2*/
  }

  test(" Complete a delayed closeable future") {
    var res = 0

    val cf: CloseableFuture[Unit] = CloseableFuture.delay(500.millis).map { _ => res = 1 }
    assertEquals(res, 0)

    await(cf)

    assertEquals(res, 1)
  }

  test("Complete a closeable future delayed in another way") {
    var res = 0

    val cf: CloseableFuture[Unit] = CloseableFuture.delayed(500.millis) { res = 1 }
    assertEquals(res, 0)

    await(cf)

    assertEquals(res, 1)
  }

  test("Close a delayed future") {
    var res = 0

    val cf: CloseableFuture[Unit] = CloseableFuture.delayed(500.millis) { res = 1 }
    assertEquals(res, 0)
    assert(cf.closeAndCheck())

    await(cf)

    assertEquals(res, 0)
  }

  test("Repeat a task until closed") {
    var timestamps = Seq.empty[Long]
    val offset = System.currentTimeMillis
    val cf = CloseableFuture.repeat(100.millis){ timestamps :+= (System.currentTimeMillis - offset) }

    CloseableFuture.delayed(500.millis) { cf.close() }

    await(cf)

    assert(timestamps.size >= 4)
  }

  test("Turn a sequence of closeable futures into one") {
    var timestamps = Seq.empty[Long]
    val offset = System.currentTimeMillis
    val cf1 = CloseableFuture.delayed(100.millis) { timestamps :+= (System.currentTimeMillis - offset) }
    val cf2 = CloseableFuture.delayed(200.millis) { timestamps :+= (System.currentTimeMillis - offset) }
    val cfSeq = CloseableFuture.sequence(Seq(cf1, cf2))

    await(cfSeq)

    assert(timestamps.size == 2)
  }

  test("You can't close a lifted future") {
    var theFlag = false // this should stay false if the future was closed (but it won't)
    var semaphore = false
    val f1: Future[Unit] = Future {
      while !semaphore do Thread.sleep(100L)
      theFlag = true
    }

    val cf1 = f1.toCloseable
    assert(!cf1.closeAndCheck())

    semaphore = true
    await(f1)

    assert(theFlag)
  }

  test("Traverse a sequence of tasks") {
    var timestamps = Seq.empty[Long]
    val offset = System.currentTimeMillis
    val millis = Seq(200, 100, 50, 150)
    val cf = CloseableFuture.traverse(millis) { t =>  CloseableFuture.delayed(t.millis) { timestamps :+= (System.currentTimeMillis - offset) }}

    await(cf)
    assert(timestamps.size == 4)
  }

  test("Traverse a sequence of tasks sequentially") {
    var timestamps = Seq.empty[(Int, Long)]
    val offset = System.currentTimeMillis
    val millis = Seq((1, 200), (2, 100), (3, 50), (4, 150))
    val cf = CloseableFuture.traverseSequential(millis) {
      case (i, t) =>  CloseableFuture.delayed(t.millis) { timestamps :+= (i, System.currentTimeMillis - offset) }
    }
    await(cf)
    assert(timestamps.size == 4)

    // indices of finished tasks are in the same order as the tuples
    timestamps.map(_._1).zip(millis.map(_._1)).foreach { case (t, m) => assertEquals(t, m) }
  }

  test("Traverse a sequence of tasks - quickest goes first") {
    var timestamps = Seq.empty[(Int, Long)]
    val offset = System.currentTimeMillis
    val millis = Seq((1, 200), (2, 100), (3, 50), (4, 150))
    val cf = CloseableFuture.traverse(millis) {
      case (i, t) =>  CloseableFuture.delayed(t.millis) { timestamps :+= (i, System.currentTimeMillis - offset) }
    }
    await(cf)

    assert(timestamps.size == 4)

    // indices of finished tasks are in the order from the one which finished first to the last
    timestamps.map(_._1).zip(millis.sortBy(_._2).map(_._1)).foreach { case (t, m) => assertEquals(t, m) }
  }

  test("Close a sequence of closeable futures") {
    val onClose = Signal(false)

    var timestamps = Seq.empty[Long]
    val offset = System.currentTimeMillis
    val cf1 = CloseableFuture.delayed(300.millis) { timestamps :+= (System.currentTimeMillis - offset) }
    val cf2 = CloseableFuture.delayed(400.millis) { timestamps :+= (System.currentTimeMillis - offset) }
    val cf3 = CloseableFuture.delayed(500.millis) { timestamps :+= (System.currentTimeMillis - offset) }
    val cf4 = CloseableFuture.delayed(600.millis) { timestamps :+= (System.currentTimeMillis - offset) }

    val cfSeq = CloseableFuture.sequence(Seq(cf1, cf2, cf3, cf4)).tap { _.onClose { onClose ! true } }
    Thread.sleep(50)
    cfSeq.close()
    waitForResult(onClose, true)

    assert(timestamps.isEmpty)
  }

  test("After a successful execution of one future in the sequence, close the rest") {
    val onClose = Stream[Unit]()

    var timestamps = Seq.empty[Long]
    val offset = System.currentTimeMillis
    val closeOthers = () => {
      timestamps :+= (System.currentTimeMillis - offset)
      onClose ! ()
    }
    val cf1 = CloseableFuture.delayed(300.millis)(closeOthers())
    val cf2 = CloseableFuture.delayed(50.millis)(closeOthers())
    val cf3 = CloseableFuture.delayed(100.millis)(closeOthers())
    val cf4 = CloseableFuture.delayed(200.millis)(closeOthers())

    val cfSeq = CloseableFuture.sequence(Seq(cf1, cf2, cf3, cf4))
    onClose.foreach(_ => cfSeq.close())

    waitForResult(onClose, ())
    await(cfSeq)

    assertEquals(timestamps.size, 1)
  }

  test("Close a traverse after the first task finishes with success") {
    val onClose = Stream[Unit]()
    var timestamps = Seq.empty[Long]
    val offset = System.currentTimeMillis
    val closeOthers = () => {
      timestamps :+= (System.currentTimeMillis - offset)
      onClose ! ()
    }

    val millis = Seq(400, 500, 50, 300)
    val cf = CloseableFuture.traverse(millis) { t =>
      CloseableFuture.delayed(t.millis) { closeOthers() }
    }
    onClose.foreach(_ => cf.close())

    waitForResult(onClose, ())
    await(cf)

    assertEquals(timestamps.size, 1)
  }

  test("Close a sequential traverse after the first task finishes with success") {
    val onClose = Stream[Unit]()
    var timestamps = Seq.empty[Long]
    val offset = System.currentTimeMillis
    val closeOthers = () => {
      timestamps :+= (System.currentTimeMillis - offset)
      onClose ! ()
    }

    val millis = Seq(50, 500, 250, 300)
    val cf = CloseableFuture.traverseSequential(millis) { t =>
      CloseableFuture.delayed(t.millis) { closeOthers() }
    }
    onClose.foreach(_ => cf.close())

    waitForResult(onClose, ())
    await(cf)

    assertEquals(timestamps.size, 1)
  }

  test("Sequence two closeable futures and get the result") {
    val cf1: CloseableFuture[String] = CloseableFuture.delayed(100.millis) { "foo" }
    val cf2: CloseableFuture[String] = CloseableFuture.delayed(150.millis) { "bar" }
    val cf: CloseableFuture[Iterable[String]] = CloseableFuture.sequence(Vector(cf1, cf2))

    val res = result(cf).toVector
    assertEquals(res.size, 2)
    assertEquals(res(0), "foo")
    assertEquals(res(1), "bar")
  }

  test("Zip two closeable futures") {
    val cf1: CloseableFuture[String] = CloseableFuture.delayed(100.millis) { "foo" }
    val cf2: CloseableFuture[Int] = CloseableFuture.delayed(150.millis) { 666 }
    val cf: CloseableFuture[(String, Int)] = CloseableFuture.zip(cf1, cf2)
    assertEquals(result(cf), ("foo", 666))
  }

  test("Close zipped futures") {
    val s = Signal(0)

    val cf1 = CloseableFuture.delayed(200.millis) { s ! 1; "foo" }
    val cf2 = CloseableFuture.delayed(250.millis) { s ! 2; 666 }
    val cf = CloseableFuture.zip(cf1, cf2)

    CloseableFuture.delayed(50.millis) { s ! 3; assert(cf.closeAndCheck()) }

    await(cf)

    assertEquals(result(s.head), 3)
  }

  test("You can't close an uncloseable future") {
    val s = Signal(0)

    val cf = CloseableFuture.delayed(200.millis) { s ! 1 }.toUncloseable
    assert(!cf.isCloseable)

    CloseableFuture.delayed(50.millis) { s ! 3; assert(!cf.closeAndCheck()) }

    await(cf)

    assertEquals(result(s.head), 1)
  }

  test("Closing a future triggers its onClose task") {
    val s = Signal(0)

    val cf = CloseableFuture { Thread.sleep(200); s ! 1 }.tap { _.onClose { s ! 2 } }
    assert(cf.isCloseable)

    CloseableFuture.delayed(50.millis) { assert(cf.closeAndCheck()) }

    await(cf)

    assert(waitForResult(s, 2))
  }

  test("Closing a delayed future triggers its onClose task") {
    val s = Signal(0)

    val cf = CloseableFuture.delayed(200.millis){ s ! 1 }.tap { _.onClose { s ! 2 } }
    assert(cf.isCloseable)

    CloseableFuture.delayed(50.millis) { assert(cf.closeAndCheck()) }

    await(cf)

    assert(waitForResult(s, 2))
  }

  test("A failed but not closed future does not trigger onClose") {
    var res = 0

    val cf: CloseableFuture[Unit] = CloseableFuture { Thread.sleep(500); res = 1 }.tap { _.onClose { res = 2 } }

    assertEquals(res, 0)
    case object FailureException extends Throwable
    assert(cf.fail(FailureException))

    await(cf)

    assertEquals(res, 0)
  }

  test("onClose added after the future is already finished won't work") {
    var res = 0

    val cf: CloseableFuture[Unit] = CloseableFuture { res = 1 }
    await(cf)
    assertEquals(res, 1)
    cf.onClose { res = 2 }
    cf.close()
    await(cf)

    assertEquals(res, 1)
  }

  test("onClose added after the future is already closed won't work") {

    var res = 0

    val cf: CloseableFuture[Unit] = CloseableFuture { Thread.sleep(500); res = 1 }
    cf.close()
    await(cf)
    cf.onClose { res = 2 }
    await(cf)

    assertEquals(res, 0)
  }

  test("Map a closeable future") {
    val cf1 = CloseableFuture { Thread.sleep(50); "foo" }
    val cf2 = cf1.map(_.toUpperCase)

    assertEquals(result(cf2), "FOO")
  }

  /**
    * This is an important feature. It means that we can treat a chain of mappings as if we were using a builder
    * of a future, and then we can use the future produced by the whole chain exactly the same way as if it was
    * created in one step. One of the requirements for that is that closing that future should work up the chain
    * and close all intermediate futures and the original one too.
    * Also, this behaviour is in agreement with how closing a future produced by `.sequence` closes original
    * futures as well (and `.traverse`, and `.zip`, etc.)
    */
  test("Closing a mapped future also closes the original one") {
    val s = Signal("")

    val cf1 = CloseableFuture { Thread.sleep(500); s ! "foo"; "foo" }.tap { _.onClose { s ! "bar" } }
    val cf2 = cf1.map(_.toUpperCase)

    assert(cf2.closeAndCheck())

    assert(waitForResult(s, "bar"))
  }

  test("Flat-map a closeable future") {
    val cf1 = CloseableFuture { Thread.sleep(50); "foo" }
    val cf2 = cf1.flatMap {
      case ""    => CloseableFuture.successful("boo")
      case other => CloseableFuture { Thread.sleep(50); other.toUpperCase }
    }

    assertEquals(result(cf2), "FOO")
  }

  test("Closing a flat-mapped future also closes the original one") {
    val s = Signal("")

    val cf1 = CloseableFuture { Thread.sleep(500); s ! "foo"; "foo" }.tap { _.onClose { s ! "bar" } }
    val cf2 = cf1.flatMap {
      case ""    => CloseableFuture.successful("boo")
      case other => CloseableFuture { Thread.sleep(500); other.toUpperCase }
    }

    assert(cf2.closeAndCheck())

    assert(waitForResult(s, "bar"))
  }

  test("recover from a closed future") {
    val cf1 = CloseableFuture { Thread.sleep(500); "foo" }
    val cf2 = cf1.recover {
      case Closed => "bar"
    }

    assert(cf1.closeAndCheck())

    assertEquals(result(cf2), "bar")
  }
}
