package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.actors.Actor.{HeartBeatStrategy, SystemMsg}
import io.github.makingthematrix.signals3.testutils.*
import io.github.makingthematrix.signals3.{CloseableFuture, EventContext, Signal, Threading}
import munit.FunSuite

import scala.concurrent.duration.*

class ActorSpec extends FunSuite {
  private val eventContext = EventContext()
  import Threading.defaultContext

  given Timeout: FiniteDuration = 1.seconds

  override def beforeEach(context: BeforeEach): Unit =
    eventContext.start()

  override def afterEach(context: AfterEach): Unit =
    eventContext.stop()

  private def close(actor: Actor[?, ?, ?]): Unit = {
    actor.close()
    waitFor(actor.isClosedSignal, true)
  }

  test("Actor creation with initial state") {
    val actor = Actor(0, (msg, actor) => Some(s"Received: $msg"))
    close(actor)
  }

  test("Request-response message sending") {
    val actor = Actor(0, (msg, actor) => Some(s"Default: $msg"))
    val response = actor ? 42
    assertEquals(resultCF(response), "Default: 42")
    close(actor)
  }

  test("Fire-and-forget message sending") {
    val received = Signal(false)
    val actor = Actor[Int, Unit, Boolean](false, (msg, actor) => {
      actor.state = true
      received ! true
      None
    })
    actor ! 1
    waitFor(received, true)
    assert(actor.state)
    close(actor)
  }

  test("Response handling with NoResponse") {
    val actor = Actor(0, (msg, actor) => None)
    val response = actor ? 42
    intercept[IllegalStateException] {
      resultCF(response)
    }
    close(actor)
  }

  test("Exception handling in behaviors") {
    val actor = Actor(0, (msg, actor) => throw new RuntimeException("Test exception"))
    val response = actor ? 42
    intercept[RuntimeException] {
      resultCF(response)
    }
    close(actor)
  }

  test("System messages handling") {
    val actor = Actor(0, (msg, actor) => Some(s"Received: $msg"))
    actor ! SystemMsg.Pause
    waitFor(actor.isPausedSignal, true)
    actor ! SystemMsg.Unpause
    waitFor(actor.isPausedSignal, false)
    actor ! SystemMsg.Close
    waitFor(actor.isClosedSignal, true)
  }

  test("Concurrent message processing") {
    val actor = Actor[Int, Int, Int](0, (msg, actor) => {
      actor.state += msg
      Some(actor.state)
    })

    val futures: Seq[CloseableFuture[Int]] = (1 to 10).map { actor ? _ }
    val results: CloseableFuture[Iterable[Int]] = CloseableFuture.sequence(futures)
    val finalResult: Int = resultCF(results).max
    assertEquals(finalResult, 55)
    close(actor)
  }

  test("Heartbeat strategies") {
    val linearResponse = Signal("")
    val agitatedResponse = Signal("")
    val reactiveResponse = Signal("")

    val linearActor = Actor[Int, String, Int](0, (msg, actor) => Some(s"Linear: $msg"), HeartBeatStrategy.Linear(100))
    val agitatedActor = Actor[Int, String, Int](0, (msg, actor) => Some(s"Agitated: $msg"), HeartBeatStrategy.Agitated(50, 1.5, 500))
    val reactiveActor = Actor[Int, String, Int](0, (msg, actor) => Some(s"Reactive: $msg"), HeartBeatStrategy.Reactive(100, 5))

    (linearActor ? 1).pipeTo(linearResponse)
    (agitatedActor ? 2).pipeTo(agitatedResponse)
    (reactiveActor ? 3).pipeTo(reactiveResponse)

    waitFor(linearResponse, "Linear: 1")
    waitFor(agitatedResponse, "Agitated: 2")
    waitFor(reactiveResponse, "Reactive: 3")

    close(linearActor)
    close(agitatedActor)
    close(reactiveActor)
  }
}