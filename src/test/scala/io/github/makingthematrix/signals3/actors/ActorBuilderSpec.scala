package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.testutils.*
import io.github.makingthematrix.signals3.*
import munit.FunSuite

import scala.concurrent.duration.*

class ActorBuilderSpec extends FunSuite {
  private val eventContext = EventContext()
  import Threading.defaultContext

  given Timeout: FiniteDuration = 1.second

  override def beforeEach(context: BeforeEach): Unit =
    eventContext.start()

  override def afterEach(context: AfterEach): Unit =
    eventContext.stop()

  private def close(actor: Actor[?, ?, ?] & Closeable): Unit = {
    actor.close()
    waitFor(actor.isClosedSignal, true)
  }

  private def buildActor[Msg, Rsp, State](builder: ActorBuilder[Msg, Rsp, State]): Actor[Msg, Rsp, State] & Closeable & Pausable =
    builder.build().asInstanceOf[Actor[Msg, Rsp, State] & Closeable & Pausable]

  test("ActorBuilder creates actor with default configuration") {
    val actor = buildActor(
      ActorBuilder[Int, String, Int](0)
        .withBehavior {
          case (msg, _) => Some(s"Processed: $msg")
        }
    )

    val response = actor ? 42
    assertEquals(resultCF(response), "Processed: 42")
    close(actor)
  }

  test("ActorBuilder creates actor with custom state") {
    val initialState = 100
    val actor = buildActor(
      ActorBuilder[Int, String, Int](initialState)
        .withBehavior {
          case (msg, actor) => Some(s"State: ${actor.state}, Msg: $msg")
        }
    )

    val response = actor ? 42
    assertEquals(resultCF(response), s"State: $initialState, Msg: 42")
    close(actor)
  }

  test("ActorBuilder creates actor with multiple behaviors") {
    val actor = buildActor(
      ActorBuilder[Int, String, Int](0)
        .withBehavior("first", {
          case (1, _) => Some("First behavior")
        })
        .withBehavior("second", {
          case (2, _) => Some("Second behavior")
        })
        .withBehavior {
          case (msg, _) => Some(s"Default: $msg")
        }
    )

    // Behaviors are matched in LIFO order (last added matches first)
    // The catch-all was added last, so it matches first
    assertEquals(resultCF(actor ? 1), "Default: 1")
    assertEquals(resultCF(actor ? 2), "Default: 2")
    assertEquals(resultCF(actor ? 3), "Default: 3")
    close(actor)
  }

  test("ActorBuilder with linear heartbeat") {
    val actor = buildActor(
      ActorBuilder[Int, String, Int](0)
        .withBehavior {
          case (msg, _) => Some(s"Processed: $msg")
        }
        .withLinearHeartbeat(50)
    )

    val response = actor ? 42
    assertEquals(resultCF(response), "Processed: 42")
    close(actor)
  }

  test("ActorBuilder with agitated heartbeat") {
    val actor = buildActor(
      ActorBuilder[Int, String, Int](0)
        .withBehavior {
          case (msg, _) => Some(s"Processed: $msg")
        }
        .withAgitatedHeartbeat(50, 1.5, 500)
    )

    val response = actor ? 42
    assertEquals(resultCF(response), "Processed: 42")
    close(actor)
  }

  test("ActorBuilder with reactive heartbeat") {
    val actor = buildActor(
      ActorBuilder[Int, String, Int](0)
        .withBehavior {
          case (msg, _) => Some(s"Processed: $msg")
        }
        .withReactiveHeartbeat(100, 5)
    )

    val response = actor ? 42
    assertEquals(resultCF(response), "Processed: 42")
    close(actor)
  }

  test("ActorBuilder with serial dispatch") {
    val actor = buildActor(
      ActorBuilder[Int, String, Int](0)
        .withBehavior {
          case (msg, _) => Some(s"Processed: $msg")
        }
        .withSerialDispatch()
    )

    val response = actor ? 42
    assertEquals(resultCF(response), "Processed: 42")
    close(actor)
  }

  test("ActorBuilder with onInit callback") {
    var initialized = false

    val actor = buildActor(
      ActorBuilder[Int, String, Int](0)
        .withBehavior {
          case (msg, _) => Some(s"Processed: $msg")
        }
        .withOnInit { _ =>
          initialized = true
        }
    )

    // Wait for initialization
    Thread.sleep(100)
    assert(initialized)
    
    val response = actor ? 42
    assertEquals(resultCF(response), "Processed: 42")
    close(actor)
  }

  test("ActorBuilder with onInit and state mutation") {
    val actor = buildActor(
      ActorBuilder[Int, String, Int](0)
        .withBehavior {
          case (msg, actor) =>
            actor.state += msg
            Some(s"State: ${actor.state}")
        }
        .withOnInit { actor =>
          actor.state = 100
        }
    )

    // Wait for initialization
    Thread.sleep(100)
    
    val response = actor ? 5
    assertEquals(resultCF(response), "State: 105")
    close(actor)
  }

  test("ActorBuilder maintains LIFO behavior order") {
    val actor = buildActor(
      ActorBuilder[Int, String, Int](0)
        .withBehavior("first", {
          case (1, _) => Some("First")
        })
        .withBehavior("second", {
          case (1, _) => Some("Second")
        })
        .withBehavior("third", {
          case (1, _) => Some("Third")
        })
    )

    // Last added should match first (LIFO)
    val response = actor ? 1
    assertEquals(resultCF(response), "Third")
    close(actor)
  }

  test("ActorBuilder with pre-defined heartbeat strategies") {
    import ActorBuilder.*

    val actor1 = buildActor(
      ActorBuilder[Int, String, Int](0)
        .withBehavior {
          case (msg, _) => Some(s"Processed: $msg")
        }
        .withHeartbeat(Linear100ms)
    )

    val response1 = actor1 ? 42
    assertEquals(resultCF(response1), "Processed: 42")
    close(actor1)

    val actor2 = buildActor(
      ActorBuilder[Int, String, Int](0)
        .withBehavior {
          case (msg, _) => Some(s"Processed: $msg")
        }
        .withHeartbeat(Reactive100ms10)
    )

    val response2 = actor2 ? 42
    assertEquals(resultCF(response2), "Processed: 42")
    close(actor2)
  }
  
  test("ActorBuilder with all options configured") {
    var initCalled = false

    val actor = buildActor(
      ActorBuilder[Int, String, Int](100)
        .withBehavior("special", {
          case (42, _) => Some("The answer!")
        })
        .withBehavior {
          case (msg, actor) => Some(s"Default: ${actor.state} - $msg")
        }
        .withAgitatedHeartbeat(50, 1.5, 500)
        .withOnInit { _ =>
          initCalled = true
        }
    )

    // Wait for initialization
    Thread.sleep(100)
    assert(initCalled)

    // The catch-all was added last, so it matches first (LIFO)
    assertEquals(resultCF(actor ? 42), "Default: 100 - 42")
    assertEquals(resultCF(actor ? 10), "Default: 100 - 10")
    close(actor)
  }

  test("ActorBuilder with serial dispatch and all options") {
    var initCalled = false

    val actor = buildActor(
      ActorBuilder[Int, String, Int](0)
        .withBehavior {
          case (msg, _) => Some(s"Processed: $msg")
        }
        .withReactiveHeartbeat(50, 5)
        .withOnInit { _ =>
          initCalled = true
        }
        .withSerialDispatch()
    )

    // Wait for initialization
    Thread.sleep(100)
    assert(initCalled)

    val response = actor ? 42
    assertEquals(resultCF(response), "Processed: 42")
    close(actor)
  }
}
