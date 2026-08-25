package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.actors.Actor.HeartBeatStrategy
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
    val actor = Actor[Int, String, Int](0, (msg, _) => Some(s"Received: $msg"))
    close(actor)
  }

  test("Request-response message sending") {
    val actor = Actor[Int, String, Int](0, (msg, _) => Some(s"Default: $msg"))
    val response = actor ? 42
    assertEquals(resultCF(response), "Default: 42")
    close(actor)
  }

  test("Fire-and-forget message sending") {
    val received = Signal(false)
    val actor = Actor[Int, Unit, Boolean](false, (_, actor) => {
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
    val actor = Actor[Int, String, Int](0, (_, _) => None)
    val response = actor ? 42
    intercept[IllegalStateException] {
      resultCF(response)
    }
    close(actor)
  }

  test("Exception handling in behaviors") {
    val actor = Actor[Int, String, Int](0, (_, _) => throw new RuntimeException("Test exception"))
    val response = actor ? 42
    intercept[RuntimeException] {
      resultCF(response)
    }
    close(actor)
  }

  test("System messages handling") {
    val actor = Actor[Int, String, Int](0, (msg, _) => Some(s"Received: $msg"))
    import actor.SystemMsg
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

    val linearActor = Actor[Int, String, Int](0, (msg, _) => Some(s"Linear: $msg"), HeartBeatStrategy.Linear(100))
    val agitatedActor = Actor[Int, String, Int](0, (msg, _) => Some(s"Agitated: $msg"), HeartBeatStrategy.Agitated(50, 1.5, 500))
    val reactiveActor = Actor[Int, String, Int](0, (msg, _) => Some(s"Reactive: $msg"), HeartBeatStrategy.Reactive(100, 5))

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
    val actor = Actor[Int, String, Int](0, (msg, _) => Some(s"Processed: $msg"))
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
    val actor = Actor[Int, String, Int](0, (msg, _) => Some(s"Default: $msg"))
    val behavior42: Actor.PF[Int, String, Int] = {
      case (42, _) => Some("Special: 42")
    }
    val behavior99: Actor.PF[Int, String, Int] = {
      case (99, _) => Some("Special: 99")
    }

    val cf42: CloseableFuture[Unit] = (actor ? actor.SystemMsg.AddBehavior("special_42", behavior42))
    val cf99: CloseableFuture[Unit] = (actor ? actor.SystemMsg.AddBehavior("special_99", behavior99))
    awaitCF(cf42)
    awaitCF(cf99)
    
    assertEquals(resultCF(actor ? 42), "Special: 42")
    assertEquals(resultCF(actor ? 99), "Special: 99")

    val cf42r: CloseableFuture[Unit] = (actor ? actor.SystemMsg.RemoveBehavior("special_42"))
    val cf99r: CloseableFuture[Unit] = (actor ? actor.SystemMsg.RemoveBehavior("special_99"))
    awaitCF(cf42r)
    awaitCF(cf99r)
    
    assertEquals(resultCF(actor ? 42), "Default: 42")
    assertEquals(resultCF(actor ? 99), "Default: 99")
    close(actor)
  }

  test("Behavior added and used") {
    val actor = Actor[Int, String, Int](0, (msg, actor) => Some(s"Default: $msg"))
    val behavior: Actor.PF[Int, String, Int] = {
      case (42, _) => Some("Special: 42")
    }

    val cf42: CloseableFuture[Unit] = (actor ? actor.SystemMsg.AddBehavior("special_42", behavior))
    awaitCF(cf42)

    assertEquals(resultCF(actor ? 42), "Special: 42")
    close(actor)
  }

  // ==================== Edge Cases ====================

  test("Empty message lists") {
    val actor = Actor[Int, String, Int](0, (msg, _) => Some(s"Received: $msg"))
    
    val response = actor ? 1
    assertEquals(resultCF(response), "Received: 1")
    close(actor)
  }

  test("Actor closed while messages in-flight") {
    val actor = Actor[Int, String, Int](0, (msg, _) => {
      Thread.sleep(50)
      Some(s"Processed: $msg")
    })
    import actor.SystemMsg
    
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
    import actor.SystemMsg
    
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
    val actor = Actor[Int, String, Int](0, (msg, _) => Some(s"Processed: $msg"),
      HeartBeatStrategy.Agitated(minMs = 50, coeff = 2.0, maxMs = 1000))
    
    val response1 = actor ? 1
    resultCF(response1)
    
    Thread.sleep(200) // Wait for interval to grow
    val response2 = actor ? 2
    resultCF(response2)
    
    close(actor)
  }

  test("Reactive heartbeat processes messages") {
    val actor = Actor[Int, String, Int](0, (msg, _) => Some(s"Processed: $msg"),
      HeartBeatStrategy.Reactive(maxMs = 100, maxMsgs = 2))
    
    val response = actor ? 1
    assertEquals(resultCF(response), "Processed: 1")
    close(actor)
  }

  // ==================== Error Handling ====================

  test("Actor continues processing after behavior exception") {
    var callCount = 0
    val actor = Actor[Int, String, Int](0, (msg, _) => {
      callCount += 1
      if (msg == 1) throw new RuntimeException("Test error") else Some(s"Processed: $msg")
    })
    
    intercept[RuntimeException](resultCF(actor ? 1))
    
    val response2 = actor ? 2
    assertEquals(resultCF(response2), "Processed: 2")
    assertEquals(callCount, 2)
    close(actor)
  }

  test("Behavior returns None vs Some(None)") {
    val actor = Actor[Int, Option[String], Int](0, (msg, _) => {
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
    val actor = Actor[Int, String, Int](0, (_, _) => None)
    
    val response = actor ? 42
    intercept[IllegalStateException](resultCF(response))
    close(actor)
  }

  test("Actor with Unit state") {
    val actor = Actor[Int, String, Unit]((), (msg, _) => Some(s"Received: $msg"))
    
    val response = actor ? 42
    assertEquals(resultCF(response), "Received: 42")
    close(actor)
  }

  // ==================== DispatchQueue Integration ====================

  test("Serial dispatch queue actor") {
    val actor = Actor.serial[Int, String, Int](0, (msg, _) => Some(s"Serial: $msg"))
    
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

  // ==================== System Message Behavior Management ====================

  test("AddBehavior system message") {
    val actor = Actor[Int, String, Int](0, (msg, actor) => Some(s"Default: $msg"))
    import actor.SystemMsg
    
    val behavior: Actor.PF[Int, String, Int] = { case (42, _) => Some("Special: 42") }
    actor ! SystemMsg.AddBehavior("testId", behavior)
    
    Thread.sleep(200) // Wait for system message processing
    assertEquals(resultCF(actor ? 42), "Special: 42")
    close(actor)
  }

  test("RemoveBehavior system message") {
    val actor = Actor[Int, String, Int](0, (msg, actor) => Some(s"Default: $msg"))
    import actor.SystemMsg
    
    val behavior: Actor.PF[Int, String, Int] = { case (42, _) => Some("Special: 42") }
    val cf = (actor ? actor.SystemMsg.AddBehavior("behavior", behavior))
    awaitCF(cf)
    
    assertEquals(resultCF(actor ? 42), "Special: 42")
    
    val cfr = actor ? SystemMsg.RemoveBehavior("behavior")
    awaitCF(cfr)
    
    assertEquals(resultCF(actor ? 42), "Default: 42")
    close(actor)
  }

  test("AddBehavior and RemoveBehavior via system messages") {
    val actor = Actor[Int, String, Int](0, (msg, actor) => Some(s"Default: $msg"))
    import actor.SystemMsg
    
    val behavior: Actor.PF[Int, String, Int] = { case (42, _) => Some("Special: 42") }
    
    actor ! SystemMsg.AddBehavior("testId", behavior)
    Thread.sleep(200)
    assertEquals(resultCF(actor ? 42), "Special: 42")
    
    actor ! SystemMsg.RemoveBehavior("testId")
    Thread.sleep(200)
    assertEquals(resultCF(actor ? 42), "Default: 42")
    
    close(actor)
  }

  // ==================== System Message with Response ====================

  test("Pause system message with response via ?") {
    val actor = Actor[Int, String, Int](0, (msg, _) => Some(s"Received: $msg"))
    import actor.SystemMsg
    
    val pauseFuture = actor ? SystemMsg.Pause
    resultCF(pauseFuture)
    waitFor(actor.isPausedSignal, true)
    close(actor)
  }

  test("Unpause system message with response via ?") {
    val actor = Actor[Int, String, Int](0, (msg, _) => Some(s"Received: $msg"))
    import actor.SystemMsg
    
    actor ! SystemMsg.Pause
    waitFor(actor.isPausedSignal, true)
    
    val unpauseFuture = actor ? SystemMsg.Unpause
    resultCF(unpauseFuture)
    waitFor(actor.isPausedSignal, false)
    close(actor)
  }

  test("Close system message with response via ?") {
    val actor = Actor[Int, String, Int](0, (msg, _) => Some(s"Received: $msg"))
    import actor.SystemMsg
    
    val closeFuture = actor ? SystemMsg.Close
    resultCF(closeFuture)
    waitFor(actor.isClosedSignal, true)
  }

  test("AddBehavior system message with response via ?") {
    val actor = Actor[Int, String, Int](0, (msg, _) => Some(s"Default: $msg"))
    import actor.SystemMsg
    
    val behavior: Actor.PF[Int, String, Int] = { case (42, _) => Some("Special: 42") }
    val cf = actor ? SystemMsg.AddBehavior("testId", behavior)
    resultCF(cf)
    
    Thread.sleep(100)
    assertEquals(resultCF(actor ? 42), "Special: 42")
    close(actor)
  }

  test("RemoveBehavior system message with response via ?") {
    val actor = Actor[Int, String, Int](0, (msg, _) => Some(s"Default: $msg"))
    import actor.SystemMsg
    
    val behavior: Actor.PF[Int, String, Int] = { case (42, _) => Some("Special: 42") }
    val cf = actor ? SystemMsg.AddBehavior("testId", behavior)
    resultCF(cf)

    assertEquals(resultCF(actor ? 42), "Special: 42")
    
    val removeFuture = actor ? SystemMsg.RemoveBehavior("testId")
    resultCF(removeFuture)
    
    Thread.sleep(100)
    assertEquals(resultCF(actor ? 42), "Default: 42")
    close(actor)
  }

  test("System messages via ? return Unit response") {
    val actor = Actor[Int, String, Int](0, (msg, _) => Some(s"Default: $msg"))
    import actor.SystemMsg
    
    val pauseFuture: CloseableFuture[Unit] = actor ? SystemMsg.Pause
    awaitCF(pauseFuture)
    
    waitFor(actor.isPausedSignal, true)
    close(actor)
  }

  // ==================== Close Response Guarantees ====================

  test("Close via ? completes only after actor is closed") {
    val actor = Actor[Int, String, Int](0, (msg, _) => Some(s"Received: $msg"))
    import actor.SystemMsg
    import scala.util.Try

    val closeFuture = actor ? SystemMsg.Close
    
    // The future should NOT be completed yet
    val poll1 = Try(resultCF(closeFuture)(using 10.millis))
    assert(poll1.isFailure) // Should timeout because actor is not closed yet
    
    // Wait for the close to actually complete
    resultCF(closeFuture)(using 2.seconds)
    
    // Now the actor should be closed
    waitFor(actor.isClosedSignal, true)
  }

  test("Close via ? response is Unit") {
    val actor = Actor[Int, String, Int](0, (msg, _) => Some(s"Received: $msg"))
    import actor.SystemMsg
    
    val closeFuture: CloseableFuture[Unit] = actor ? SystemMsg.Close
    awaitCF(closeFuture)(using 2.seconds)

    waitFor(actor.isClosedSignal, true)
  }

  test("Close via ? with pending messages waits for processing") {
    val actor = Actor[Int, String, Int](0, (msg, _) => {
      Thread.sleep(50) // Simulate slow processing
      Some(s"Processed: $msg")
    })
    import actor.SystemMsg
    
    // Send some messages that take time to process
    actor ! 1
    actor ! 2
    actor ! 3
    
    // Close the actor - this should wait for messages to be processed
    val closeFuture = actor ? SystemMsg.Close
    
    // The future should complete only after messages are processed and actor is closed
    awaitCF(closeFuture)(using 2.seconds)
    
    waitFor(actor.isClosedSignal, true)
  }

  test("Close via ! does not wait for response") {
    val actor = Actor[Int, String, Int](0, (msg, _) => Some(s"Received: $msg"))
    import actor.SystemMsg
    
    // Close via fire-and-forget
    actor ! SystemMsg.Close
    
    // Wait for the actor to be closed
    waitFor(actor.isClosedSignal, true)
  }

  test("Multiple Close via ? all complete") {
    val actor = Actor[Int, String, Int](0, (msg, _) => Some(s"Received: $msg"))
    import actor.SystemMsg
    
    val closeFuture1 = actor ? SystemMsg.Close
    val closeFuture2 = actor ? SystemMsg.Close
    
    // Both futures should complete (though actor can only close once)
    resultCF(closeFuture1)(using 2.seconds)
    resultCF(closeFuture2)(using 2.seconds)
    
    waitFor(actor.isClosedSignal, true)
  }

  test("Close via ? when actor has pending messages") {
    val received = Signal(false)
    val actor = Actor[Int, Unit, Boolean](false, (_, _) => {
      received ! true
      None
    })
    import actor.SystemMsg
    
    // Send a message that will take time
    actor ! 1
    
    // Close the actor - should wait for message to be processed
    val closeFuture = actor ? SystemMsg.Close
    
    awaitCF(closeFuture)(using 2.seconds)
    waitFor(actor.isClosedSignal, true)
    
    // The message should have been processed before close completed
    waitFor(received, true)
  }
}