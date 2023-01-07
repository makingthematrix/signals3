package io.makingthematrix.signals3

class EventStreamWithAuxSignalSpec extends munit.FunSuite {
  private lazy val aux = new SourceSignal[Int](None)
  private lazy val e = new SourceStream[String]()
  private lazy val r = new EventStreamWithAuxSignal(e, aux)

  private var events = List.empty[(String, Option[Int])]

  test("Subscribe, send stuff, unsubscribe, send more stuff") {
    val sub = r.onCurrent { r =>
      events = r :: events
    }(EventContext.Global)

    assertEquals(events, List.empty)

    e ! "meep"
    assertEquals(events, List(("meep", None)))

    aux ! 1
    assertEquals(events, List(("meep", None)))

    e ! "foo"
    assertEquals(events, List(("foo", Some(1)), ("meep", None)))

    e ! "meep"
    assertEquals(events, List(("meep", Some(1)), ("foo", Some(1)), ("meep", None)))

    aux ! 2
    assertEquals(events.size, 3)

    e ! "meep"
    assertEquals(events.size, 4)
    assertEquals(events.head, ("meep", Some(2)))

    sub.unsubscribe()

    e ! "foo"
    aux ! 3

    assertEquals(events.size, 4)
  }
}
