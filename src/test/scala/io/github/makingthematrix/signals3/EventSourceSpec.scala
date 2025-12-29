package io.github.makingthematrix.signals3

import scala.concurrent.ExecutionContext

class EventSourceSpec extends munit.FunSuite {

  // A Stream subclass that exposes hooks and notify for testing EventSource behavior
  private class TestStream[E] extends Stream[E] {
    @volatile var onWireCount = 0
    @volatile var onUnwireCount = 0

    override protected def onWire(): Unit = onWireCount += 1
    override protected def onUnwire(): Unit = onUnwireCount += 1

    // Expose the protected notifySubscribers for tests
    def testNotify(call: Stream.EventSubscriber[E] => Unit): Unit =
      notifySubscribers(call)
  }

  private class DummySubscriber[E] extends Stream.EventSubscriber[E] {
    var events: Vector[(E, Option[ExecutionContext])] = Vector.empty
    override protected[signals3] def onEvent(event: E, currentContext: Option[ExecutionContext]): Unit =
      events = events :+ (event -> currentContext)
  }

  test("subscribe/unsubscribe toggles hasSubscribers; onWire on first subscribe; onUnwire when empty") {
    val s = TestStream[Int]()
    val sub = DummySubscriber[Int]()

    assertEquals(s.hasSubscribers, false)

    // First subscribe -> onWire
    s.subscribe(sub)
    assertEquals(s.hasSubscribers, true)
    assertEquals(s.onWireCount, 1)

    // Duplicate subscribe should not re-wire or add another sub
    s.subscribe(sub)
    assertEquals(s.hasSubscribers, true)
    assertEquals(s.onWireCount, 1)

    // Unsubscribe -> last removal triggers onUnwire
    s.unsubscribe(sub)
    assertEquals(s.hasSubscribers, false)
    assertEquals(s.onUnwireCount, 1)

    // Unsubscribing again still calls onUnwire (implementation calls it when empty and autowiring enabled)
    s.unsubscribe(sub)
    assertEquals(s.onUnwireCount, 2)
  }

  test("notifySubscribers invokes all subscribers with the provided call") {
    val s = TestStream[Int]()
    val a = DummySubscriber[Int]()
    val b = DummySubscriber[Int]()

    s.subscribe(a)
    s.subscribe(b)

    s.testNotify(_.onEvent(42, None))

    assertEquals(a.events.map(_._1), Vector(42))
    assertEquals(b.events.map(_._1), Vector(42))
  }

  test("onWire called only on first subscriber; onUnwire only on removing last among many") {
    val s = TestStream[Int]()
    val a = DummySubscriber[Int]()
    val b = DummySubscriber[Int]()

    s.subscribe(a)
    assertEquals(s.onWireCount, 1)

    s.subscribe(b)
    assertEquals(s.onWireCount, 1) // still 1 (already wired)
    assertEquals(s.onUnwireCount, 0)

    s.unsubscribe(a)
    assertEquals(s.hasSubscribers, true)
    assertEquals(s.onUnwireCount, 0) // not last

    s.unsubscribe(b)
    assertEquals(s.hasSubscribers, false)
    assertEquals(s.onUnwireCount, 1)
  }

  test("disableAutowiring wires immediately when there are no subscribers and prevents unwire") {
    val s = TestStream[Int]()

    // No subscribers yet; disable autowiring should force onWire and wired=true
    s.disableAutowiring()
    assertEquals(s.onWireCount, 1)
    assertEquals(s.hasSubscribers, false)
    assertEquals(s.wired, true)

    // Adding/removing subscribers should not call onUnwire (autowiring disabled)
    val a = DummySubscriber[Int]()
    s.subscribe(a)
    assertEquals(s.onWireCount, 1) // no extra wire (already wired by disable)
    s.unsubscribe(a)
    assertEquals(s.onUnwireCount, 0)
    assertEquals(s.wired, true)
  }

  test("duplicate unsubscribe on empty still calls onUnwire (autowiring enabled)") {
    val s = TestStream[Int]()
    val sub = DummySubscriber[Int]()
    s.subscribe(sub)
    assertEquals(s.onWireCount, 1)

    s.unsubscribe(sub)
    assertEquals(s.onUnwireCount, 1)

    // Second call triggers onUnwire again because the event source is empty and autowiring is enabled
    s.unsubscribe(sub)
    assertEquals(s.onUnwireCount, 2)
  }

  test("NoAutowiring mixin wires immediately and never unwires on last unsubscribe") {
    class NoAutoStream[E] extends Stream[E] with EventSource.NoAutowiring {
      @volatile var onWireCount = 0
      @volatile var onUnwireCount = 0
      override protected def onWire(): Unit = onWireCount += 1
      override protected def onUnwire(): Unit = onUnwireCount += 1
    }

    val s = NoAutoStream[Int]()
    // NoAutowiring enforces initialization; at minimum 'wired' should be true immediately
    assertEquals(s.wired, true)

    val a = DummySubscriber[Int]()
    s.subscribe(a)
    s.unsubscribe(a)
    // Autowiring was disabled by the mixin; removing last subscriber should not call onUnwire
    assertEquals(s.onUnwireCount, 0)
  }
}
