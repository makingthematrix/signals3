package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.actors.Actor.{HeartBeatStrategy, PF}
import io.github.makingthematrix.signals3.Utils.*
import io.github.makingthematrix.signals3.{Closeable, CloseableFuture, EventContext, Signal, SourceStream, Stream, Threading}
import munit.FunSuite

import scala.concurrent.duration.*
import scala.util.Try

class ActorSpec extends FunSuite {
  import Utils.*
  import Utils.MyMsg.*

  private val eventContext = EventContext()
  import Threading.defaultContext

  given Timeout: FiniteDuration = 1.seconds

  override def beforeEach(context: BeforeEach): Unit =
    eventContext.start()

  override def afterEach(context: AfterEach): Unit =
    eventContext.stop()

  test("Request-response message sending") {
    val actor = create[MyMsg]({ case (MyInt(msg), _) => Some(MyStr(s"Default: $msg")) })
    val response = actor ? MyInt(42)
    assertEquals(resultCF(response), MyStr("Default: 42"))
    close(actor)
  }

  test("Fire-and-forget message sending") {
    val received = Signal(false)
    val actor = create[Int, Boolean](false, { case (_, actor) =>
      actor.state = true
      received ! true
      None
    })
    actor ! 1
    waitFor(received, true)
    assert(actor.state)
    close(actor)
  }

  test("System messages handling") {
    val actor = create[String]({ case (msg, _) => Some(s"Received: $msg") })
    
    actor ! SystemMsg.Pause
    waitFor(actor.isPausedSignal, true)
    actor ! SystemMsg.Unpause
    waitFor(actor.isPausedSignal, false)
    actor ! SystemMsg.Close
    waitFor(actor.isClosedSignal, true)
  }

  test("Concurrent message processing") {
    val actor = create[Int, Int](0, { case (msg, actor) =>
      actor.state += msg
      Some(actor.state)
    })

    waitFor(actor.isInitializedSignal, true)

    val futures: Seq[CloseableFuture[Int]] = (1 to 10).map { actor ? _ }
    val results: CloseableFuture[Iterable[Int]] = CloseableFuture.sequence(futures)
    val finalResult: Int = resultCF(results)(using 2.seconds).max
    assertEquals(finalResult, 55)
    close(actor)
  }

  test("Heartbeat strategies") {
    val linearResponse = Signal(MyStr(""))
    val agitatedResponse = Signal(MyStr(""))
    val reactiveResponse = Signal(MyStr(""))

    val linearActor = create[MyMsg]({ case (MyInt(msg), _) => Some(MyStr(s"Linear: $msg"))}, HeartBeatStrategy.Linear(100))
    val agitatedActor = create[MyMsg]({ case (MyInt(msg), _) => Some(MyStr(s"Agitated: $msg"))}, HeartBeatStrategy.Agitated(50, 1.5, 500))
    val reactiveActor = create[MyMsg]({ case (MyInt(msg), _) => Some(MyStr(s"Reactive: $msg"))}, HeartBeatStrategy.Reactive(100, 5))

    (linearActor ? MyInt(1)).pipeTo(linearResponse)
    (agitatedActor ? MyInt(2)).pipeTo(agitatedResponse)
    (reactiveActor ? MyInt(3)).pipeTo(reactiveResponse)

    waitFor(linearResponse, MyStr("Linear: 1"))
    waitFor(agitatedResponse, MyStr("Agitated: 2"))
    waitFor(reactiveResponse, MyStr("Reactive: 3"))

    close(linearActor)
    close(agitatedActor)
    close(reactiveActor)
  }

  // ==================== Behavior Modification ====================

  test("Concurrent behavior addition and removal") {
    val actor = create[MyMsg]({ case (MyInt(msg), _) => Some(MyStr(s"Default: $msg")) })
    val behavior42: PF[MyMsg, Unit] = { case (MyInt(42), _) => Some(MyStr("Special: 42")) }
    val behavior99: PF[MyMsg, Unit] = { case (MyInt(99), _) => Some(MyStr("Special: 99")) }

    val cf42 = actor ? SystemMsg.AddBehavior("special_42", behavior42)
    val cf99 = actor ? SystemMsg.AddBehavior("special_99", behavior99)
    awaitCF(cf42)
    awaitCF(cf99)
    
    assertEquals(resultCF(actor ? MyInt(42)), MyStr("Special: 42"))
    assertEquals(resultCF(actor ? MyInt(99)), MyStr("Special: 99"))

    val cf42r = actor ? SystemMsg.RemoveBehavior("special_42")
    val cf99r = actor ? SystemMsg.RemoveBehavior("special_99")
    awaitCF(cf42r)
    awaitCF(cf99r)

    awaitAllTasks
    
    assertEquals(resultCF(actor ? MyInt(42)), MyStr("Default: 42"))
    assertEquals(resultCF(actor ? MyInt(99)), MyStr("Default: 99"))
    close(actor)
  }

