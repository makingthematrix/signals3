package io.makingthematrix.signals3

import testutils.{awaitAllTasks, result}

class FlatMapSignalSpec extends munit.FunSuite {

  private var received = Vector.empty[Int]
  private val capture = (value: Int) => received :+= value

  override def beforeEach(context: BeforeEach): Unit = {
    received = Vector.empty[Int]
  }

  test("Normal flatmapping") {
    val s = Signal(0)
    val s1 = Signal(1)
    val s2 = Signal(2)

    val fm = s.flatMap { Seq(s1, s2) }
    fm.onCurrent(capture)

    assertEquals(fm.value, Some(1))
    s ! 1
    assertEquals(fm.value, Some(2))
    s1 ! 3
    assertEquals(fm.value, Some(2))
    s2 ! 4
    assertEquals(fm.value, Some(4))
    assertEquals(received, Vector(1, 2, 4))
  }

  test("Normal flattening") {
    val s = Signal(0)
    val s1 = Signal(1)
    val s2 = Signal(2)

    val fm = s.map { Seq(s1, s2) }.flatten
    fm.onCurrent(capture)

    assertEquals(fm.value, Some(1))
    s ! 1
    assertEquals(fm.value, Some(2))
    s1 ! 3
    assertEquals(fm.value, Some(2))
    s2 ! 4
    assertEquals(fm.value, Some(4))
    assertEquals(received, Vector(1, 2, 4))
  }

  test("Chained flatmapping") {
    val s = Seq.fill(6)(Signal(0))

    val fm = s(0).flatMap { Seq(s(1), s(2)) }.flatMap { Seq(s(3), s(4), s(5)) }
    fm.onCurrent(capture)

    s(5) ! 5
    s(2) ! 2
    s(0) ! 1

    assertEquals(fm.value, Some(5))
    assertEquals(received, Vector(0, 5))
  }

  test("Chained flattening") {
    val s = Seq.fill(6)(Signal(0))

    val fm = s(0).map { Seq(s(1), s(2)) }.flatten.flatMap { Seq(s(3), s(4), s(5)) }
    fm.onCurrent(capture)

    s(5) ! 5
    s(2) ! 2
    s(0) ! 1

    assertEquals(fm.value, Some(5))
    assertEquals(received, Vector(0, 5))
  }

  test("FlatMapping an empty signal") {
    val signal = Signal[Int]()
    val chain = signal.flatMap(_ => Signal(42))

    assert(chain.empty)
    signal ! Int.MaxValue
    assertEquals(result(chain.future), 42)
  }

  test("FlatMapping to an empty signal") {
    val signal = Signal(0)
    val signalA = Signal[String]()
    val signalB = Signal[String]()
    val chain = signal.flatMap(n => if (n % 2 == 0) signalA else signalB)
    val fan = Follower(chain).subscribed
    assert(chain.empty)
    assertEquals(fan.received, Vector.empty)

    signalA ! "a"
    assertEquals(result(chain.future), "a")
    assertEquals(fan.received, Vector("a"))

    signal ! 1
    assert(chain.empty)
    assertEquals(fan.received, Vector("a"))

    signalA ! "aa"
    assert(chain.empty)
    assertEquals(fan.received, Vector("a"))

    signalB ! "b"
    assertEquals(result(chain.future), "b")
    assertEquals(fan.received, Vector("a", "b"))
  }


  test("No subscribers will be left behind") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val s = Signal(0)
    val s1 = Signal(1)
    val s2 = Signal(2)

    val fm = s.flatMap { Seq(s1, s2) }
    val sub = fm.foreach(capture)

    s1 ! 3
    s2 ! 4

    assert(s.hasSubscribers)
    assert(s1.hasSubscribers)
    assert(!s2.hasSubscribers)
    assert(fm.hasSubscribers)

    sub.destroy()
    assert(!s.hasSubscribers)
    assert(!s1.hasSubscribers)
    assert(!s2.hasSubscribers)
    assert(!fm.hasSubscribers)

    s1 ! 5
    s ! 1
    s2 ! 6
    awaitAllTasks
    assertEquals(received, Vector(1, 3))
  }

  test("wire and un-wire both source signals") {
    val s1 = Signal[Int]()
    val s2 = Signal[Int]()
    val s = s1.flatMap { _ => s2 }

    assert(!s1.wired)
    assert(!s.wired)
    val o = s.foreach { _ => () }

    assert(s1.wired)
    assert(s.wired)

    o.disable()
    assert(!s1.wired)
    assert(!s.wired)
  }

  test("un-wire discarded signal on change") {
    val s = Signal[Boolean](true)
    val sstr = Signal[String]()
    val s1 = sstr.map(_.length)
    val s2 = sstr.map(str => if (str.contains("a")) 1 else 0)

    val fm = s.flatMap {
      case true  => s1
      case false => s2
    }

    val o = fm.foreach(_ => ())

    assert(s.wired)
    assert(s1.wired)
    assert(!s2.wired)

    s ! false

    assert(s.wired)
    assert(!s1.wired)
    assert(s2.wired)

    o.destroy()
    assert(!s.wired)
    assert(!s1.wired)
    assert(!s2.wired)
  }

  test("update value when wired") {
    val s = Signal[Int](0)
    val fm = s.flatMap(Signal(_))

    assertEquals(s.value, Some(0))
    assertEquals(fm.value, None)

    s ! 1
    assertEquals(s.value, Some(1))
    assertEquals(fm.value, None) // not updated because signal is not autowired
    fm.foreach(_ => ())

    assertEquals(fm.value, Some(1)) // updated when wiring
  }

  test("possibly stale value after re-wiring") {
    val source = Signal(1)
    val chain = source.flatMap(n => if (n % 2 == 0) Signal(n) else Signal[Int]()).map(identity)
    val fan = Follower(chain).subscribed
    source ! 2
    assertEquals(fan.received, Vector(2))
    chain.unsubscribeAll()

    (3 to 7).foreach { source ! _ }

    assert(chain.empty)
    fan.subscribed
    assertEquals(fan.received, Vector(2))
    source ! 8
    assertEquals(fan.received, Vector(2, 8))
  }
}
