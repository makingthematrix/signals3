package io.makingthematrix.signals3

import java.util.concurrent.atomic.AtomicReference
import testutils._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import Threading.defaultContext
import CancellableFuture.delayed

import java.lang.Thread.sleep

class ThrottledSignalSpec extends munit.FunSuite:

  test("throttle serial events") {
    100 times spying { spy =>
      val s = Signal(1)
      val m = s.throttle(2.millis)
      m.onCurrent(spy.capture)
      assertEquals(spy.received.get.map(_._1), Vector[Int](1))

      (2 to 3) foreach { v =>
        sleep(1)
        s ! v
        s ! v + 10
      }

      withDelay {
        assertEquals(spy.received.get.last._1, 13)
      }
    }
  }

  test("throttle parallel events")(spying { spy =>
    import spy._

    received.set(Vector.empty[(Int, Long)])
    val s = Signal[Int]()
    val m = s.throttle(50.millis)
    m.onCurrent(capture)

    val updates = Future.sequence((1 to 10000).map(n => delayed(random.nextInt(500).millis) {
      s ! n
      s ! n + 1000
    }.future))

    Await.result(updates, 5.seconds)
    val sorted = received.get.map(_._2).sorted
    val interval = sorted.zip(sorted.tail).map { case (aa, bb) => bb - aa }
    interval.foreach { time => assert(time >= 45L, s"Time should be >45ms but is ${time}ms") }
  })

  test("wire and un-wire throttled signal") {
    lazy val s = Signal[Int](0)
    val m = s.throttle(100.millis)
    assert(!m.wired)

    val o = m.onCurrent { _ => () }
    assert(m.wired)

    o.disable()
    assert(!m.wired)

    o.enable()
    assert(m.wired)

    o.destroy()
    assert(!m.wired)
  }

  test("emit the last change from those received during the same time interval") {
    val s = Signal[Int]()
    val m = s.throttle(100.millis)

    var res: Int = 0
    m.foreach { res = _ }
    s ! 1
    sleep(105)
    assertEquals(res, 1)
    s ! 2
    sleep(5)
    s ! 3
    sleep(5)
    s ! 4
    sleep(105)
    assertEquals(res, 4)
  }

  class Spy:
    val received = new AtomicReference(Vector.empty[(Int, Long)])
    val capture: Int => Unit = { value => compareAndSet(received)(_ :+ (value -> System.currentTimeMillis())) }

  def spying(f: Spy => Unit): Unit = f(new Spy)