  test("Duplicate behavior IDs are NOT replaced") {
    val actor = create[String]{ case (msg, _) => Some(s"Default: $msg") }
    
    // Add first behavior that handles 42
    val behavior1: PF[String, Unit] = { case ("42", _) => Some("First: 42") }
    val cf1 = actor ? SystemMsg.AddBehavior("duplicate_id", behavior1)
    awaitCF(cf1)
    
    // Verify first behavior works
    assertEquals(resultCF(actor ? "42"), "First: 42")
    
    // Add second behavior with the same ID but different response
    val behavior2: PF[String, Unit] = { case ("42", _) => Some("Second: 42") }
    val cf2 = actor ? SystemMsg.AddBehavior("duplicate_id", behavior2)
    awaitCF(cf2)
    
    // The second behavior should have replaced the first
    assertEquals(resultCF(actor ? "42"), "First: 42")
    
    close(actor)
  }

  // ==================== Behavior ID Message Routing ====================

  test("ask with behavior ID routes message to specific behavior") {
    val actor = create[String]{ case (msg, _) => Some(s"Default: $msg") }
    
    // Add a special behavior
    val behavior: PF[String, Unit] = { case ("42", _) => Some("Special: 42") }
    val cfAdd = actor ? SystemMsg.AddBehavior("special_42", behavior)
    awaitCF(cfAdd)

    // Message 42 should be handled by the special behavior when using its ID
    val response = actor ? ("special_42" -> "42")
    assertEquals(resultCF(response), "Special: 42")
    
    close(actor)
  }

  test("bang with behavior ID routes message to specific behavior") {
    val received = Signal(false)
    val receivedSpecial = Signal(false)
    
    val actor = create[Int]{ case _ => None }
    
    // Add behaviors via system messages
    val defaultBehavior: PF[Int, Unit] = { case _ => received ! true; None }
    val specialBehavior: PF[Int, Unit] = { case _ => receivedSpecial ! true; None }
    
    awaitCF(actor ? SystemMsg.AddBehavior("default", defaultBehavior))
    awaitCF(actor ? SystemMsg.AddBehavior("special", specialBehavior))
    
    // Send via bang without behavior ID - should use normal processing
    actor ! 1
    waitFor(received, true)
    
    // Reset and send with behavior ID
    received ! false
    receivedSpecial ! false
    
    // Send via bang with specific behavior ID
    actor.tell(2, "special")
    waitFor(receivedSpecial, true)
    
    // The default behavior should NOT have been triggered
    Thread.sleep(100)
    assert(!received.currentValue.getOrElse(false), "Default behavior should not be triggered when using behavior ID")
    
    close(actor)
  }

  // ==================== Edge Cases ====================

  test("Actor closed while messages in-flight") {
    val actor = create[String] { case (msg, _) =>
      Thread.sleep(50)
      Some(s"Processed: $msg")
    }
    
    val futures = (1 to 5).map(n => actor ? n.toString)
    actor ! SystemMsg.Close
    waitFor(actor.isClosedSignal, true)

    val res = CloseableFuture.sequence(futures)
    tryResult(res.future)(using 2.seconds)
  }

