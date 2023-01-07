package io.makingthematrix.signals3

import testutils.{result, waitForResult}

class ScanSignalSpec extends munit.FunSuite {
  test("Normal scanning") {
    val received = Signal(Seq.empty[Int])
    val capture: Int => Unit = { value => received.mutate(_ :+ value) }

    val s = Signal(1)
    val scanned = s.scan(0)(_ + _)
    assertEquals(result(scanned.future), 1)

    scanned.onCurrent(capture)
    assertEquals(result(scanned.future), 1)
    waitForResult(received, Seq(1))

    Seq(2, 3, 1).foreach(s ! _)

    waitForResult(received, Seq(1, 3, 6, 7))
    assertEquals(result(scanned.future), 7)
  }

  test("disable autowiring when fetching current value") {
    val s = Signal(1)
    val scanned = s.scan(0)(_ + _)
    assertEquals(result(scanned.future), 1)

    Seq(2, 3, 1).foreach(s ! _)
    waitForResult(s, 1)
    assertEquals(result(scanned.future), 7)
  }

  test("Chained scanning") {
    val received = Signal(Seq.empty[Int])
    val capture: Int => Unit = { value => received.mutate(_ :+ value) }

    val s = Signal(1)
    val scanned = s.scan(0)(_ + _).scan(1)(_ * _)
    assertEquals(result(scanned.future), 1)

    scanned.onCurrent(capture)
    Seq(2, 3, 1).foreach(s ! _)
    waitForResult(s, 1)

    assertEquals(result(scanned.future), 3 * 6 *7)
    waitForResult(received, Seq(1, 3, 3 * 6, 3 * 6 * 7))
  }

  test("No subscribers will be left behind") {
    val received = Signal(Seq.empty[Int])
    val capture: Int => Unit = { value => received.mutate(_ :+ value) }

    val s = Signal(1)
    val scanned = s.scan(0)(_ + _)
    val sub = scanned.onCurrent(capture)
    Seq(2, 3) foreach (s ! _)
    assert(s.hasSubscribers)
    assert(scanned.hasSubscribers)
    sub.destroy()
    assert(!s.hasSubscribers)
    assert(!scanned.hasSubscribers)
    s ! 4
    waitForResult(received, Seq(1, 3, 6))
  }
}
