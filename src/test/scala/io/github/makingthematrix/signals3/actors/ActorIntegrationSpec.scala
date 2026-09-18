package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.testutils.*
import io.github.makingthematrix.signals3.*
import io.github.makingthematrix.signals3.actors.Actor.HeartBeatStrategy
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

  private def closeChild(actor: Actor[?, ?, ?]): Unit = {
    actor.asInstanceOf[Closeable].close()
    waitFor(actor.isClosedSignal, true)
  }

  private def create[Msg, Rsp, State](state: State, pf: Actor.PF[Msg, Rsp, State]): Actor[Msg, Rsp, State] & Closeable & Pausable =
    ActorBuilder(state).withBehaviorPF(pf).build().asInstanceOf[Actor[Msg, Rsp, State] & Closeable & Pausable]

  private def create[Msg, Rsp, State](state: State, pf: Actor.PF[Msg, Rsp, State], hbs: HeartBeatStrategy): Actor[Msg, Rsp, State] & Closeable & Pausable =
    ActorBuilder(state)
      .withBehaviorPF(pf)
      .withHeartbeat(hbs)
      .build()
      .asInstanceOf[Actor[Msg, Rsp, State] & Closeable & Pausable]
  
  private def spawn[Msg, Rsp, State](parent: Actor[Msg, Rsp, State])(data: parent.SystemMsg.Spawn): Actor[Msg, Rsp, State] = {
    import parent.SystemMsg
    val rsp = Await.result(parent ? data, 5.seconds)
    rsp match {
      case SystemMsg.NewChild(child) => child
      case SystemMsg.InvalidId        => throw new AssertionError(s"Spawn with id '${data.id}' was rejected as InvalidId")
      case other                      => throw new AssertionError(s"Unexpected spawn response: $other")
    }
  }

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
    val behaviorsPerThread = 10
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
    val futures = behaviorIds.map { id =>
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
    
    val messagesToSend = 100
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
    
    val modificationFutures = (0 until behaviorModifications).map { i =>
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
    val modificationFutures = (0 until 50).map { i =>
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
    
    val numMessages = 100
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
    val modificationFutures = (0 until 100).map { i =>
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

  // ============================================================================
  // Spawn: Core ? Round-Trip
  // ============================================================================

  test("Spawn via ? returns NewChild with a working child") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"Parent: $msg") })
    import parent.SystemMsg

    val child = spawn(parent)(SystemMsg.Spawn())
    assert(child.isInitialized)
    assertEquals(resultCF(child ? 42), "Parent: 42") // inherited behavior
    closeChild(child)
    close(parent)
  }

  test("Spawn() with all defaults clones the parent's behavior") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"Default: $msg") })
    val child = spawn(parent)(parent.SystemMsg.Spawn())
    assertEquals(resultCF(child ? 7), "Default: 7")
    closeChild(child)
    close(parent)
  }

  test("The ? response carries the actual child reference, not just an ack") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"Parent: $msg") })
    import parent.SystemMsg
    val childBeh: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"Child: $msg") }
    val child = spawn(parent)(SystemMsg.Spawn(behaviors = List("c" -> childBeh)))
    assertEquals(resultCF(child ? 1), "Child: 1")
    assertEquals(resultCF(parent ? 1), "Parent: 1")
    closeChild(child)
    close(parent)
  }

  // ============================================================================
  // Spawn: Inheritance Semantics
  // ============================================================================

  test("Child inherits parent's state when Spawn.state is None") {
    val parent = create[Int, String, Int](100, { case (msg, _) => Some(s"P: $msg") })
    val child = spawn(parent)(parent.SystemMsg.Spawn())
    assertEquals(child.state, 100)
    closeChild(child)
    close(parent)
  }

  test("Child inherits parent's behaviors added via AddBehavior") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"Default: $msg") })
    import parent.SystemMsg
    val special: Actor.PF[Int, String, Int] = { case (42, _) => Some("Special: 42") }
    awaitCF(parent ? SystemMsg.AddBehavior("special", special))
    assert(parent.getBehavior("special").isDefined)

    val child = spawn(parent)(SystemMsg.Spawn())
    assertEquals(resultCF(child ? 42), "Special: 42")
    closeChild(child)
    close(parent)
  }

  test("Child has independent state from the parent") {
    val parent = create[Int, Int, Int](0, { case (msg, a) => a.state += msg; Some(a.state) })
    val child = spawn(parent)(parent.SystemMsg.Spawn())

    assertEquals(resultCF(parent ? 5), 5)
    assertEquals(resultCF(child ? 7), 7)
    assertEquals(parent.state, 5)
    assertEquals(child.state, 7)
    closeChild(child)
    close(parent)
  }

  test("Child inherits parent's heartbeat strategy functionally") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") }, Actor.HeartBeatStrategy.Linear(50))
    val child = spawn(parent)(parent.SystemMsg.Spawn())
    // If the child wrongly inherited a slow beat, this would time out
    assertEquals(resultCF(child ? 1), "P: 1")
    closeChild(child)
    close(parent)
  }

  // ============================================================================
  // Spawn: Explicit Parameters Override Inheritance
  // ============================================================================

  test("Spawn with explicit id sets child.id") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    val child = spawn(parent)(parent.SystemMsg.Spawn(id = "my-child"))
    assertEquals(child.id, "my-child")
    closeChild(child)
    close(parent)
  }

  test("Spawn with empty id auto-generates a unique id") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    val c1 = spawn(parent)(parent.SystemMsg.Spawn(id = ""))
    val c2 = spawn(parent)(parent.SystemMsg.Spawn(id = ""))
    assert(c1.id.nonEmpty)
    assert(c2.id.nonEmpty)
    assert(c1.id != c2.id)
    closeChild(c1)
    closeChild(c2)
    close(parent)
  }

  test("Spawn with explicit state overrides inheritance") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    val child = spawn(parent)(parent.SystemMsg.Spawn(state = Some(999)))
    assertEquals(child.state, 999)
    closeChild(child)
    close(parent)
  }

  test("Spawn with explicit behaviors overrides inheritance") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"Parent: $msg") })
    val childBeh: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"Child: $msg") }
    val child = spawn(parent)(parent.SystemMsg.Spawn(behaviors = List("c" -> childBeh)))
    assertEquals(resultCF(child ? 1), "Child: 1")
    assertEquals(resultCF(parent ? 1), "Parent: 1")
    closeChild(child)
    close(parent)
  }

  test("Spawn with explicit heartbeat overrides inheritance") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") }, Actor.HeartBeatStrategy.Linear(2000))
    val child = spawn(parent)(parent.SystemMsg.Spawn(heartbeat = Some(Actor.HeartBeatStrategy.Reactive(50, 1))))
    // Child should respond quickly; if it inherited the 2s linear beat, this would be slow
    assertEquals(resultCF(child ? 1)(using 1.seconds), "P: 1")
    closeChild(child)
    close(parent)
  }

  // ============================================================================
  // Spawn: onInit and Dispatch Modes
  // ============================================================================

  test("Spawn with onInit runs it on the child during initialization") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    val flag = Signal(false)
    var receivedChild: Option[Actor[Int, String, Int]] = None
    val child = spawn(parent)(parent.SystemMsg.Spawn(onInit = Some { c =>
      receivedChild = Some(c)
      flag ! true
    }))
    waitFor(flag, true)
    assert(receivedChild.contains(child), "onInit should receive the child actor")
    closeChild(child)
    close(parent)
  }

  test("Spawn with useSerialDispatch creates a serial child") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    val child = spawn(parent)(parent.SystemMsg.Spawn(useSerialDispatch = true))
    assert(child.isSerial)
    assertEquals(resultCF(child ? 1), "P: 1")
    closeChild(child)
    close(parent)
  }

  test("Spawn with explicit executionContext creates a parallel child") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    val child = spawn(parent)(parent.SystemMsg.Spawn(executionContext = Some(Threading.defaultContext)))
    assert(!child.isSerial)
    assertEquals(resultCF(child ? 1), "P: 1")
    closeChild(child)
    close(parent)
  }

  // ============================================================================
  // Spawn: Parent-Child Graph
  // ============================================================================

  test("Spawned child's parent is the spawning actor") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    val child = spawn(parent)(parent.SystemMsg.Spawn())
    assert(child.parent.contains(parent))
    closeChild(child)
    close(parent)
  }

  test("Multiple children have distinct ids and all work") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    val c1 = spawn(parent)(parent.SystemMsg.Spawn())
    val c2 = spawn(parent)(parent.SystemMsg.Spawn())
    val c3 = spawn(parent)(parent.SystemMsg.Spawn())
    assert(c1.id != c2.id)
    assert(c2.id != c3.id)
    assert(c1.id != c3.id)
    assertEquals(resultCF(c1 ? 1), "P: 1")
    assertEquals(resultCF(c2 ? 2), "P: 2")
    assertEquals(resultCF(c3 ? 3), "P: 3")
    closeChild(c1)
    closeChild(c2)
    closeChild(c3)
    close(parent)
  }

  test("Child can spawn a grandchild (hierarchical spawning)") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    val child = spawn(parent)(parent.SystemMsg.Spawn())
    val grandchild = spawn(child)(child.SystemMsg.Spawn())
    assert(grandchild.parent.contains(child))
    assertEquals(resultCF(grandchild ? 1), "P: 1")
    closeChild(grandchild)
    closeChild(child)
    close(parent)
  }

  test("Spawn with a duplicate explicit id is rejected with InvalidId") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    import parent.SystemMsg
    val first = spawn(parent)(SystemMsg.Spawn(id = "dup"))
    val secondRsp = Await.result(parent ? SystemMsg.Spawn(id = "dup"), 5.seconds)
    secondRsp match {
      case SystemMsg.InvalidId => // expected
      case other              => fail(s"Expected InvalidId, got $other")
    }
    assert(!first.isClosed, "First child should not be closed by the rejected spawn")
    assertEquals(resultCF(first ? 1), "P: 1")
    closeChild(first)
    close(parent)
  }

  test("A freed id can be re-spawned after the child closes") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    val c1 = spawn(parent)(parent.SystemMsg.Spawn(id = "x"))
    closeChild(c1)
    // After close, the child sends ActorClosed to parent, which removes it; re-spawn should succeed
    val c2 = spawn(parent)(parent.SystemMsg.Spawn(id = "x"))
    assertEquals(c2.id, "x")
    assertEquals(resultCF(c2 ? 1), "P: 1")
    closeChild(c2)
    close(parent)
  }

  // ============================================================================
  // Spawn: Lifecycle Interaction
  // ============================================================================

  test("Spawn works on a paused actor (system messages bypass pause)") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    import parent.SystemMsg
    parent ! SystemMsg.Pause
    waitFor(parent.isPausedSignal, true)
    val child = spawn(parent)(SystemMsg.Spawn())
    assertEquals(resultCF(child ? 1), "P: 1")
    closeChild(child)
    close(parent)
  }

  test("Spawn on a closed actor fails with ActorIsClosed") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    close(parent)
    import parent.SystemMsg
    intercept[IllegalStateException] {
      resultCF(parent ? SystemMsg.Spawn())
    }
  }

  test("Closing the parent cascades close to all children") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    val c1 = spawn(parent)(parent.SystemMsg.Spawn())
    val c2 = spawn(parent)(parent.SystemMsg.Spawn())
    close(parent) // close waits for parent.isClosedSignal; cascade is bang-based
    waitFor(c1.isClosedSignal, true)
    waitFor(c2.isClosedSignal, true)
  }

  test("Closing the parent cascades close to grandchildren") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    val child = spawn(parent)(parent.SystemMsg.Spawn())
    val grandchild = spawn(child)(child.SystemMsg.Spawn())
    close(parent)
    waitFor(child.isClosedSignal, true)
    waitFor(grandchild.isClosedSignal, true)
  }

  test("Closing a child independently removes it from the parent's children map") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    val c1 = spawn(parent)(parent.SystemMsg.Spawn(id = "x"))
    closeChild(c1)
    // Re-spawn with the same id should succeed once the parent has processed ActorClosed
    val c2 = spawn(parent)(parent.SystemMsg.Spawn(id = "x"))
    assertEquals(c2.id, "x")
    closeChild(c2)
    close(parent)
  }

  test("An independently closed child does not close its siblings or the parent") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    val c1 = spawn(parent)(parent.SystemMsg.Spawn())
    val c2 = spawn(parent)(parent.SystemMsg.Spawn())
    closeChild(c1)
    waitFor(c1.isClosedSignal, true)
    assert(!c2.isClosed, "Sibling should not be closed")
    assert(!parent.isClosed, "Parent should not be closed")
    assertEquals(resultCF(parent ? 1), "P: 1")
    assertEquals(resultCF(c2 ? 2), "P: 2")
    closeChild(c2)
    close(parent)
  }

  // ============================================================================
  // Spawn: Concurrency and Thread-Safety
  // ============================================================================

  test("Concurrent spawning from multiple threads is thread-safe") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    import parent.SystemMsg

    val numThreads = 10
    val spawnsPerThread = 5
    val expected = numThreads * spawnsPerThread
    val children = scala.collection.concurrent.TrieMap.empty[String, Actor[Int, String, Int]]

    val futures: Seq[Future[Unit]] = (0 until numThreads).map { _ =>
      Future {
        (0 until spawnsPerThread).foreach { _ =>
          val rsp = Await.result(parent ? SystemMsg.Spawn(), 2.seconds)
          rsp match {
            case SystemMsg.NewChild(c) => children.put(c.id, c)
            case other                 => throw new AssertionError(s"Unexpected response: $other")
          }
        }
      }
    }
    Await.result(Future.sequence(futures), 20.seconds)

    assertEquals(children.size, expected)
    // All ids distinct
    assertEquals(children.keySet.size, expected)
    // Every child initialized and working
    children.values.foreach { c =>
      assert(c.isInitialized)
      assertEquals(resultCF(c ? 1), "P: 1")
    }
    // Close all children then parent
    children.values.foreach(closeChild)
    close(parent)
  }

  test("Spawning does not interfere with concurrent message processing") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    import parent.SystemMsg

    val messageFutures: Seq[Future[String]] = (0 until 100).map { i =>
      Future { Await.result(parent ? i, 2.seconds) }
    }
    val spawnFutures: Seq[Future[Unit]] = (0 until 50).map { _ =>
      Future {
        val rsp = Await.result(parent ? SystemMsg.Spawn(), 2.seconds)
        rsp match {
          case SystemMsg.NewChild(_) => ()
          case other                  => throw new AssertionError(s"Unexpected: $other")
        }
      }
    }

    val msgResults = Await.result(Future.sequence(messageFutures), 20.seconds)
    Await.result(Future.sequence(spawnFutures), 20.seconds)
    assertEquals(msgResults.size, 100)
    assert(msgResults.forall(_.nonEmpty))
    close(parent)
  }

  test("Parent and child process messages concurrently without interference") {
    val parent = create[Int, Int, Int](0, { case (msg, a) => a.state += msg; Some(a.state) })
    val child = spawn(parent)(parent.SystemMsg.Spawn())

    val parentCount = SourceSignal(0)
    val childCount = SourceSignal(0)

    val parentFutures: Seq[Future[Unit]] = (0 until 50).map { i =>
      Future {
        Await.result(parent ? i, 2.seconds)
        parentCount.mutate(_ + 1)
      }
    }
    val childFutures: Seq[Future[Unit]] = (0 until 50).map { i =>
      Future {
        Await.result(child ? i, 2.seconds)
        childCount.mutate(_ + 1)
      }
    }

    Await.result(Future.sequence(parentFutures), 20.seconds)
    Await.result(Future.sequence(childFutures), 20.seconds)
    waitFor(parentCount, 50)
    waitFor(childCount, 50)
    // States diverge: sum 0..49 = 1225
    assertEquals(parent.state, 1225)
    assertEquals(child.state, 1225)
    closeChild(child)
    close(parent)
  }

  test("Concurrent spawns with the same explicit id yield exactly one child") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    import parent.SystemMsg

    val numThreads = 10
    val newChildCount = SourceSignal(0)
    val invalidIdCount = SourceSignal(0)
    val childRef = new java.util.concurrent.atomic.AtomicReference[Option[Actor[Int, String, Int]]](None)

    val futures: Seq[Future[Unit]] = (0 until numThreads).map { _ =>
      Future {
        val rsp = Await.result(parent ? SystemMsg.Spawn(id = "race"), 2.seconds)
        rsp match {
          case SystemMsg.NewChild(c) =>
            childRef.compareAndSet(None, Some(c))
            newChildCount.mutate(_ + 1)
          case SystemMsg.InvalidId =>
            invalidIdCount.mutate(_ + 1)
          case other =>
            throw new AssertionError(s"Unexpected response: $other")
        }
      }
    }
    Await.result(Future.sequence(futures), 20.seconds)

    waitFor(newChildCount, 1)
    waitFor(invalidIdCount, numThreads - 1)

    val child = childRef.get.getOrElse(fail("No child was created"))
    assertEquals(resultCF(child ? 1), "P: 1")
    closeChild(child)
    close(parent)
  }

  // ============================================================================
  // Spawn: Bang (!) Path
  // ============================================================================

  test("Spawn via ! creates a child without returning a reference") {
    val parent = create[Int, String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    import parent.SystemMsg
    val flag = Signal(false)
    var ref: Option[Actor[Int, String, Int]] = None
    parent ! SystemMsg.Spawn(onInit = Some { c =>
      ref = Some(c)
      flag ! true
    })
    waitFor(flag, true)
    val child = ref.getOrElse(fail("onInit did not capture the child reference"))
    assertEquals(resultCF(child ? 42), "P: 42")
    closeChild(child)
    close(parent)
  }
}
