package io.github.makingthematrix.signals3

import munit.FunSuite

import scala.concurrent.duration.*

/**
	* Tests of the [[Initializable]] trait: the initialization state machine, the failure-and-retry
	* semantics of `initialize`, the initialization signal, and the ordering of the `onInit` behaviors.
	*/
class InitializableSpec extends FunSuite {
  import EventContext.Implicits.global
  import Threading.defaultContext
  import Utils.*

  given Timeout: FiniteDuration = 3.seconds

  test("initialize runs the onInit behaviors exactly once") {
    val initializable = new Initializable { }
    var count = 0
    initializable.onInit { count += 1 }

    assert(!initializable.isInitialized)
    initializable.initialize()
    assert(initializable.isInitialized)
    assertEquals(count, 1)

    // a second call to initialize is a no-op - the behaviors don't run again
    initializable.initialize()
    assertEquals(count, 1)
  }

  test("a failed initialize is reported and rolled back, and can be retried") {
    val initializable = new Initializable { }
    var attempts = 0
    var failed: Option[Throwable] = None
    initializable.onInit { attempts += 1; if (attempts == 1) throw new RuntimeException("init failed") }
    initializable.onInitFailed { t => failed = Some(t) }

    initializable.initialize()
    // the exception is reported to the failure behaviors and the initialization is rolled back
    assert(failed.exists(_.getMessage.contains("init failed")))
    assert(!initializable.isInitialized)

    // the initialization can be retried: the behaviors run again and this time succeed
    initializable.initialize()
    assert(initializable.isInitialized)
    assertEquals(attempts, 2)
  }

  test("onInit behaviors run in reverse registration order") {
    val initializable = new Initializable { }
    val order = scala.collection.mutable.ListBuffer[String]()
    initializable.onInit { order += "first" }
    initializable.onInit { order += "second" }
    initializable.onInit { order += "third" }

    initializable.initialize()

    // Callbacks are prepended to the list, so the last registered runs first
    assertEquals(order.toList, List("third", "second", "first"))
  }

  test("isInitializedSignal flips to true when the object is initialized") {
    val initializable = new Initializable { }
    val signal = initializable.isInitializedSignal
    assert(!signal.currentValue.getOrElse(false))

    initializable.initialize()
    assert(waitFor(signal, true))
  }
}
