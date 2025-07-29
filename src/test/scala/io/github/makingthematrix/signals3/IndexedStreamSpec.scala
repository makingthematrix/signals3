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
    given DispatchQueue = SerialDispatchQueue()
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
    given DispatchQueue = SerialDispatchQueue()
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
    given DispatchQueue = SerialDispatchQueue()

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
    given DispatchQueue = SerialDispatchQueue()

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
    given DispatchQueue = SerialDispatchQueue()

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

  test("Take and drop") {
    given DispatchQueue = SerialDispatchQueue()

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

  test("foo") {
    val l = 1 :: List(2)
    case class Foo(n: Seq[Int])
    object `::`:
      def unapply(foo: Foo): Option[(Int, Foo)] =
        if foo.n.isEmpty then None
        else Some((foo.n.head, Foo(foo.n.tail)))

    val foo = Foo(Seq(1,2,3))
    val a = foo match
      case head :: tail => head
      case _ => 0

    assertEquals(1, a)
  }
