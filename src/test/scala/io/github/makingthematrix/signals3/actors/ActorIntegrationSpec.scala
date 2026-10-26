package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.{CanBeClosed, *}
import io.github.makingthematrix.signals3.actors.Actor.{InvalidIdException, PF}
import io.github.makingthematrix.signals3.actors.SystemMsg.Close
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

  private def close(actorRef: ActorRef[?]): Unit = {
    awaitCF(actorRef ? Close)
  }

  private def create[Msg](pf: PF[Msg, Unit]): Actor[Msg, Unit] & Closeable & Pausable =
    ActorBuilder[Msg]()
      .withBehaviorPF(pf)
      .build()

  private def create[Msg, State](state: State, pf: PF[Msg, State]): Actor[Msg, State] & Closeable & Pausable =
    ActorBuilder[Msg, State](state)
      .withBehaviorPF(pf)
      .build()

  import SystemMsg.*

  @unchecked private def spawn[Msg, State](parent: Actor[Msg, ?])(data: SystemMsg.Spawn[Msg, State]): ActorRef[Msg] = {
    tryResultCF(parent ? data) match {
      case Success(NewChild(child)) => child.asInstanceOf[ActorRef[Msg]]
      case Failure(InvalidIdException(id)) if id == data.actorId => fail(s"Spawn with id $id was rejected as invalid")
      case other => fail(s"Unexpected spawn response: $other")
    }
  }

  @unchecked private def spawn[Msg, State](parent: ActorRef[Msg])(data: SystemMsg.Spawn[Msg, State]): ActorRef[Msg] = {
    tryResultCF(parent ? data) match {
      case Success(NewChild(child)) => child.asInstanceOf[ActorRef[Msg]]
      case Failure(InvalidIdException(id)) if id == data.actorId => fail(s"Spawn with id $id was rejected as invalid")
      case other => fail(s"Unexpected spawn response: $other")
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
    val actor = create[String, String]("0", {
      case (msg, _) => Some(s"Default: $msg")
    })

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
    val cf = actor.ask(SystemMsg.RemoveBehavior("non-existent"))
    
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
    val child = spawn(parent)(Spawn())
    assertEquals(resultCF(child ? "42"), "Parent: 42") // inherited behavior
    close(child)
    close(parent)
  }

  // ============================================================================
  // Spawn: Explicit Parameters Override Inheritance
  // ============================================================================

  test("Spawn with explicit id sets child.id") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    val child = spawn(parent)(Spawn(actorId = "my-child"))
    assertEquals(child.id, "my-child")
    close(child)
    close(parent)
  }

  test("Spawn with explicit behaviors overrides inheritance") {
    val parent = create[String] { case (msg, _) => Some(s"Parent: $msg") }
    val childBeh: PF[String, Unit] = { case (msg, _) => Some(s"Child: $msg") }
    val child = spawn(parent)(Spawn(behaviors = List("c" -> childBeh)))
    assertEquals(resultCF(child ? "1"), "Child: 1")
    assertEquals(resultCF(parent ? "1"), "Parent: 1")
    close(child)
    close(parent)
  }

  // ============================================================================
  // Spawn: onInit and Dispatch Modes
  // ============================================================================

  test("Spawn with onInit runs it on the child during initialization") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    val flag = Signal(false)
    var receivedChild: Option[MutableActor[String, Unit]] = None
    val child = spawn[String, Unit](parent)(Spawn(onInit = Some { c =>
      receivedChild = Some(c)
      flag ! true
    }))
    waitFor(flag, true)
    assert(receivedChild.exists(rc => rc.id == child.id), "onInit should receive the child actor")
    close(child)
    close(parent)
  }

  // ============================================================================
  // Spawn: Parent-Child Graph
  // ============================================================================


  test("Multiple children have distinct ids and all work") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    val c1 = spawn(parent)(Spawn())
    val c2 = spawn(parent)(Spawn())
    val c3 = spawn(parent)(Spawn())
    assert(c1.id != c2.id)
    assert(c2.id != c3.id)
    assert(c1.id != c3.id)
    assertEquals(resultCF(c1 ? "1"), "P: 1")
    assertEquals(resultCF(c2 ? "2"), "P: 2")
    assertEquals(resultCF(c3 ? "3"), "P: 3")
    close(c1)
    close(c2)
    close(c3)
    close(parent)
  }

  test("Child can spawn a grandchild (hierarchical spawning)") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    val child = spawn(parent)(Spawn())
    val grandchild = spawn(child)(Spawn())
    assertEquals(resultCF(grandchild ? "1"), "P: 1")
    close(grandchild)
    close(child)
    close(parent)
  }

  test("Spawn with a duplicate explicit id is rejected with InvalidId") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    val first = spawn(parent)(Spawn(actorId = "dup"))
    tryResultCF(parent ? Spawn(actorId = "dup")) match {
      case Failure(InvalidIdException("dup")) => // expected
      case other => fail(s"Expected InvalidId, got $other")
    }
    assertEquals(resultCF(first ? "1"), "P: 1")
    close(first)
    close(parent)
  }

  test("A freed id can be re-spawned after the child closes") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    val c1 = spawn(parent)(Spawn(actorId = "x"))
    close(c1)
    // After close, the child sends ActorClosed to parent, which removes it; re-spawn should succeed
    val c2 = spawn(parent)(Spawn(actorId = "x"))
    assertEquals(c2.id, "x")
    assertEquals(resultCF(c2 ? "1"), "P: 1")
    close(c2)
    close(parent)
  }

  // ============================================================================
  // Spawn: Lifecycle Interaction
  // ============================================================================

  test("Spawn works on a paused actor (system messages bypass pause)") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    parent ! SystemMsg.Pause
    waitFor(parent.isPausedSignal, true)
    val child = spawn(parent)(Spawn())
    assertEquals(resultCF(child ? "1"), "P: 1")
    close(child)
    close(parent)
  }

  test("Spawn on a closed actor fails with ActorIsClosed") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    close(parent)
    intercept[IllegalStateException] {
      resultCF(parent ? Spawn())
    }
  }

  test("Closing the parent cascades close to all children") {
    val inited = Signal(0)
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    var c1: Actor[String, Unit] & CanBeClosed = null
    spawn[String, Unit](parent)(Spawn(onInit = Some { c => c1 = c; inited.mutate(n => n + 1) }))
    var c2: Actor[String, Unit] & CanBeClosed = null
    spawn[String, Unit](parent)(Spawn(onInit = Some { c => c2 = c; inited.mutate(n => n + 1) }))

    waitFor(inited, 2)

    close(parent) // close waits for parent.isClosedSignal; cascade is bang-based
    waitFor(c1.isClosedSignal, true)
    waitFor(c2.isClosedSignal, true)
  }

  test("Closing the parent cascades close to grandchildren") {
    val inited = Signal(0)
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    var child: Actor[String, Unit] & CanBeClosed = null
    spawn[String, Unit](parent)(Spawn(onInit = Some { c => child = c; inited.mutate(n => n + 1) }))
    var grandchild: Actor[String, Unit] & CanBeClosed = null
    spawn[String, Unit](child)(Spawn(onInit = Some { c => grandchild = c; inited.mutate(n => n + 1) }))

    waitFor(inited, 2)

    close(parent)
    waitFor(child.isClosedSignal, true)
    waitFor(grandchild.isClosedSignal, true)
  }

  test("An independently closed child does not close its siblings or the parent") {
    val inited = Signal(0)

    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    var c1: Actor[String, Unit] & CanBeClosed = null
    spawn[String, Unit](parent)(Spawn(onInit = Some { c => c1 = c; inited.mutate(n => n + 1) }))
    var c2: Actor[String, Unit] & CanBeClosed = null
    spawn[String, Unit](parent)(Spawn(onInit = Some { c => c2 = c; inited.mutate(n => n + 1) }))

    waitFor(inited, 2)

    awaitCF(c1 ? Close)

    waitFor(c1.isClosedSignal, true)
    assert(!c2.isClosed, "Sibling should not be closed")

    assert(!parent.isClosed, "Parent should not be closed")
    assertEquals(resultCF(parent ? "1"), "P: 1")
    assertEquals(resultCF(c2 ? "2"), "P: 2")

    close(parent)
  }

  // ============================================================================
  // Spawn: Concurrency and Thread-Safety
  // ============================================================================

  test("Concurrent spawning from multiple threads is thread-safe") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }

    val numThreads = 10
    val spawnsPerThread = 5
    val expected = numThreads * spawnsPerThread
    val children = scala.collection.concurrent.TrieMap.empty[String, ActorRef[String]]

    val futures: Seq[Future[Unit]] = (0 until numThreads).map { _ =>
      Future {
        (0 until spawnsPerThread).foreach { _ =>
          resultCF(parent ? Spawn[String, Unit]()) match {
            case SystemMsg.NewChild(c) => children.put(c.id, c.asInstanceOf[ActorRef[String]])
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
      assertEquals(resultCF(c ? "1"), "P: 1")
    }
    // Close all children then parent
    children.values.foreach(close)
    close(parent)
  }

  test("Spawning does not interfere with concurrent message processing") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    val messageFutures: Seq[Future[String]] = (0 until 100).map { i =>
      Future { resultCF(parent ? i.toString) }
    }
    val spawnFutures: Seq[Future[Unit]] = (0 until 50).map { _ =>
      Future {
        resultCF(parent ? Spawn()) match {
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
    val child = spawn(parent)(Spawn())

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

    close(child)
    close(parent)
  }

  test("Concurrent spawns with the same explicit id yield exactly one child") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    val numThreads = 10
    val newChildCount = SourceSignal(0)
    val invalidIdCount = SourceSignal(0)
    val childRef = new java.util.concurrent.atomic.AtomicReference[Option[ActorRef[String]]](None)

    val futures: Seq[Future[Unit]] = (0 until numThreads).map { _ =>
      Future {
        tryResultCF(parent ? Spawn[String, Unit](actorId = "race")) match {
          case Success(SystemMsg.NewChild(c)) =>
            childRef.compareAndSet(None, Some(c.asInstanceOf[ActorRef[String]]))
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
    close(child)
    close(parent)
  }

  // ============================================================================
  // Spawn: Bang (!) Path
  // ============================================================================

  test("Spawn via ! creates a child without returning a reference") {
    val parent = create[String] { case (msg, _) => Some(s"P: $msg") }
    val flag = Signal(false)
    var ref: Option[MutableActor[String, Unit]] = None
    parent ! Spawn[String, Unit](onInit = Some { c =>
      ref = Some(c)
      flag ! true
    })
    waitFor(flag, true)
    val child = ref.getOrElse(fail("onInit did not capture the child reference"))
    assertEquals(resultCF(child ? "42"), "P: 42")
    awaitCF(child ? Close)
    close(parent)
  }
}
