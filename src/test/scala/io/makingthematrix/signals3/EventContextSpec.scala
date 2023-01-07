package io.makingthematrix.signals3

class EventContextSpec extends munit.FunSuite {
  private var received = Seq[Int]()
  private val capture = (value: Int) => received = received :+ value

  override def beforeEach(context: BeforeEach): Unit = {
    received = Seq.empty
  }

  test("Pausing, resuming and destroying the global event context") {
    implicit val ec: EventContext = EventContext.Global
    val s = Signal(1)
    s.onCurrent(capture)

    assertEquals(ec.isContextStarted, true)
    assertEquals(s.hasSubscribers, true)

    ec.stop()
    s ! 2
    assertEquals(ec.isContextStarted, true)
    assertEquals(s.hasSubscribers, true)

    ec.start()
    s ! 3
    assertEquals(ec.isContextStarted, true)
    assertEquals(s.hasSubscribers, true)

    ec.destroy()
    s ! 4
    assertEquals(ec.isContextStarted, true)
    assertEquals(s.hasSubscribers, true)

    assertEquals(received, Seq(1, 2, 3, 4))
  }

  test("Pausing, resuming and destroying a normal event context") {
    implicit val ec: EventContext = EventContext()

    val s = Signal(0)
    s.onCurrent(capture)
    assertEquals(s.hasSubscribers, true)
    Seq(1, 2).foreach(s ! _)
    s ! 3
    assertEquals(s.hasSubscribers, true)

    ec.stop()
    Seq(4, 5).foreach(s ! _)
    assertEquals(ec.isContextStarted, false)
    assertEquals(s.hasSubscribers, false)

    ec.start()
    Seq(6, 7).foreach(s ! _)
    assertEquals(ec.isContextStarted , true)
    assertEquals(s.hasSubscribers, true)

    ec.destroy()
    Seq(8, 9).foreach(s ! _)
    assertEquals(ec.isContextStarted, false)
    assertEquals(s.hasSubscribers, false)

    assertEquals(received, Seq(0, 1, 2, 3, 5, 6, 7))
  }
}
