package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.testutils.*
import io.github.makingthematrix.signals3.*
import munit.FunSuite

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

/**
 * Integration tests for Actor focusing on thread safety and concurrent behavior modifications.
 * These tests verify that behavior modifications through system messages are thread-safe.
 */
class ActorIntegrationSpec extends FunSuite {
  private val eventContext = EventContext()
  import Threading.defaultContext

  given Timeout: FiniteDuration = 5.seconds

  override def beforeEach(context: BeforeEach): Unit =
    eventContext.start()

  override def afterEach(context: AfterEach): Unit =
    eventContext.stop()

  private def close(actor: Actor[?, ?, ?] & Closeable): Unit = {
    actor.close()
    waitFor(actor.isClosedSignal, true)
  }

  private def create[Msg, Rsp, State](state: State, pf: Actor.PF[Msg, Rsp, State]): Actor[Msg, Rsp, State] & Closeable & Pausable =
    Actor(state, pf).asInstanceOf[Actor[Msg, Rsp, State] & Closeable & Pausable]

  // ============================================================================
  // Thread Safety Tests for Behavior Modifications
  // ============================================================================

  /**
   * Test that concurrent behavior additions through system messages are thread-safe.
   * This verifies that adding behaviors from multiple threads doesn't corrupt the
   * behavior list or cause any race conditions.
   */
  test("Concurrent behavior additions through system messages are thread-safe") {
    val actor = create[Int, String, Int](0, {
      case (msg, _) => Some(s"Default: $msg")
    })
    
    // Get the SystemMsg type from the actor instance
    import actor.SystemMsg
    
    val numThreads = 10
    val behaviorsPerThread = 100
    val totalBehaviors = numThreads * behaviorsPerThread
    
    // Track which behaviors were successfully added (using atomic mutate)
    val addedCount = SourceSignal(0)
    
    // Create a custom behavior that records its ID when added
    def createTrackingBehavior(id: String): Actor.PF[Int, String, Int] = {
      case (msg, _) if msg == id.hashCode => Some(s"Behavior-$id: $msg")
    }
    
    // Add behaviors concurrently from multiple threads
    val futures: Seq[Future[Unit]] = (0 until numThreads).map { threadId =>
      Future {
        (0 until behaviorsPerThread).foreach { i =>
          val behaviorId = s"thread-$threadId-behavior-$i"
          val behavior = createTrackingBehavior(behaviorId)
          // Add behavior via system message
          val future = actor.ask(SystemMsg.AddBehavior(behaviorId, behavior))
          // Wait for completion to ensure it's processed
          Await.result(future, 1.second)
          // Increment count atomically
          addedCount.mutate(_ + 1)
        }
      }
    }
    
    // Wait for all threads to complete
    val allFutures: Seq[Future[Unit]] = futures
    Await.result(Future.sequence(allFutures), 10.seconds)
    
    // Verify all behaviors were added
    waitFor(addedCount, totalBehaviors)
    
    // Verify we can retrieve all added behaviors
    val retrievedBehaviors = (0 until numThreads).flatMap { threadId =>
      (0 until behaviorsPerThread).map { i =>
        val behaviorId = s"thread-$threadId-behavior-$i"
        actor.getBehavior(behaviorId)
      }
    }.flatten
    
    assertEquals(retrievedBehaviors.size, totalBehaviors)
    
    close(actor)
  }

  /**
   * Test that concurrent behavior additions and removals are thread-safe.
   */
  test("Concurrent behavior additions and removals through system messages are thread-safe") {
    val actor = create[Int, String, Int](0, {
      case (msg, _) => Some(s"Default: $msg")
    })
    
    import actor.SystemMsg
    
    val numOperations = 100
    val behaviorIds = (0 until numOperations).map(i => s"behavior-$i").toList
    
    def createBehavior(id: String): Actor.PF[Int, String, Int] = {
      case (msg, _) if msg == id.hashCode => Some(s"Behavior-$id: $msg")
    }
    
    // Perform concurrent add/remove operations
    val futures: Seq[Future[Unit]] = behaviorIds.map { id =>
      Future {
        if (id.hashCode % 2 == 0) {
          // Add behavior
          val future = actor.ask(SystemMsg.AddBehavior(id, createBehavior(id)))
          Await.result(future, 1.second)
        } else {
          // Try to remove behavior (may or may not exist)
          val future = actor.ask(SystemMsg.RemoveBehavior(id))
          Await.result(future, 1.second)
        }
      }
    }
    
    // Wait for all operations to complete
    Await.result(Future.sequence(futures), 10.seconds)
    
    // Verify the behavior map is consistent
    val expectedBehaviors = behaviorIds.filter(id => id.hashCode % 2 == 0).toSet
    
    // Verify all added behaviors are retrievable
    expectedBehaviors.foreach { id =>
      assert(actor.getBehavior(id).isDefined, s"Behavior $id should be present")
    }
    
    // Verify removed behaviors are not present
    behaviorIds.filter(id => id.hashCode % 2 != 0).foreach { id =>
      assert(actor.getBehavior(id).isEmpty, s"Behavior $id should be removed")
    }
    
    close(actor)
  }

