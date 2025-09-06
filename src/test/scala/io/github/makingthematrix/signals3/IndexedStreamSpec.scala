package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Closeable.CloseableStream
import io.github.makingthematrix.signals3.ProxyStream.{IndexedStream, TakeStream}
import io.github.makingthematrix.signals3.testutils.awaitAllTasks

import scala.collection.mutable

class IndexedStreamSpec extends munit.FunSuite:
  import EventContext.Implicits.global
  given DispatchQueue = SerialDispatchQueue()

  test("Counter starts at zero") {
    val a: Indexed = Stream().indexed
    assertEquals(0, a.counter)
  }

  test("Count events") {
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
    val a: SourceStream[Int] = Stream()
    val b: TakeStream[Int] = a.take(2)

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

  test("Close a stream manually") {
    val a: SourceStream[Int] = Stream()
    val b: CloseableStream[Int] = a.closeable

    val buffer = mutable.ArrayBuilder.make[Int]
    b.foreach { n =>
      buffer.addOne(n)
    }

    a ! 1
    awaitAllTasks
    a ! 2
    awaitAllTasks

    b.close()
    assert(b.isClosed)

    a ! 3
    awaitAllTasks
    a ! 4
    awaitAllTasks

    val seq = buffer.result().toSeq
    assertEquals(seq, Seq(1, 2))
  }

  test("Drop and take") {
    val a: SourceStream[Int] = Stream()
    val b: TakeStream[Int] = a.drop(1).take(2)

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

  test("Take and drop") {
    val a: SourceStream[Int] = Stream()
    val c = a.take(2).drop(1)

    val cBuffer = mutable.ArrayBuilder.make[Int]
    c.foreach { cBuffer.addOne }

    a ! 1
    awaitAllTasks
    a ! 2
    awaitAllTasks
    a ! 3
    awaitAllTasks
    a ! 4
    awaitAllTasks

    val cSeq = cBuffer.result().toSeq
    assertEquals(cSeq, Seq(2))
  }

  test("Split a stream into a head future and tail stream") {
    import Stream.`::`
    val a: SourceStream[Int] = Stream()
    val (head, tail) = a match
      case head :: tail => (head, tail)

    var hn = 0
    head.foreach(n => hn = n)

    val buffer = mutable.ArrayBuilder.make[Int]
    tail.foreach { n =>
      buffer.addOne(n)
    }

    a ! 1
    awaitAllTasks
    a ! 2
    awaitAllTasks
    a ! 3
    awaitAllTasks

    assertEquals(hn, 1)

    val seq = buffer.result().toSeq
    assertEquals(seq, Seq(2, 3))
  }

  test("Take and use .last to get the last of the took elements") {
    val a: SourceStream[Int] = Stream()

    val c = a.take(2)
    val cBuffer = mutable.ArrayBuilder.make[Int]
    c.foreach {cBuffer.addOne}

    val f = c.last
    var fValue: Int = 0
    f.foreach(fValue = _)

    a ! 1
    awaitAllTasks
    a ! 2
    awaitAllTasks
    a ! 3
    awaitAllTasks

    assertEquals(cBuffer.result().toSeq, Seq(1, 2))
    assertEquals(fValue, 2)
  }

  test("Take and use .init to get all but the last element") {
    val a: SourceStream[Int] = Stream()

    val c = a.take(3)
    val cBuffer = mutable.ArrayBuilder.make[Int]
    c.foreach {cBuffer.addOne}

    val init = c.init
    val initBuffer = mutable.ArrayBuilder.make[Int]
    init.foreach {initBuffer.addOne}

    a ! 1
    awaitAllTasks
    a ! 2
    awaitAllTasks
    a ! 3
    awaitAllTasks
    a ! 4
    awaitAllTasks

    assertEquals(cBuffer.result().toSeq, Seq(1, 2, 3))
    assertEquals(initBuffer.result().toSeq, Seq(1, 2))
  }
