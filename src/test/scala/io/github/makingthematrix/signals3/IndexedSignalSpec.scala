package io.github.makingthematrix.signals3

import testutils.*

import scala.collection.mutable

class IndexedSignalSpec extends munit.FunSuite {
  import EventContext.Implicits.global
  given DispatchQueue = SerialDispatchQueue()

  test("Counter starts at zero") {
    val a: Indexed = Signal().indexed
    assertEquals(0, a.counter)
  }

  test("Count events") {
    val a = Signal[Int]()
    val b = a.indexed
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

  test("Empty take signal is already closed") {
    val a = Signal[Int]()
    val b = a.take(0)
    assert(b.isClosed)
    assert(b.currentValue.isEmpty)

    val bBuffer = mutable.ArrayBuilder.make[Int]
    b.foreach(bBuffer.addOne)

    a ! 1
    awaitAllTasks
    a ! 2
    awaitAllTasks

    assertEquals(bBuffer.result().toSeq, Seq.empty[Int])
  }

  test("take(1) signal takes the current value of its parent and is already closed") {
    val a = Signal[Int](1)
    assert(a.currentValue.contains(1))
    val b = a.take(1)
    assert(b.currentValue.contains(1))
    assert(b.isClosed)

    val bBuffer = mutable.ArrayBuilder.make[Int]
    b.foreach(bBuffer.addOne)

    a ! 2
    awaitAllTasks
    a ! 3
    awaitAllTasks

    assertEquals(bBuffer.result().toSeq, Seq(1))
  }

  test("Take value changes until a condition") {
    val a = Signal[String]()
    val b = a.takeWhile(str => str.toInt < 3)

    val buffer = mutable.ArrayBuilder.make[Int]
    b.foreach { str => buffer.addOne(str.toInt) }
    var last: String = ""
    b.last.foreach(last = _)

    a ! "1"
    awaitAllTasks
    a ! "2"
    awaitAllTasks
    a ! "3"
    awaitAllTasks
    a ! "4"
    awaitAllTasks

    assertEquals(buffer.result().toSeq, Seq(1, 2))
    assert(b.isClosed)
    assertEquals(last, "2")
  }

  test("Close after two changes") {
    val a = Signal[Int]()
    val b = a.take(2)

    val buffer = mutable.ArrayBuilder.make[Int]
    b.foreach { n =>
      buffer.addOne(n)
    }

    a ! 1
    assert(waitForResult(a, 1))
    assert(waitForResult(b, 1))

    a ! 2
    assert(waitForResult(a, 2))
    assert(waitForResult(b, 2))

    assert(b.isClosed)

    a ! 3
    assert(waitForResult(a, 3))
    assert(waitForResult(b, 2))
    a ! 4
    assert(waitForResult(a, 4))
    assert(waitForResult(b, 2))

    val seq = buffer.result().toSeq
    assertEquals(seq, Seq(1, 2))
  }

  test("Get info about closing a signal through isClosedSignal") {
    val a = Signal[Int]()
    val b = a.take(2)

    val buffer = mutable.ArrayBuilder.make[Int]
    b.foreach { n =>
      buffer.addOne(n)
    }
    var closed = false
    b.isClosedSignal.onTrue.foreach { _ =>
      closed = true
    }

    a ! 1
    assert(waitForResult(a, 1))
    assert(waitForResult(b, 1))

    a ! 2
    assert(waitForResult(a, 2))
    assert(waitForResult(b, 2))

    assert(b.isClosed)
    assert(closed)
  }

  test("Drop and take") {
    val a = Signal[Int]()
    val b = a.drop(1).take(2)

    val buffer = mutable.ArrayBuilder.make[Int]
    b.foreach { n =>
      buffer.addOne(n)
    }

    a ! 1
    assert(waitForResult(a, 1))
    assertEquals(buffer.result().toSeq, Seq.empty)

    a ! 2
    assert(waitForResult(a, 2))
    assert(waitForResult(b, 2))
    assertEquals(buffer.result().toSeq, Seq(2))

    a ! 3
    assert(waitForResult(a, 3))
    assert(waitForResult(b, 3))
    assertEquals(buffer.result().toSeq, Seq(2, 3))
    assert(b.isClosed)

    a ! 4
    assert(waitForResult(a, 4))
    assert(waitForResult(b, 3))
    assertEquals(buffer.result().toSeq, Seq(2, 3))
    assert(b.isClosed)
  }

  test("Take and drop") {
    val a = Signal[Int]()
    val c = a.take(2).drop(1)

    val cBuffer = mutable.ArrayBuilder.make[Int]
    c.foreach {cBuffer.addOne}

    a ! 1
    assert(waitForResult(a, 1))

    a ! 2
    assert(waitForResult(a, 2))

    a ! 3
    assert(waitForResult(a, 3))

    a ! 4
    assert(waitForResult(a, 4))

    val cSeq = cBuffer.result().toSeq
    assertEquals(cSeq, Seq(2))
  }

  test("Split a signal in half") {
    val a = Signal[Int]()

    val (b, c) = a.splitAt(2)

    val bBuffer = mutable.ArrayBuilder.make[Int]
    b.foreach {bBuffer.addOne}
    val cBuffer = mutable.ArrayBuilder.make[Int]
    c.foreach {cBuffer.addOne}

    a ! 1
    a ! 1
    assert(waitForResult(a, 1))

    a ! 2
    assert(waitForResult(a, 2))

    a ! 3
    assert(waitForResult(a, 3))

    a ! 4
    assert(waitForResult(a, 4))

    assertEquals(bBuffer.result().toSeq, Seq(1, 2))
    assertEquals(cBuffer.result().toSeq, Seq(3, 4))
  }

  test("Split a signal into a head future and tail stream") {
    import Signal.`::`
    val a = Signal[Int]()
    val (head, tail) = a match {
      case head :: tail => (head, tail)
    }

    var hn = 0
    head.foreach(n => hn = n)

    val buffer = mutable.ArrayBuilder.make[Int]
    tail.foreach { n =>
      buffer.addOne(n)
    }

    a ! 1
    assert(waitForResult(a, 1))
    a ! 2
    assert(waitForResult(a, 2))
    a ! 3
    assert(waitForResult(a, 3))

    assertEquals(hn, 1)

    val seq = buffer.result().toSeq
    assertEquals(seq, Seq(2, 3))
  }

  test("Take and use .last to get the last of the taken elements") {
    val a: SourceSignal[Int] = Signal[Int]()

    val c = a.take(2)
    val cBuffer = mutable.ArrayBuilder.make[Int]
    c.foreach {cBuffer.addOne}

    val f = c.last
    var fValue: Int = 0
    f.foreach(fValue = _)

    a ! 1
    assert(waitForResult(a, 1))
    a ! 2
    assert(waitForResult(a, 2))
    a ! 3
    assert(waitForResult(a, 3))

    assertEquals(cBuffer.result().toSeq, Seq(1, 2))
    assertEquals(fValue, 2)
  }

  test("Take and use .init to get all but the last element") {
    val a: SourceSignal[Int] = Signal[Int]()

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

}