  test("System messages with messages in queue") {
    val received = Signal(false)
    val actor = create[Int, Boolean](false, { case (_, actor) =>
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

  // ==================== Error Handling ====================

  test("Actor continues processing after behavior exception") {
    var callCount = 0
    val actor = create[String] { case (msg, _) =>
      callCount += 1
      if (msg == "1") throw new RuntimeException("Test error") else Some(s"Processed: $msg")
    }
    
    intercept[RuntimeException](resultCF(actor ? "1"))
    
    val response2 = actor ? "2"
    assertEquals(resultCF(response2), "Processed: 2")
    assertEquals(callCount, 2)
    close(actor)
  }

  // ==================== DispatchQueue Integration ====================

  test("Serial dispatch queue with multiple behaviors") {
    val behavior1: PF[String, Unit] = { case ("42", _) => Some("Special: 42") }
    val behavior2: PF[String, Unit] = { case (msg, _) => Some(s"Default: $msg") }
    val actor =
      ActorBuilder[String]()
        .withBehaviorPFs(List(behavior1, behavior2))
        .withSerialDispatch()
        .build()
        .asInstanceOf[Actor[String, Unit] & Closeable]

    waitForResult(actor.isInitializedSignal, true)

    assertEquals(resultCF(actor ? "42"), "Special: 42")
    assertEquals(resultCF(actor ? "1"), "Default: 1")
    close(actor)
  }

  // ==================== System Message Behavior Management ====================

  test("AddBehavior and RemoveBehavior via system messages") {
    val actor = create[String] { case (msg, _) => Some(s"Default: $msg") }
    
    val behavior: PF[String, Unit] = { case ("42", _) => Some("Special: 42") }
    
    actor ! SystemMsg.AddBehavior("testId", behavior)
    assertEquals(resultCF(actor ? "42"), "Special: 42")
    
    actor ! SystemMsg.RemoveBehavior("testId")
    assertEquals(resultCF(actor ? "42"), "Default: 42")
    
    close(actor)
  }

  // ==================== System Message with Response ====================

  test("Pause system message with response via ?") {
    val actor = create[Unit] { case _ => None }
    
    val pauseFuture = actor ? SystemMsg.Pause
    resultCF(pauseFuture)
    waitFor(actor.isPausedSignal, true)
    close(actor)
  }

  test("Unpause system message with response via ?") {
    val actor = create[Unit] { case _ => None }
    
    actor ! SystemMsg.Pause
    waitFor(actor.isPausedSignal, true)
    
    val unpauseFuture = actor ? SystemMsg.Unpause
    resultCF(unpauseFuture)
    waitFor(actor.isPausedSignal, false)
    close(actor)
  }

  // ==================== Close Response Guarantees ====================

  test("Close via ? completes only after actor is closed") {
    val actor = create[Unit] { case _ => None }

    val closeFuture = actor ? SystemMsg.Close
    
    // The future should NOT be completed yet
    val poll1 = Try(resultCF(closeFuture)(using 10.millis))
    assert(poll1.isFailure) // Should timeout because actor is not closed yet
    
    // Wait for the close to actually complete
    resultCF(closeFuture)(using 2.seconds)
    
    // Now the actor should be closed
    waitFor(actor.isClosedSignal, true)
  }

  test("Close via ? with pending messages waits for processing") {
    val actor = create[Int] { case (msg, _) =>
      Thread.sleep(50) // Simulate slow processing
      Some(msg + 1)
    }
    
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

  test("Only the first close via ? completes, the next one fails") {
    val actor = create[Unit] { case _ => None }

    waitFor(actor.isInitializedSignal, true)

    val closeFuture1 = actor ? SystemMsg.Close
    resultCF(closeFuture1)

    val closeFuture2 = actor ? SystemMsg.Close
    val res = Try(resultCF(closeFuture2)(using 1.seconds))
    assert(res.isFailure) // should time out

    waitFor(actor.isClosedSignal, true)
  }

  test("Close via ? when actor has pending messages") {
    val received = Signal(false)
    val actor = create[Int] { case _ => received ! true; None }
    
    // Send a message that will take time
    actor ! 1
    
    // Close the actor - should wait for message to be processed
    val closeFuture = actor ? SystemMsg.Close
    
    awaitCF(closeFuture)(using 2.seconds)
    waitFor(actor.isClosedSignal, true)
    
    // The message should have been processed before close completed
    waitFor(received, true)
  }

  // ==================== in/out Stream Tests =====================

  test("in stream receives messages sent to actor") {
    val actor = create[Int]{ case (msg, _) => Some(msg + 1) }
    val received = Signal(Seq.empty[Int])
    
    // Subscribe to in stream
    actor.in.foreach { msg => received.mutate(_ :+ msg) }
    
    // Send messages via in stream
    actor.in ! 1
    actor.in ! 2
    actor.in ! 3
    
    // Wait for messages
    waitForResult(received, Seq(1, 2, 3))
    
    close(actor)
  }

  test("out stream receives responses when behavior sends to it") {
    val actor = create[String] { case (msg, actor) =>
      // Behavior explicitly sends response to out stream
      actor.out ! s"Response: $msg"
      None
    }
    val responses = Signal(Seq.empty[String])
    
    // Subscribe to the out stream to receive responses
    actor.out.foreach { rsp =>
      responses.mutate(_ :+ rsp)
    }
    
    // Send messages via in stream
    actor.in ! "1"
    actor.in ! "2"
    
    // Wait for responses via out stream
    waitForResult(responses, Seq("Response: 1", "Response: 2"))
    
    close(actor)
  }

  test("in and out streams work together for bidirectional communication") {
    // This test demonstrates using in/out streams as an alternative to ! and ? operators
    val actor = create[String] { case (msg, actor) =>
      // The behavior processes the message and sends response to out stream
      actor.out ! s"Processed: $msg"
      None
    }
    
    val receivedResponses = Signal(Seq.empty[String])
    
    // Subscribe to out stream to receive responses
    actor.out.foreach { rsp =>
      receivedResponses.mutate(_ :+ rsp)
    }
    
    // Send messages via in stream
    actor.in ! "10"
    actor.in ! "20"
    actor.in ! "30"
    
    // Wait for all responses to be received via out stream
    waitForResult(receivedResponses, Seq("Processed: 10", "Processed: 20", "Processed: 30"))
    
    close(actor)
  }

  test("piping messages from external stream to actor in stream") {
    // This test demonstrates a real-world scenario where external events are piped to the actor
    val externalStream: SourceStream[String] = Stream()
    val actor = create[String] { case (msg, actor) =>
      // Behavior sends responses to out stream
      actor.out ! s"Handled: $msg"
      None
    }
    val received = Signal(Seq.empty[String])
    
    // Subscribe to actor's responses via out stream
    actor.out.foreach { rsp =>
      received.mutate(_ :+ rsp)
    }
    
    // Pipe external stream to actor's in stream
    externalStream.pipeTo(actor.in)
    
    // Send messages to external stream, which will be forwarded to actor
    externalStream ! "1"
    externalStream ! "2"
    externalStream ! "3"
    
    // Wait for responses via out stream
    waitForResult(received, Seq("Handled: 1", "Handled: 2", "Handled: 3"))
    
    close(actor)
  }

  // ==================== onInit Tests =====================

  test("onInit receives the actor as parameter") {
    var receivedActor: Option[Actor[String, Unit]] = None
    
    val actor = create[String]({ case (msg, _) => Some(s"Processed: $msg") }, onInit = { a => receivedActor = Some(a) })
    
    // onInit should have received the actor
    assert(receivedActor.isDefined)
    assert(receivedActor.contains(actor))
    close(actor)
  }

  test("onInit is called before the actor starts processing messages") {
    val order = Signal(Seq.empty[String])
    
    val actor = create[String]({ case (msg, _) =>
      order.mutate(_ :+ "behavior")
      Some(s"Processed: $msg")
    }, onInit = { _ => order.mutate(_ :+ "onInit") })
    
    // Send a message immediately
    actor ! "1"
    
    // onInit should have been called before the behavior processes the message
    waitForResult(order, Seq("onInit", "behavior"))
    close(actor)
  }

  test("onInit with serial dispatch queue") {
    val initCalled = Signal(false)

    val actor =
      ActorBuilder[String]()
        .withBehaviorPF { case (msg, _) => Some(s"Processed: $msg") }
        .withOnInit { _ => initCalled ! true }
        .withSerialDispatch()
        .build()
        .asInstanceOf[Actor[String, String] & Closeable]

    waitFor(initCalled, true)
    close(actor)
  }

  test("onInit can send messages via out stream") {
    val initMessage = Signal("")
    
    val actor = create[String]({ case (msg, _) => Some(s"Processed: $msg") }, onInit = { actorImpl => actorImpl.out ! "Initialized" })
    
    actor.out.foreach { msg => initMessage ! msg }
    
    waitFor(initMessage, "Initialized")
    close(actor)
  }

  test("onInit can modify actor state") {
    val actor = create[MyMsg, Int](0, { case (_, actor) => Some(MyStr(s"State: ${actor.state}")) }, onInit = { _.state = 100 })
    
    // State should have been modified by onInit
    assertEquals(actor.state, 100)
    
    val response = actor ? MyInt(1)
    assertEquals(resultCF(response), MyStr("State: 100"))
    close(actor)
  }

  test("onInit can send messages to external stream") {
    val externalStreamReceived = Signal(Seq.empty[String])
    val externalStream: SourceStream[String] = Stream()
    
    val actor = create[String]({ case (msg, _) => Some(s"Processed: $msg") },
      onInit = { _ =>
        externalStream ! "Actor initialized"
        externalStream ! "Ready to process"
      })
    
    externalStream.foreach { msg => externalStreamReceived.mutate(_ :+ msg) }
    
    waitForResult(externalStreamReceived, Seq("Actor initialized", "Ready to process"))
    close(actor)
  }

  test("onInit bidirectional handshake between two actors") {
    val handshakeComplete = Signal(false)
    
    // Create first actor with a reference to where the second will send its handshake
    val actor1 = create[String]({ case (msg, _) => Some(s"A1: $msg") })
    
    // Create second actor with onInit that sends a message to actor1's in stream
    val actor2 = create[String]({ case (msg, _) => Some(s"A2: $msg") }, onInit = { _ => actor1.in ! "42" })
    
    actor1.in.foreach { msg => if (msg == "42") handshakeComplete ! true }
    
    waitFor(handshakeComplete, true)
    
    close(actor1)
    close(actor2)
  }

  test("onInit exception handling with resource cleanup") {
    // onInit is called during initialization, and if it throws an exception, it's caught, logged,
    // and the external result remains simply that the actor is not initialized, but closed.
    val actor = create[Unit]({ case _ => None }, onInit = { _ => throw new RuntimeException("Init error") })
    assert(!actor.isInitialized)
    assert(actor.isClosed)
  }
}