  /**
   * Test that behavior modifications don't interfere with message processing.
   */
  test("Behavior modifications during message processing are thread-safe") {
    val actor = create[Int, String, Int](0, {
      case (msg, _) => Some(s"Default: $msg")
    })
    
    import actor.SystemMsg
    
    val messagesToSend = 1000
    val behaviorModifications = 100
    
    // Track processed messages (using atomic mutate)
    val processedCount = SourceSignal(0)
    
    // Add a behavior that records processed messages - this should match ALL messages
    // by using a catch-all pattern
    val recordingBehavior: Actor.PF[Int, String, Int] = {
      case (msg, _) =>
        processedCount.mutate(_ + 1)
        Some(s"Recorded: $msg")
    }
    
    // First, add the recording behavior
    actor.ask(SystemMsg.AddBehavior("recorder", recordingBehavior))
    // Wait for behavior to be added
    Thread.sleep(100)
    assert(actor.getBehavior("recorder").isDefined)
    
    // Send messages concurrently with behavior modifications
    val messageFutures: Seq[Future[String]] = (0 until messagesToSend).map { i =>
      Future {
        val response = actor.ask(i)
        Await.result(response, 1.second)
      }
    }
    
    val modificationFutures: Seq[Future[Unit]] = (0 until behaviorModifications).map { i =>
      Future {
        if (i % 2 == 0) {
          // Add a temporary behavior - use a message value that won't match any sent messages
          val tempId = s"temp-$i"
          val tempBehavior: Actor.PF[Int, String, Int] = {
            case (msg, _) if msg == -999999 => Some(s"Temp-$tempId: $msg")
          }
          val future = actor.ask(SystemMsg.AddBehavior(tempId, tempBehavior))
          Await.result(future, 1.second)
        } else {
          // Remove a behavior (try to remove temp behaviors)
          val tempId = s"temp-${i-1}"
          val future = actor.ask(SystemMsg.RemoveBehavior(tempId))
          Await.result(future, 1.second)
        }
      }
    }
    
    // Wait for all operations to complete
    Await.result(Future.sequence(messageFutures), 10.seconds)
    Await.result(Future.sequence(modificationFutures), 10.seconds)
    
    // Verify all messages were processed
    waitFor(processedCount, messagesToSend)
    assertEquals(processedCount.currentValue.getOrElse(0), messagesToSend)
    
    close(actor)
  }

  /**
   * Test that removing a behavior that doesn't exist doesn't cause errors.
   */
  test("Removing non-existent behavior is safe") {
    val actor = create[Int, String, Int](0, {
      case (msg, _) => Some(s"Default: $msg")
    })
    
    import actor.SystemMsg
    
    // Try to remove a behavior that doesn't exist
    val future = actor.ask(SystemMsg.RemoveBehavior("non-existent"))
    
    // Should complete successfully without error
    Await.result(future, 1.second)
    
    close(actor)
  }

  /**
   * Test that adding a behavior with duplicate ID does NOT replace the existing one.
   * This is the current behavior - duplicate IDs are ignored.
   */
  test("Adding behavior with duplicate ID does not replace existing behavior") {
    val actor = create[Int, String, Int](0, {
      case (msg, _) => Some(s"Default: $msg")
    })
    
    import actor.SystemMsg
    
    val behaviorId = "test-behavior"
    
    // Add first behavior
    val behavior1: Actor.PF[Int, String, Int] = {
      case (msg, _) if msg == 1 => Some(s"First: $msg")
    }
    actor.ask(SystemMsg.AddBehavior(behaviorId, behavior1))
    // Wait for behavior to be added
    Thread.sleep(100)
    assert(actor.getBehavior(behaviorId).isDefined)
    
    // Verify first behavior works
    val response1 = actor.ask(1)
    assertEquals(Await.result(response1, 1.second), "First: 1")
    
    // Try to add second behavior with same ID - this should be ignored
    val behavior2: Actor.PF[Int, String, Int] = {
      case (msg, _) if msg == 1 => Some(s"Second: $msg")
    }
    actor.ask(SystemMsg.AddBehavior(behaviorId, behavior2))
    // Wait for the add attempt to complete (it will be ignored)
    Thread.sleep(100)
    
    // Verify first behavior is still active (duplicate IDs are not replaced)
    val response2 = actor.ask(1)
    assertEquals(Await.result(response2, 1.second), "First: 1")
    close(actor)
  }

