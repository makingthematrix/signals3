package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Finite.FiniteSignal
import testutils.*

import scala.collection.mutable

class FlagSignalSpec extends munit.FunSuite {
  import Serialized.dispatcher

  test("Take and use .last to get the last of the taken elements (FlagSignal)") {
    val a = FlagSignal() // starts with false

    val taken: FiniteSignal[Boolean] = a.take(2)
    val last = taken.last

    // Emit just one change to reach 2 taken values (initial false, then true)
    a.set()   // false -> true
    waitForResult(taken, true)
    assertEquals(testutils.result(last), true)
    waitForResult(taken.isClosedSignal, true)

    // Further changes should not be delivered to 'taken'
    a.toggle() // true -> false
    waitForResult(a, false)
  }

  test("Take and use .init to get all but the last element (FlagSignal)") {
    val a = FlagSignal() // starts with false

    val taken = a.take(3)

    val init = taken.init

    // Produce exactly 2 changes so that 'taken' closes after collecting 3
    a.set()    // false -> true
    waitForResult(taken, true)
    waitForResult(init, true)
    waitForResult(init.isClosedSignal, true)
    a.toggle() // true -> false
    waitForResult(taken, false)
    waitForResult(taken.isClosedSignal, true)


    // Additional changes shouldn't be delivered to 'taken' nor 'init'
    a.set()    // false -> true
    waitForResult(a, true)
  }

  test("Empty take signal is already closed (FlagSignal)") {
    val a = FlagSignal()
    val taken0 = a.take(0)
    assert(taken0.isClosed)

    val bBuffer = mutable.ArrayBuilder.make[Boolean]
    taken0.onCurrent((x: Boolean) => bBuffer.addOne(x))

    // Try to change the flag a few times – 'b' should never receive anything
    a.set()
    awaitAllTasks
    a.clear()
    awaitAllTasks

    assertEquals(bBuffer.result().toSeq, Seq.empty[Boolean])
  }

  test("Group the FlagSignal with grouped(2)") {
    val a = FlagSignal() // initial value false participates in grouping
    val b: Signal[Seq[Boolean]] = a.grouped(2)

    val buffer = scala.collection.mutable.ArrayBuffer.empty[Seq[Boolean]]
    b.onCurrent(seq => buffer += seq)

    // Produce 3 value changes; with the initial 'false' this yields two groups of size 2
    a.set()   // false -> true
    awaitAllTasks
    a.clear() // true -> false
    awaitAllTasks
    a.set()   // false -> true
    awaitAllTasks

    val res = buffer.toSeq
    assertEquals(res.size, 1)
    assertEquals(res(0), Seq(false, true))
  }

  test("Group the FlagSignal by change in value") {
    val a = FlagSignal()
    var last: Option[Boolean] = None
    val b: Signal[Seq[Boolean]] = a.groupBy { v =>
      val startNew = last.exists(_ != v)
      last = Some(v)
      startNew
    }

    val buffer = scala.collection.mutable.ArrayBuffer.empty[Seq[Boolean]]
    b.onCurrent(seq => buffer += seq)

    a.set()   // false -> true -> should release first group [false]
    awaitAllTasks
    a.clear() // true -> false -> should release second group [true]
    awaitAllTasks
    a.set()   // false -> true (third group starts, but not released)
    awaitAllTasks

    val res = buffer.toSeq
    assertEquals(res.size, 2)
    assertEquals(res(0), Seq(false, true))
    assertEquals(res(1), Seq(false))
  }
}
