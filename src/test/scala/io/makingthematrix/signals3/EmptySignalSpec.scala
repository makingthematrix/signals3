package io.makingthematrix.signals3

class EmptySignalSpec extends munit.FunSuite {
  test("Value of an uninitialized signal") {
    val signal = Signal[Int]()
    assertEquals(signal.currentValue, None)
    signal ! 1
    assertEquals(signal.currentValue, Some(1))
  }

  test("Subscribing to an uninitialized signal") {
    val signal = Signal[Int]()
    val fan = Follower(signal).subscribed
    assertEquals(fan.lastReceived, None)
    signal ! 1
    assertEquals(fan.lastReceived, Some(1))
  }

  test("Mapping an uninitialized signal") {
    val signal = Signal[Int]()
    val chain = signal.map(_ + 42)
    assertEquals(chain.currentValue, None)
    signal ! 1
    assertEquals(chain.currentValue, Some(43))
  }

  test("Subscribing to a mapped but uninitialized signal") {
    val signal = Signal[Int]()
    val chain = signal.map(_ + 42)
    val fan = Follower(chain).subscribed
    assertEquals(fan.lastReceived, None)
    signal ! 1
    assertEquals(fan.lastReceived, Some(43))
  }

  test("Combining an initialized and an uninitialized signal with a flatMap") {
    val signalA = Signal(1)
    val signalB = Signal[Int]()
    val chain = signalA.flatMap(a => signalB.map(b => a + b))
    assertEquals(chain.currentValue, None)
    signalB ! 42
    assertEquals(chain.currentValue, Some(43))
  }

  test("Subscribing to a flatMapped signal") {
    val signalA = Signal(1)
    val signalB = Signal[Int]()
    val chain = signalA.flatMap(a => signalB.map(b => a + b))
    val fan = Follower(chain).subscribed
    assertEquals(fan.lastReceived, None)
    signalB ! 42
    assertEquals(fan.lastReceived, Some(43))
  }

  test("Zipping with an empty signal") {
    val signalA = Signal(1)
    val signalB = Signal[String]()
    val chain = signalA.zip(signalB)
    val fan = Follower(chain).subscribed
    assertEquals(fan.lastReceived, None)
    signalB ! "one"
    assertEquals(fan.lastReceived, Some((1, "one")))
  }

  test("Combining with an empty signal") {
    val signalA = Signal(1)
    val signalB = Signal[Int]()
    val chain = signalA.combine(signalB)(_ + _)
    val fan = Follower(chain).subscribed
    assertEquals(fan.lastReceived, None)
    signalB ! 42
    assertEquals(fan.lastReceived, Some(43))
  }

  test("Map after filter") {
    val signalA = Signal(1)
    val chain = signalA.filter(_ % 2 == 0).map(_ + 42)
    val fan = Follower(chain).subscribed
    assertEquals(chain.currentValue, None)
    assertEquals(fan.received, Vector.empty)

    signalA ! 2
    assertEquals(chain.currentValue, Some(44))
    assertEquals(fan.received, Vector(44))

    signalA ! 3
    assertEquals(chain.currentValue, None)
    assertEquals(fan.received, Vector(44))

    signalA ! 4
    assertEquals(chain.currentValue, Some(46))
    assertEquals(fan.received, Vector(44, 46))
  }
}
