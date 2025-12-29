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

  test("state reflects current flag value") {
    val a = FlagSignal() // starts false
    assertEquals(a.state, false)

    a.set()
    waitForResult(a, true)
    assertEquals(a.state, true)

    a.clear()
    waitForResult(a, false)
    assertEquals(a.state, false)

    a.toggle()
    waitForResult(a, true)
    assertEquals(a.state, true)
  }

  test("setIf/clearIf/toggleIf respect predicate") {
    val a = FlagSignal() // false

    a.setIf(false)
    awaitAllTasks
    assertEquals(a.state, false)

    a.setIf(true)
    waitForResult(a, true)
    assertEquals(a.state, true)

    a.clearIf(false)
    awaitAllTasks
    assertEquals(a.state, true)

    a.clearIf(true)
    waitForResult(a, false)
    assertEquals(a.state, false)

    a.toggleIf(false)
    awaitAllTasks
    assertEquals(a.state, false)

    a.toggleIf(true)
    waitForResult(a, true)
    assertEquals(a.state, true)
  }

  test("onSet triggers when value becomes true (once per change)") {
    val a = FlagSignal() // false
    var count = 0
    a.onSet { count += 1 }
    // Flush initial callback (state is false, so no increment expected)
    awaitAllTasks
    assertEquals(count, 0)

    a.set()
    waitForResult(a, true)
    awaitAllTasks
    assertEquals(count, 1)

    // Setting true again doesn't emit duplicate value
    a.set()
    awaitAllTasks
    assertEquals(count, 1)

    a.clear()
    waitForResult(a, false)
    awaitAllTasks
    assertEquals(count, 1)

    a.toggle() // false -> true
    waitForResult(a, true)
    awaitAllTasks
    assertEquals(count, 2)
  }

  test("onClear triggers when value becomes false (includes initial false)") {
    val a = FlagSignal() // false
    var count = 0
    a.onClear { count += 1 }

    // Called for initial false (allow async delivery)
    awaitAllTasks
    assertEquals(count, 1)

    a.set()
    waitForResult(a, true)
    assertEquals(count, 1)

    a.clear()
    waitForResult(a, false)
    awaitAllTasks
    assertEquals(count, 2)

    // Clearing again (no value change) shouldn't call
    a.clear()
    awaitAllTasks
    assertEquals(count, 2)

    a.toggle() // false -> true
    waitForResult(a, true)
    awaitAllTasks
    assertEquals(count, 2)

    a.toggle() // true -> false
    waitForResult(a, false)
    awaitAllTasks
    assertEquals(count, 3)
  }

  test("onToggle fires immediately and on each subsequent change") {
    val a = FlagSignal() // false
    var count = 0
    a.onToggle { count += 1 }

    // Immediate call on subscription with current value (allow async delivery)
    awaitAllTasks
    assertEquals(count, 1)

    a.set()
    waitForResult(a, true)
    awaitAllTasks
    assertEquals(count, 2)

    a.clear()
    waitForResult(a, false)
    awaitAllTasks
    assertEquals(count, 3)

    a.toggle()
    waitForResult(a, true)
    awaitAllTasks
    assertEquals(count, 4)
  }
}