  // ============================================================================
  // Message Processing During Behavior Modification Tests
  // ============================================================================

  /**
   * Test that messages sent while behaviors are being modified are processed correctly.
   * Note: Some messages might match the newly added behaviors, so we just verify
   * that all messages get responses and no exceptions occur.
   */
  test("Messages sent during behavior modification are processed correctly") {
    val actor = create[Int, String, Int](0, {
      case (msg, _) => Some(s"Default: $msg")
    })
    
    import actor.SystemMsg
    
    val messages = (0 until 100).toList
    
    // Start sending messages
    val messageFutures: Seq[Future[String]] = messages.map { msg =>
      Future {
        val response = actor.ask(msg)
        Await.result(response, 1.second)
      }
    }
    
    // Concurrently modify behaviors
    val modificationFutures: Seq[Future[Unit]] = (0 until 50).map { i =>
      Future {
        val behaviorId = s"mod-$i"
        val behavior: Actor.PF[Int, String, Int] = {
          case (msg, _) if msg == i * 1000 => Some(s"Modified-$behaviorId: $msg")
        }
        val future = actor.ask(SystemMsg.AddBehavior(behaviorId, behavior))
        Await.result(future, 1.second)
      }
    }
    
    // Wait for all operations to complete
    Await.result(Future.sequence(messageFutures), 10.seconds)
    Await.result(Future.sequence(modificationFutures), 10.seconds)
    
    // Verify all messages got responses (no exceptions)
    val messageResults: Seq[String] = messageFutures.map(f => Await.result(f, 1.second))
    assertEquals(messageResults.size, messages.size)
    // All responses should be non-empty
    assert(messageResults.forall(_.nonEmpty))
    
    close(actor)
  }

  /**
   * Test that behavior modifications don't cause message loss.
   */
  test("Behavior modifications do not cause message loss") {
    val actor = create[Int, String, Int](0, {
      case (msg, _) => Some(s"Default: $msg")
    })
    
    import actor.SystemMsg
    
    val numMessages = 1000
    val receivedCount = SourceSignal(0)
    
    // Add a behavior that records received messages - catch-all pattern
    val recordingBehavior: Actor.PF[Int, String, Int] = {
      case (msg, _) =>
        receivedCount.mutate(_ + 1)
        Some(s"Recorded: $msg")
    }
    actor.ask(SystemMsg.AddBehavior("recorder", recordingBehavior))
    // Wait for behavior to be added
    Thread.sleep(100)
    assert(actor.getBehavior("recorder").isDefined)
    
    // Send messages
    val futures: Seq[Future[String]] = (0 until numMessages).map { i =>
      Future {
        val response = actor.ask(i)
        Await.result(response, 1.second)
      }
    }
    
    // Concurrently modify behaviors - use message values that won't match any sent messages
    val modificationFutures: Seq[Future[Unit]] = (0 until 100).map { i =>
      Future {
        val behaviorId = s"temp-$i"
        val behavior: Actor.PF[Int, String, Int] = {
          case (msg, _) if msg == -999999 - i => Some(s"Temp: $msg")
        }
        val future = actor.ask(SystemMsg.AddBehavior(behaviorId, behavior))
        Await.result(future, 1.second)
        Thread.sleep(1) // Small delay
        val removeFuture = actor.ask(SystemMsg.RemoveBehavior(behaviorId))
        Await.result(removeFuture, 1.second)
      }
    }
    
    // Wait for all operations to complete
    Await.result(Future.sequence(futures), 10.seconds)
    Await.result(Future.sequence(modificationFutures), 10.seconds)
    
    // Verify all messages were received
    waitFor(receivedCount, numMessages)
    assertEquals(receivedCount.currentValue.getOrElse(0), numMessages)
    
    close(actor)
  }
}
