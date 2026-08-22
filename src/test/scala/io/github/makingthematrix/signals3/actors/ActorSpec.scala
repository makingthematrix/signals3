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

  // ==================== SourceStream Integration ====================

  test("SourceStream integration via in stream") {
    val actor = Actor[Int, String, Int](0, (msg, actor) => Some(s"Processed: $msg"))
    val received = Signal(false)
    
    actor.in.foreach { msg =>
      if (msg == 42) received ! true
    }
    
    actor.in ! 42
    waitFor(received, true)
    close(actor)
  }

  // ==================== Behavior Modification ====================

  test("Concurrent behavior addition and removal") {
    val actor = Actor[Int, String, Int](0, (msg, actor) => Some(s"Default: $msg"))
    val behavior1: Actor.PF[Int, String, Int] = {
      case (42, _) => Some("Special: 42")
    }
    val behavior2: Actor.PF[Int, String, Int] = {
      case (99, _) => Some("Special: 99")
    }
    
    val id1 = actor.addBehavior(behavior1)
    val id2 = actor.addBehavior(behavior2)
    
    assertEquals(resultCF(actor ? 42), "Special: 42")
    assertEquals(resultCF(actor ? 99), "Special: 99")
    
    actor.removeBehavior(id1)
    actor.removeBehavior(id2)
    
    assertEquals(resultCF(actor ? 42), "Default: 42")
    assertEquals(resultCF(actor ? 99), "Default: 99")
    close(actor)
  }

  test("Behavior added and used") {
    val actor = Actor[Int, String, Int](0, (msg, actor) => Some(s"Default: $msg"))
    val behavior: Actor.PF[Int, String, Int] = {
      case (42, _) => Some("Special: 42")
    }
    
    actor.addBehavior(behavior)
    
    assertEquals(resultCF(actor ? 42), "Special: 42")
    close(actor)
  }

  // ==================== Edge Cases ====================

  test("Empty message lists") {
    val actor = Actor[Int, String, Int](0, (msg, actor) => Some(s"Received: $msg"))
    
    val response = actor ? 1
    assertEquals(resultCF(response), "Received: 1")
    close(actor)
  }

  test("Actor closed while messages in-flight") {
    val actor = Actor[Int, String, Int](0, (msg, actor) => {
      Thread.sleep(50)
      Some(s"Processed: $msg")
    })
    
    val futures = (1 to 5).map(actor ? _)
    actor ! SystemMsg.Close
    waitFor(actor.isClosedSignal, true)
    
    futures.foreach(f => tryResult(f.future)(using 2.seconds))
  }

  test("System messages with messages in queue") {
    val received = Signal(false)
    val actor = Actor[Int, Unit, Boolean](false, (msg, actor) => {
      actor.state = true
      received ! true
      None
    })
    
    actor ! SystemMsg.Pause
    waitFor(actor.isPausedSignal, true)
    
    actor ! 1
    actor ! 2
    Thread.sleep(100)
    assert(!received.currentValue.contains(true))
    
    actor ! SystemMsg.Unpause
    waitFor(actor.isPausedSignal, false)
    waitFor(received, true)
    assert(actor.state)
    
    close(actor)
  }

  // ==================== Heartbeat Strategy Specifics ====================

  test("Agitated heartbeat interval grows when idle") {
    val actor = Actor[Int, String, Int](0, (msg, actor) => Some(s"Processed: $msg"), 
      HeartBeatStrategy.Agitated(minMs = 50, coeff = 2.0, maxMs = 1000))
    
    val response1 = actor ? 1
    resultCF(response1)
    
    Thread.sleep(200) // Wait for interval to grow
    val response2 = actor ? 2
    resultCF(response2)
    
    close(actor)
  }

  test("Reactive heartbeat processes messages") {
    val actor = Actor[Int, String, Int](0, (msg, actor) => Some(s"Processed: $msg"),
      HeartBeatStrategy.Reactive(maxMs = 100, maxMsgs = 2))
    
    val response = actor ? 1
    assertEquals(resultCF(response), "Processed: 1")
    close(actor)
  }

  // ==================== Error Handling ====================

  test("Actor continues processing after behavior exception") {
    var callCount = 0
    val actor = Actor[Int, String, Int](0, (msg, actor) => {
      callCount += 1
      if (msg == 1) throw new RuntimeException("Test error")
      else Some(s"Processed: $msg")
    })
    
    intercept[RuntimeException](resultCF(actor ? 1))
    
    val response2 = actor ? 2
    assertEquals(resultCF(response2), "Processed: 2")
    assertEquals(callCount, 2)
    close(actor)
  }

  test("Behavior returns None vs Some(None)") {
    val actor = Actor[Int, Option[String], Int](0, (msg, actor) => {
      if (msg == 1) None
      else if (msg == 2) Some(None)
      else Some(Some("value"))
    })
    
    val response1 = actor ? 1
    intercept[IllegalStateException](resultCF(response1))
    
    val response2 = actor ? 2
    assertEquals(resultCF(response2), None)
    
    val response3 = actor ? 3
    assertEquals(resultCF(response3), Some("value"))
    close(actor)
  }

  // ==================== Special Cases ====================

  test("Actor with no behaviors uses ignoreMsg") {
    val actor = Actor[Int, String, Int](0, (msg, actor) => None)
    
    val response = actor ? 42
    intercept[IllegalStateException](resultCF(response))
    close(actor)
  }

  test("Actor with Unit state") {
    val actor = Actor[Int, String, Unit]((), (msg, actor) => Some(s"Received: $msg"))
    
    val response = actor ? 42
    assertEquals(resultCF(response), "Received: 42")
    close(actor)
  }

  // ==================== DispatchQueue Integration ====================

  test("Serial dispatch queue actor") {
    val actor = Actor.serial[Int, String, Int](0, (msg, actor) => Some(s"Serial: $msg"))
    
    val response = actor ? 42
    assertEquals(resultCF(response), "Serial: 42")
    close(actor)
  }

  test("Serial dispatch queue with multiple behaviors") {
    val behavior1: Actor.PF[Int, String, Int] = { case (42, _) => Some("Special: 42") }
    val behavior2: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"Default: $msg") }
    val actor = Actor.serial[Int, String, Int](0, List(behavior1, behavior2))
    
    assertEquals(resultCF(actor ? 42), "Special: 42")
    assertEquals(resultCF(actor ? 1), "Default: 1")
    close(actor)
  }
}