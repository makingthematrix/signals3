package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.*
import io.github.makingthematrix.signals3.actors.Actor.{InvalidIdException, PF}
import io.github.makingthematrix.signals3.testutils.*
import munit.FunSuite

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

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

  private def close(actor: Actor[?, ?] & Closeable): Unit = {
    actor.close()
    waitFor(actor.isClosedSignal, true)
  }

  private def closeChild(actor: Actor[?, ?]): Unit = {
    actor.asInstanceOf[Closeable].close()
    waitFor(actor.isClosedSignal, true)
  }

  private def create[Msg](pf: PF[Msg, Unit]): Actor[Msg, Unit] & Closeable & Pausable =
    ActorBuilder[Msg]()
      .withBehaviorPF(pf)
      .build()

  private def create[Msg, State](state: State, pf: PF[Msg, State]): Actor[Msg, State] & Closeable & Pausable =
    ActorBuilder[Msg, State](state)
      .withBehaviorPF(pf)
      .build()

  private def spawn[Msg, State](parent: Actor[Msg, State])(data: parent.SystemMsg.Spawn): Actor[Msg, State] =
    tryResultCF(parent ? data) match {
      case Success(parent.SystemMsg.NewChild(child)) => child
      case Failure(InvalidIdException(id)) if id == data.actorId => fail(s"Spawn with id $id was rejected as invalid")
      case other => fail(s"Unexpected spawn response: $other")
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
    val actor = create[String, String]("0", {
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
    def createTrackingBehavior(id: String): Actor.PF[String, String] = {
      case (msg, _) if msg == id.hashCode.toString => Some(s"Behavior-$id: $msg")
    }
    
    // Add behaviors concurrently from multiple threads
    val futures: Seq[Future[Unit]] = (0 until numThreads).map { threadId =>
      Future {
        (0 until behaviorsPerThread).foreach { i =>
          val behaviorId = s"thread-$threadId-behavior-$i"
          val behavior = createTrackingBehavior(behaviorId)
          // Add behavior via system message
          val cf = actor.ask(SystemMsg.AddBehavior(behaviorId, behavior))
          // Wait for completion to ensure it's processed
          awaitCF(cf)
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
    val actor = create[String, String]("0", {
      case (msg, _) => Some(s"Default: $msg")
    })
    
    import actor.SystemMsg
    
    val numOperations = 100
    val behaviorIds = (0 until numOperations).map(i => s"behavior-$i").toList
    
    def createBehavior(id: String): Actor.PF[String, String] = {
      case (msg, _) if msg == id.hashCode.toString => Some(s"Behavior-$id: $msg")
    }
    
    // Perform concurrent add/remove operations
    val futures = behaviorIds.map { id =>
      Future {
        if (id.hashCode % 2 == 0) {
          // Add behavior
          val cf = actor.ask(SystemMsg.AddBehavior(id, createBehavior(id)))
          awaitCF(cf)
        } else {
          // Try to remove behavior (may or may not exist)
          val cf = actor.ask(SystemMsg.RemoveBehavior(id))
          awaitCF(cf)
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
   * Test that removing a behavior that doesn't exist doesn't cause errors.
   */
  test("Removing non-existent behavior is safe") {
    val actor = create[String, String]("0", {
      case (msg, _) => Some(s"Default: $msg")
    })

    // Try to remove a behavior that doesn't exist
    val cf = actor.ask(actor.SystemMsg.RemoveBehavior("non-existent"))
    
    // Should complete successfully without error
    awaitCF(cf)
    
    close(actor)
  }

  // ============================================================================
  // Message Processing During Behavior Modification Tests
  // ============================================================================

  enum MyMsg{
    case MyInt(n: Int)
    case MyStr(str: String)
  }
  
  object MyMsg {
    def apply(n: Int): MyMsg = MyInt(n)
    def apply(str: String): MyMsg = MyStr(str)
  }

  import MyMsg.*


  /**
   * Test that behavior modifications don't cause message loss.
   */
  test("Behavior modifications do not cause message loss") {
    val actor = create[MyMsg] { case (MyInt(n), _) => Some(MyMsg(s"Default: $n")) }
    
    import actor.SystemMsg
    
    val numMessages = 100
    val receivedCount = SourceSignal(0)
    
    // Add a behavior that records received messages - catch-all pattern
    val recordingBehavior: PF[MyMsg, Unit] = {
      case (MyInt(n), _) =>
        receivedCount.mutate(_ + 1)
        Some(MyStr(s"Recorded: $n"))
    }
    actor.ask(SystemMsg.AddBehavior("recorder", recordingBehavior))
    // Wait for behavior to be added
    Thread.sleep(100)
    assert(actor.getBehavior("recorder").isDefined)
    
    // Send messages
    val futures: Seq[Future[MyMsg]] = (0 until numMessages).map { i =>
      Future {
        val response = actor.ask(MyInt(i))
        Await.result(response, 1.second)
      }
    }
    
    // Concurrently modify behaviors - use message values that won't match any sent messages
    val modificationFutures = (0 until 100).map { i =>
      Future {
        val behaviorId = s"temp-$i"
        val behavior: PF[MyMsg, Unit] = {
          case (MyInt(n), _) if n == -999999 - i => Some(MyStr(s"Temp: $n"))
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
    val parent = create[String] { case (msg, _) => Some(s"Parent: $msg") }
    val child = spawn(parent)(parent.SystemMsg.Spawn())
    assert(child.isInitialized)
    assertEquals(resultCF(child ? "42"), "Parent: 42") // inherited behavior
    closeChild(child)
    close(parent)
  }

  // ============================================================================
  // Spawn: Inheritance Semantics
  // ============================================================================

  test("Child inherits parent's state when Spawn.state is None") {
    val parent = create[String, Int](100, { case (msg, _) => Some(s"P: $msg") })
    val child = spawn(parent)(parent.SystemMsg.Spawn())
    assertEquals(child.state, 100)
    closeChild(child)
    close(parent)
  }

  test("Child inherits parent's behaviors added via AddBehavior") {
    val parent = create[String] { case (msg, _) => Some(s"Default: $msg") }
    import parent.SystemMsg
    val special: PF[String, Unit] = { case ("42", _) => Some("Special: 42") }
    awaitCF(parent ? SystemMsg.AddBehavior("special", special))
    assert(parent.getBehavior("special").isDefined)

    val child = spawn(parent)(SystemMsg.Spawn())
    assertEquals(resultCF(child ? "42"), "Special: 42")
    closeChild(child)
    close(parent)
  }

  test("Child has independent state from the parent") {
    val parent = create[Int, Int](0, { case (msg, a) => a.state += msg; Some(a.state) })
    val child = spawn(parent)(parent.SystemMsg.Spawn())

    assertEquals(resultCF(parent ? 5), 5)
    assertEquals(resultCF(child ? 7), 7)
    assertEquals(parent.state, 5)
    assertEquals(child.state, 7)
    closeChild(child)
    close(parent)
  }

  // ============================================================================
  // Spawn: Explicit Parameters Override Inheritance
  // ============================================================================

  test("Spawn with explicit id sets child.id") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    val child = spawn(parent)(parent.SystemMsg.Spawn(actorId = "my-child"))
    assertEquals(child.id, "my-child")
    closeChild(child)
    close(parent)
  }

  test("Spawn with explicit state overrides inheritance") {
    val parent = create[String, Int](0, { case (msg, _) => Some(s"P: $msg") })
    val child = spawn(parent)(parent.SystemMsg.Spawn(state = Some(999)))
    assertEquals(child.state, 999)
    closeChild(child)
    close(parent)
  }

  test("Spawn with explicit behaviors overrides inheritance") {
    val parent = create[String] { case (msg, _) => Some(s"Parent: $msg") }
    val childBeh: PF[String, Unit] = { case (msg, _) => Some(s"Child: $msg") }
    val child = spawn(parent)(parent.SystemMsg.Spawn(behaviors = List("c" -> childBeh)))
    assertEquals(resultCF(child ? "1"), "Child: 1")
    assertEquals(resultCF(parent ? "1"), "Parent: 1")
    closeChild(child)
    close(parent)
  }

  // ============================================================================
  // Spawn: onInit and Dispatch Modes
  // ============================================================================

  test("Spawn with onInit runs it on the child during initialization") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    val flag = Signal(false)
    var receivedChild: Option[Actor[String, Unit]] = None
    val child = spawn(parent)(parent.SystemMsg.Spawn(onInit = Some { c =>
      receivedChild = Some(c)
      flag ! true
    }))
    waitFor(flag, true)
    assert(receivedChild.contains(child), "onInit should receive the child actor")
    closeChild(child)
    close(parent)
  }

  // ============================================================================
  // Spawn: Parent-Child Graph
  // ============================================================================

  test("Spawned child's parent is the spawning actor") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    val child = spawn(parent)(parent.SystemMsg.Spawn())
    assert(child.parent.contains(parent))
    closeChild(child)
    close(parent)
  }

  test("Multiple children have distinct ids and all work") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    val c1 = spawn(parent)(parent.SystemMsg.Spawn())
    val c2 = spawn(parent)(parent.SystemMsg.Spawn())
    val c3 = spawn(parent)(parent.SystemMsg.Spawn())
    assert(c1.id != c2.id)
    assert(c2.id != c3.id)
    assert(c1.id != c3.id)
    assertEquals(resultCF(c1 ? "1"), "P: 1")
    assertEquals(resultCF(c2 ? "2"), "P: 2")
    assertEquals(resultCF(c3 ? "3"), "P: 3")
    closeChild(c1)
    closeChild(c2)
    closeChild(c3)
    close(parent)
  }

  test("Child can spawn a grandchild (hierarchical spawning)") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    val child = spawn(parent)(parent.SystemMsg.Spawn())
    val grandchild = spawn(child)(child.SystemMsg.Spawn())
    assert(grandchild.parent.contains(child))
    assertEquals(resultCF(grandchild ? "1"), "P: 1")
    closeChild(grandchild)
    closeChild(child)
    close(parent)
  }

  test("Spawn with a duplicate explicit id is rejected with InvalidId") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    import parent.SystemMsg
    val first = spawn(parent)(SystemMsg.Spawn(actorId = "dup"))
    tryResultCF(parent ? SystemMsg.Spawn(actorId = "dup")) match {
      case Failure(InvalidIdException("dup")) => // expected
      case other => fail(s"Expected InvalidId, got $other")
    }
    assert(!first.isClosed, "First child should not be closed by the rejected spawn")
    assertEquals(resultCF(first ? "1"), "P: 1")
    closeChild(first)
    close(parent)
  }

  test("A freed id can be re-spawned after the child closes") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    val c1 = spawn(parent)(parent.SystemMsg.Spawn(actorId = "x"))
    closeChild(c1)
    // After close, the child sends ActorClosed to parent, which removes it; re-spawn should succeed
    val c2 = spawn(parent)(parent.SystemMsg.Spawn(actorId = "x"))
    assertEquals(c2.id, "x")
    assertEquals(resultCF(c2 ? "1"), "P: 1")
    closeChild(c2)
    close(parent)
  }

  // ============================================================================
  // Spawn: Lifecycle Interaction
  // ============================================================================

  test("Spawn works on a paused actor (system messages bypass pause)") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    import parent.SystemMsg
    parent ! SystemMsg.Pause
    waitFor(parent.isPausedSignal, true)
    val child = spawn(parent)(SystemMsg.Spawn())
    assertEquals(resultCF(child ? "1"), "P: 1")
    closeChild(child)
    close(parent)
  }

  test("Spawn on a closed actor fails with ActorIsClosed") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    close(parent)
    import parent.SystemMsg
    intercept[IllegalStateException] {
      resultCF(parent ? SystemMsg.Spawn())
    }
  }

  test("Closing the parent cascades close to all children") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    val c1 = spawn(parent)(parent.SystemMsg.Spawn())
    val c2 = spawn(parent)(parent.SystemMsg.Spawn())
    close(parent) // close waits for parent.isClosedSignal; cascade is bang-based
    waitFor(c1.isClosedSignal, true)
    waitFor(c2.isClosedSignal, true)
  }

  test("Closing the parent cascades close to grandchildren") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    val child = spawn(parent)(parent.SystemMsg.Spawn())
    val grandchild = spawn(child)(child.SystemMsg.Spawn())
    close(parent)
    waitFor(child.isClosedSignal, true)
    waitFor(grandchild.isClosedSignal, true)
  }

  test("An independently closed child does not close its siblings or the parent") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    val c1 = spawn(parent)(parent.SystemMsg.Spawn())
    val c2 = spawn(parent)(parent.SystemMsg.Spawn())
    closeChild(c1)
    waitFor(c1.isClosedSignal, true)
    assert(!c2.isClosed, "Sibling should not be closed")
    assert(!parent.isClosed, "Parent should not be closed")
    assertEquals(resultCF(parent ? "1"), "P: 1")
    assertEquals(resultCF(c2 ? "2"), "P: 2")
    closeChild(c2)
    close(parent)
  }

  // ============================================================================
  // Spawn: Concurrency and Thread-Safety
  // ============================================================================

  test("Concurrent spawning from multiple threads is thread-safe") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    import parent.SystemMsg

    val numThreads = 10
    val spawnsPerThread = 5
    val expected = numThreads * spawnsPerThread
    val children = scala.collection.concurrent.TrieMap.empty[String, Actor[String, Unit]]

    val futures: Seq[Future[Unit]] = (0 until numThreads).map { _ =>
      Future {
        (0 until spawnsPerThread).foreach { _ =>
          resultCF(parent ? SystemMsg.Spawn()) match {
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
      assertEquals(resultCF(c ? "1"), "P: 1")
    }
    // Close all children then parent
    children.values.foreach(closeChild)
    close(parent)
  }

  test("Spawning does not interfere with concurrent message processing") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    import parent.SystemMsg

    val messageFutures: Seq[Future[String]] = (0 until 100).map { i =>
      Future { resultCF(parent ? i.toString) }
    }
    val spawnFutures: Seq[Future[Unit]] = (0 until 50).map { _ =>
      Future {
        resultCF(parent ? SystemMsg.Spawn()) match {
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
    val parent = create[Int, Int](0, { case (msg, a) => a.state += msg; Some(a.state) })
    val child = spawn(parent)(parent.SystemMsg.Spawn())

    val parentCount = SourceSignal(0)
    val childCount = SourceSignal(0)

    val parentFutures: Seq[Future[Unit]] = (0 until 50).map { i =>
      Future {
        awaitCF(parent ? i)
        parentCount.mutate(_ + 1)
      }
    }
    val childFutures: Seq[Future[Unit]] = (0 until 50).map { i =>
      Future {
        awaitCF(child ? i)
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
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    import parent.SystemMsg

    val numThreads = 10
    val newChildCount = SourceSignal(0)
    val invalidIdCount = SourceSignal(0)
    val childRef = new java.util.concurrent.atomic.AtomicReference[Option[Actor[String, Unit]]](None)

    val futures: Seq[Future[Unit]] = (0 until numThreads).map { _ =>
      Future {
        tryResultCF(parent ? SystemMsg.Spawn(actorId = "race")) match {
          case Success(SystemMsg.NewChild(c)) =>
            childRef.compareAndSet(None, Some(c))
            newChildCount.mutate(_ + 1)
          case Failure(InvalidIdException("race")) =>
            invalidIdCount.mutate(_ + 1)
          case other =>
            fail(s"Unexpected response: $other")
        }
      }
    }
    Await.result(Future.sequence(futures), 20.seconds)

    waitFor(newChildCount, 1)
    waitFor(invalidIdCount, numThreads - 1)

    val child = childRef.get.getOrElse(fail("No child was created"))
    assertEquals(resultCF(child ? "1"), "P: 1")
    closeChild(child)
    close(parent)
  }

  // ============================================================================
  // Spawn: Bang (!) Path
  // ============================================================================

  test("Spawn via ! creates a child without returning a reference") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    import parent.SystemMsg
    val flag = Signal(false)
    var ref: Option[Actor[String, Unit]] = None
    parent ! SystemMsg.Spawn(onInit = Some { c =>
      ref = Some(c)
      flag ! true
    })
    waitFor(flag, true)
    val child = ref.getOrElse(fail("onInit did not capture the child reference"))
    assertEquals(resultCF(child ? "42"), "P: 42")
    closeChild(child)
    close(parent)
  }
}
