package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Closeable.CloseableStream
import io.github.makingthematrix.signals3.ProxyStream.IndexedStream
import io.github.makingthematrix.signals3.testutils.awaitAllTasks

import scala.collection.mutable

class IndexedStreamSpec extends munit.FunSuite:
  import EventContext.Implicits.global

  test("Counter starts at zero") {
    val a: Indexed[Int] = Stream().indexed
    assertEquals(0, a.counter)
  }

  test("Count events") {
    given dq: DispatchQueue = SerialDispatchQueue()
    val a: SourceStream[Int] = Stream()
    val b: IndexedStream[Int] = a.indexed
    var localCounter = 0
    b.foreach { _ =>
      localCounter += 1
      assertEquals(b.counter, localCounter)
    }

    a ! 1
    awaitAllTasks
    a ! 2
    awaitAllTasks
    a ! 3
    awaitAllTasks
  }

  test("Drop an event") {
    given dq: DispatchQueue = SerialDispatchQueue()
    val a: SourceStream[Int] = Stream()
    val b: Stream[Int] = a.drop(1)

    val buffer = mutable.ArrayBuilder.make[Int]
    b.foreach { n =>
      buffer.addOne(n)
    }

    a ! 1
    awaitAllTasks
    a ! 2
    awaitAllTasks
    a ! 3
    awaitAllTasks

    val seq = buffer.result().toSeq
    assertEquals(seq, Seq(2, 3))
  }

  test("Drop and map") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val a: SourceStream[Int] = Stream()
    val b: Stream[String] = a.drop(2).map(_.toString)

    val buffer = mutable.ArrayBuilder.make[String]
    b.foreach { str =>
      buffer.addOne(str)
    }

    a ! 1
    awaitAllTasks
    a ! 2
    awaitAllTasks
    a ! 3
    awaitAllTasks
    a ! 4
    awaitAllTasks

    val seq = buffer.result().toSeq
    assertEquals(seq, Seq("3", "4"))
  }

  test("Close after two events") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val a: SourceStream[Int] = Stream()
    val b: CloseableStream[Int] = a.take(2)

    val buffer = mutable.ArrayBuilder.make[Int]
    b.foreach { n =>
      buffer.addOne(n)
    }

    a ! 1
    awaitAllTasks
    a ! 2
    awaitAllTasks

    assert(b.isClosed)

    a ! 3
    awaitAllTasks
    a ! 4
    awaitAllTasks

    val seq = buffer.result().toSeq
    assertEquals(seq, Seq(1, 2))
  }

  test("Drop and take") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val a: SourceStream[Int] = Stream()
    val b: CloseableStream[Int] = a.drop(1).take(2)

    val buffer = mutable.ArrayBuilder.make[Int]
    b.foreach { n =>
      buffer.addOne(n)
    }

    a ! 1
    awaitAllTasks
    a ! 2
    awaitAllTasks
    a ! 3
    awaitAllTasks

    assert(b.isClosed)

    a ! 4
    awaitAllTasks

    val seq = buffer.result().toSeq
    assertEquals(seq, Seq(2, 3))
  }
