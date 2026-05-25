package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Finite.{FiniteSignal, FiniteStream}
import io.github.makingthematrix.signals3.testutils.{awaitAllTasks, waitFor}

import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class FiniteSpec extends munit.FunSuite {

  private val eventContext = EventContext()
  given dq: DispatchQueue = SerialDispatchQueue()
  given Timeout: FiniteDuration = 250.millis

  override def beforeEach(context: BeforeEach): Unit =
    eventContext.start()

  override def afterEach(context: AfterEach): Unit =
    eventContext.stop()

  test("Chain a finite stream with >> operator") {
    val a: SourceStream[Int] = Stream()
    val b: FiniteStream[Int] = a.take(3)
    val c: SourceStream[Int] = Stream()
    val d: Stream[Int] = b >> c

    val dBuffer = mutable.ArrayBuilder.make[Int]
    d.foreach(dBuffer.addOne)

    a !! 1
    c !! 10
    a !! 2
    c !! 20
    awaitAllTasks
    assertEquals(dBuffer.result().toSeq, Seq(1, 2))

    a !! 3
    assert(b.isClosed)
    awaitAllTasks
    assertEquals(dBuffer.result().toSeq, Seq(1, 2, 3))

    a !! 4
    c !! 30
    awaitAllTasks
    assertEquals(dBuffer.result().toSeq, Seq(1, 2, 3, 30))
  }

  test("Chain finite streams with >>> operator") {
    val a: SourceStream[Int] = Stream()
    val b: FiniteStream[Int] = a.take(3)
    val c: SourceStream[Int] = Stream()
    val d: FiniteStream[Int] = c.take(2)
    val e: FiniteStream[Int] = b >>> d

    val buffer = mutable.ArrayBuilder.make[Int]
    e.foreach(buffer.addOne)

    var lastValue = 0
    e.last.foreach(lastValue = _)

    a !! 1
    c !! 10
    a !! 2
    c !! 20
    awaitAllTasks
    assertEquals(buffer.result().toSeq, Seq(1, 2))

    a !! 3
    assert(b.isClosed)
    awaitAllTasks
    assertEquals(buffer.result().toSeq, Seq(1, 2, 3))

    a !! 4
    c !! 30
    c !! 40
    awaitAllTasks
    assertEquals(buffer.result().toSeq, Seq(1, 2, 3, 30, 40))
    assert(d.isClosed)
    assert(e.isClosed)
    assertEquals(lastValue, 40)
  }

  test("Chain a finite signal with >> operator") {
    val a: SourceSignal[Int] = Signal()
    val b: FiniteSignal[Int] = a.take(3)
    val c: SourceSignal[Int] = Signal()
    val d: Signal[Int] = b >> c

    var res: List[Int] = Nil
    d.foreach { n =>
      res = n :: res
    }

    a ! 1
    c ! 10
    a ! 2
    c ! 20
    awaitAllTasks
    assertEquals(res.reverse, List(1, 2))

    a ! 3
    waitFor(d, 3)
    assert(b.isClosed)
    assertEquals(res.reverse, List(1, 2, 3))

    a ! 4
    c ! 30
    awaitAllTasks
    assertEquals(res.reverse, Seq(1, 2, 3, 30))
  }

  test("Chain finite signals with >>> operator") {
    val a: SourceSignal[Int] = Signal()
    val b: FiniteSignal[Int] = a.take(3)
    val c: SourceSignal[Int] = Signal()
    val d: FiniteSignal[Int] = c.take(2)
    val e: FiniteSignal[Int] = b >>> d

    var lastValue = 0
    e.last.foreach(lastValue = _)

    var res: List[Int] = Nil
    e.foreach { n =>
      res = n :: res
    }

    a ! 1
    c ! 10
    a ! 2
    c ! 20
    awaitAllTasks
    assertEquals(res.reverse, List(1, 2))

    a ! 3
    waitFor(e, 3)
    assert(b.isClosed)
    assertEquals(res.reverse, List(1, 2, 3, 20))

    a ! 4
    c ! 30
    waitFor(e, 30)
    assert(d.isClosed)
    assert(e.isClosed)
    c ! 40
    awaitAllTasks
    assertEquals(res.reverse, Seq(1, 2, 3, 20, 30))
    assertEquals(lastValue, 30)
  }
}
