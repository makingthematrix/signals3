package io.github.makingthematrix.signals3

import testutils.result

class FilterSignalSpec extends munit.FunSuite {

  test("Value of a filtered signal") {
    val source = Signal(1)
    val chain = source.filter(_ % 2 == 0)
    assertEquals(chain.currentValue, None)
    source ! 2
    assertEquals(result(chain.future), 2)
    source ! 3
    assertEquals(chain.currentValue, None)
    source ! 4
    assertEquals(result(chain.future), 4)

  }

  test("Subscribing to a filtered signal") {
    val source = Signal(1)
    val chain = source.filter(_ % 2 == 0)
    val fan = Follower(chain).subscribed
    assertEquals(fan.lastReceived, None)
    source ! 2
    assertEquals(fan.received, Vector(2))
    source ! 3
    assertEquals(fan.received, Vector(2))
    chain.unsubscribeAll()
    assertEquals(fan.received, Vector(2))
    fan.subscribed
    assertEquals(fan.received, Vector(2))
    chain.unsubscribeAll()
    source ! 4
    assertEquals(fan.received, Vector(2))
    fan.subscribed
    assertEquals(fan.received, Vector(2, 4))
  }

  test("Possibly stale value after re-wiring") {
    val source = Signal(1)
    val chain = source.filter(_ % 2 == 0).map(identity)
    val fan = Follower(chain).subscribed
    source ! 2
    assertEquals(fan.received, Vector(2))
    chain.unsubscribeAll()

    (3 to 7) foreach(n => source ! n)

    assertEquals(chain.currentValue, None)
    fan.subscribed
    assertEquals(fan.received, Vector(2))
    source ! 8
    assertEquals(fan.received, Vector(2, 8))
  }
}
