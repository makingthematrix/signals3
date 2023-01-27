package io.makingthematrix.signals3

import testutils.{awaitAllTasks, waitForResult}

class FlatMapEventStreamSpec extends munit.FunSuite {
  test("Normal flatmapping") {
    given dispatchQueue: DispatchQueue = SerialDispatchQueue()
    val received = Signal(Seq.empty[Int])
    val capture: Int => Unit = { value => received.mutate(_ :+ value) }

    val switch = EventStream[Boolean]()
    val source1 = EventStream[Int]()
    val source2 = EventStream[Int]()

    val result = switch.flatMap {
      case true  => source1
      case false => source2
    }
    result.foreach(capture)

    waitForResult(received, Seq.empty)
    source1 ! 1
    awaitAllTasks
    waitForResult(received, Seq.empty) // result not set yet
    source2 ! 2
    awaitAllTasks
    waitForResult(received, Seq.empty) // result not set yet

    switch ! true

    source2 ! 2
    awaitAllTasks
    waitForResult(received, Seq.empty) // result set to source1, so events from source2 are ignored
    source1 ! 1
    waitForResult(received, Seq(1))  // yay!
    source1 ! 1
    waitForResult(received, Seq(1, 1))  // yay!

    switch ! false

    source1 ! 1
    awaitAllTasks
    waitForResult(received, Seq(1, 1))  // no 3x1 because result now switched to source2
    source2 ! 2
    waitForResult(received, Seq(1, 1, 2))  // yay!

    switch ! true

    source2 ! 2
    awaitAllTasks
    waitForResult(received, Seq(1, 1, 2)) // result switched back to source1, so events from source2 are ignored again
    source1 ! 1
    waitForResult(received, Seq(1, 1, 2, 1))  // yay!
  }

  test("Chained flatmapping") {
    given dispatchQueue: DispatchQueue = SerialDispatchQueue()
    val received = Signal(Seq.empty[Int])
    val capture: Int => Unit = { value => received.mutate(_ :+ value) }

    val switch  = EventStream[Boolean]()
    val switch1 = EventStream[Boolean]()
    val switch2 = EventStream[Boolean]()
    val source1 = EventStream[Int]()
    val source2 = EventStream[Int]()

    val result = for {
      b1  <- switch
      b2  <- if (b1) switch1 else switch2
      res <- if (b2) source1 else source2
    } yield res

    result.foreach(capture)

    waitForResult(received, Seq.empty)

    switch ! true
    awaitAllTasks
    waitForResult(received, Seq.empty)

    switch1 ! true
    source1 ! 1
    waitForResult(received, Seq(1))

    source2 ! 2
    awaitAllTasks
    waitForResult(received, Seq(1))

    switch ! false
    source1 ! 1
    awaitAllTasks
    waitForResult(received, Seq(1))

    switch2 ! false
    source2 ! 2
    source1 ! 1
    waitForResult(received, Seq(1, 2))
  }

  test("No subscribers will be left behind") {
    val received = Signal(Seq.empty[Int])
    val capture: Int => Unit = { value => received.mutate(_ :+ value) }

    val switch = EventStream[Boolean]()
    val source1 = EventStream[Int]()
    val source2 = EventStream[Int]()

    val result = switch.flatMap {
      case true  => source1
      case false => source2
    }

    assert(!result.hasSubscribers)
    assert(!switch.hasSubscribers) // result is lazy; it will subscribe to switch only after it gets its own subscription (or with .disableAutowiring())
    assert(!source1.hasSubscribers)
    assert(!source2.hasSubscribers)

    val sub = result.foreach(capture)

    assert(result.hasSubscribers)
    assert(switch.hasSubscribers) // result is subscribed to switch
    assert(!source1.hasSubscribers)
    assert(!source2.hasSubscribers)

    switch ! true

    assert(result.hasSubscribers)
    assert(switch.hasSubscribers)
    assert(source1.hasSubscribers) // the switch caused result to subscribe to source1
    assert(!source2.hasSubscribers)

    switch ! false

    assert(result.hasSubscribers)
    assert(switch.hasSubscribers)
    assert(!source1.hasSubscribers) // the switch caused result to unsubscribe from source1
    assert(source2.hasSubscribers) // the switch caused result to subscribe to source2

    sub.destroy()

    assert(!result.hasSubscribers)
    assert(!switch.hasSubscribers)
    assert(!source1.hasSubscribers)
    assert(!source2.hasSubscribers)
  }


  test("wire and un-wire both source event streams") {
    val es1 = EventStream[String]()
    val es2 = EventStream[Int]()
    val res = es1.flatMap { _ => es2 }

    assert(!es1.wired)
    assert(!es2.wired)
    val o = res.foreach { _ => () }

    assert(es1.wired)
    assert(!es2.wired)

    es1 ! "aaa"

    assert(es2.wired)

    o.disable()
    assert(!es1.wired)
    assert(!es2.wired)
  }
}
