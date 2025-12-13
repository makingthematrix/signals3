package io.github.makingthematrix.signals3

import testutils.waitForResult

class MapSignalSpec extends munit.FunSuite {
  import EventContext.Implicits.global
  import Threading.defaultContext

  test("Normal mapping") {
    val received = Signal(Seq.empty[Int])
    val capture: Int => Unit = { value => received.mutate(_ :+ value) }

    val s = Signal(1)
    val m = s.map(_ * 2)
    m.onCurrent(capture)

    Seq(2, 3, 1) foreach (s ! _)
    waitForResult(received, Seq(2, 4, 6, 2))
  }

  test("Mapping nulls") {
    val vv = Signal(Option("invalid"))
    val s = Signal("start")
    val m = s.map(Option(_))
    m.foreach { vv ! _ }
    waitForResult(vv, Some("start"))
    s ! "meep"
    waitForResult(vv, Some("meep"))
    s ! null
    waitForResult(vv, None)
    s ! "moo"
    waitForResult(vv, Some("moo"))
  }

  test("Chained mapping") {
    val received = Signal(Seq.empty[Int])
    val capture: Int => Unit = { value => received.mutate(_ :+ value) }

    val s = Signal(1)
    val m = s.map(_ * 2).map(_ * 3)
    m.onCurrent(capture)
    Seq(2, 3, 1).foreach(s ! _)
    waitForResult(received, Seq(6, 12, 18, 6))
  }

  test("No subscribers will be left behind") {
    val received = Signal(Seq.empty[Int])
    val capture: Int => Unit = { value => received.mutate(_ :+ value) }

    val s = Signal(1)
    val f = s.map (_ * 2)
    val sub = f.onCurrent(capture)
    Seq(2, 3) foreach (s ! _)
    assert(s.hasSubscribers)
    assert(f.hasSubscribers)
    sub.destroy()
    assert(!s.hasSubscribers)
    assert(!f.hasSubscribers)
    s ! 4
    waitForResult(received, Seq(2, 4, 6))
  }

  test("wire and un-wire a mapped signal") {
    lazy val s1 = Signal[Int](0)
    lazy val s = s1.map { _ => 1 }

    assert(!s1.wired)
    val o = s.foreach { _ => () }

    assert(s.wired)

    o.disable()

    assert(!s.wired)

    o.enable()
    assert(s.wired)

    o.destroy()
    assert(!s.wired)
  }
}
