package io.github.makingthematrix.signals3

class EventContextSpec extends munit.FunSuite {
  private var received = Seq[Int]()
  private val capture = (value: Int) => received = received :+ value

  // A lightweight stub to observe EventContext -> Subscription interactions
  private class TestSubscription extends Subscription {
    var subscribeCount = 0
    var unsubscribeCount = 0
    var destroyCount = 0

    var enabledCalled = 0
    var disabledCalled = 0
    var pauseDisabled = false

    def enable(): Unit = enabledCalled += 1
    def disable(): Unit = disabledCalled += 1
    def destroy(): Unit = destroyCount += 1
    def disablePauseWithContext(): Unit = pauseDisabled = true
    def subscribe(): Unit = subscribeCount += 1
    def unsubscribe(): Unit = unsubscribeCount += 1
  }

  override def beforeEach(context: BeforeEach): Unit =
    received = Seq.empty

  test("Pausing, resuming and destroying the global event context") {
    given ec: EventContext = EventContext.Global
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
    given ec: EventContext = EventContext()

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

  test("isContextDestroyed flags for Global and normal contexts") {
    val global = EventContext.Global
    assertEquals(global.isContextDestroyed, false)
    global.destroy()
    // Global is a no-op; still false
    assertEquals(global.isContextDestroyed, false)

    val ec = EventContext()
    assertEquals(ec.isContextDestroyed, false)
    ec.destroy()
    assertEquals(ec.isContextDestroyed, true)
  }

  test("register while started subscribes immediately; while stopped defers until start") {
    val ec = EventContext()
    assertEquals(ec.isContextStarted, true)

    val sub1 = TestSubscription()
    val r1 = ec.register(sub1)
    assertEquals(r1, true)
    assertEquals(sub1.subscribeCount, 1) // started context subscribes immediately

    ec.stop()
    assertEquals(ec.isContextStarted, false)
    val sub2 = TestSubscription()
    val r2 = ec.register(sub2)
    assertEquals(r2, true)
    assertEquals(sub2.subscribeCount, 0) // deferred subscription

    ec.start()
    assertEquals(ec.isContextStarted, true)
    assertEquals(sub2.subscribeCount, 1) // subscribed upon start
  }

  test("duplicate register returns false and does not double-register") {
    val ec = EventContext()
    val sub = TestSubscription()
    assertEquals(ec.register(sub), true)
    // First register in started context subscribes once
    assertEquals(sub.subscribeCount, 1)

    // Duplicate register should be ignored
    assertEquals(ec.register(sub), false)
    assertEquals(sub.subscribeCount, 1)
  }

  test("register after destroy returns false and does nothing") {
    val ec = EventContext()
    ec.destroy()
    val sub = TestSubscription()
    assertEquals(ec.register(sub), false)
    assertEquals(sub.subscribeCount, 0)
  }

  test("unregister removes subscription from context management (no stop/destroy callbacks)") {
    val ec = EventContext()

    // Register sub1 and then unregister: subsequent stop should NOT call unsubscribe on it
    val sub1 = TestSubscription()
    assertEquals(ec.register(sub1), true)
    assertEquals(sub1.subscribeCount, 1)
    ec.unregister(sub1)
    ec.stop()
    assertEquals(sub1.unsubscribeCount, 0)

    // Register sub2 and keep it registered: stop should call unsubscribe once
    ec.start()
    val sub2 = TestSubscription()
    assertEquals(ec.register(sub2), true)
    assertEquals(sub2.subscribeCount, 1)
    ec.stop()
    assertEquals(sub2.unsubscribeCount, 1)

    // Register sub3 and ensure destroy triggers its destroy callback
    val sub3 = TestSubscription()
    ec.start()
    assertEquals(ec.register(sub3), true)
    ec.destroy()
    assertEquals(sub3.destroyCount, 1)
  }
}
