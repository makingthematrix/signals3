package io.github.makingthematrix.signals3

import testutils.*

class DoneSignalSpec extends munit.FunSuite {
  import Threading.defaultContext

  test("state is false initially and true after done()") {
    val ds = DoneSignal()

    // initially false
    assert(waitForResult(ds, false))
    assertEquals(ds.state, false)

    // mark as done
    ds.done()

    // flips to true and state reflects it
    assert(waitForResult(ds, true))
    assertEquals(ds.state, true)
  }

  test("doneIf only marks done when predicate is true") {
    val ds = DoneSignal()

    ds.doneIf(false)
    assert(waitForResult(ds, false))
    assertEquals(ds.state, false)

    ds.doneIf(true)
    assert(waitForResult(ds, true))
    assertEquals(ds.state, true)
  }

  test("onDone registered after completion runs exactly once") {
    val ds = DoneSignal()
    val counter = Signal(0)

    ds.done()

    ds.onDone { counter.mutate(_ + 1) }

    // should trigger once on registration (already done)
    waitForResult(counter, 1)

    // further done() calls don't re-emit (no duplicate call)
    ds.done()
    awaitAllTasks
    assert(waitForResult(counter, 1))
  }

  test("register multiple onDone handlers after done; each runs once") {
    val ds = DoneSignal()
    val counter = Signal(0)

    ds.done()

    ds.onDone { counter.mutate(_ + 1) }
    ds.onDone { counter.mutate(_ + 1) }

    waitForResult(counter, 2)
  }
}
