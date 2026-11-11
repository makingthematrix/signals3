package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.*
import Actor.{InvalidIdException, NoRefFound}
import SystemMsg.*
import actors.Utils.*
import munit.FunSuite
import io.github.makingthematrix.signals3.Behaviors.*
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

/**
 * Unit tests for ActorSystem functionality: registration, lookup, deregistration,
 * spawn integration, cross-actor communication through the registry, and concurrency.
 */
class ActorSystemSpec extends FunSuite {
  import Utils.*
  
  private val eventContext = EventContext()
  import Threading.defaultContext

  given Timeout: FiniteDuration = 5.seconds

  override def beforeEach(context: BeforeEach): Unit =
    eventContext.start()

  override def afterEach(context: AfterEach): Unit =
    eventContext.stop()
  
  // ============================================================================
  // 1. Construction and initialization
  // ============================================================================

  test("ActorSystem.apply is initialized and has the given id") {
    val sys = newSystem[String]("sys")
    assert(sys.isInitialized)
    assertEquals(sys.id, "sys")
    assert(!sys.isClosed)
    close(sys)
  }

  // ============================================================================
  // 2. Register
  // ============================================================================

  test("Register via ? stores the actor and returns Ref") {
    val sys = newSystem[String]("sys")
    val a = create[String]("manual") { case (msg, _) => Some(s"M: $msg") }
    resultCF(sys ? Register(a)) match {
      case Ref(ref) => assertEquals(ref.id, "manual")
      case other    => fail(s"Expected Ref, got $other")
    }
    close(a, sys)
  }

  test("Register with a duplicate id fails and does not overwrite the previous entry") {
    val sys = newSystem[String]("sys")
    val a = create[String]("dup") { case (msg, _) => Some(s"A: $msg") }
    val b = create[String]("dup") { case (msg, _) => Some(s"B: $msg") } // it's ok to have two unconnected actors with the same id

    tryResultCF(sys ? Register(a)) match {
      case Success(Ref(ref)) => assertEquals(ref.id, "dup")
      case Failure(ex)       => fail(ex.getMessage)
      case _                 => fail("registering the first actor failed")
    }

    // it's not ok rto egister them in the same actor system
    // but it's also not a failure: redundant Register messages may happen - they should be ignored
    tryResultCF(sys ? Register(b)) match {
      case Failure(InvalidIdException("dup")) =>
      case Success(Ref(ref))                  => fail("registering the second actor succeeded - it should have failed")
      case res                                => fail(s"something went wrong: $res")
    }

    val ref = awaitRef(sys, "dup")
    assertEquals(resultCF(ref ? "1"), "A: 1")
    close(a, b, sys)
  }

  // ============================================================================
  // 3. Spawn integration with the system registry
  // ============================================================================

  test("Spawned child auto-registers on the system") {
    val sys   = newSystem[String]("sys")
    val child = spawn(sys)(Spawn(actorId = "c"))
    val ref   = awaitRef(sys, "c")
    assertEquals(ref.path.actorId, "c")
    close(child); close(sys)
  }

  test("Grandchild auto-registers on the same system") {
    val sys        = newSystem[String]("sys")
    val child      = spawn(sys)(SystemMsg.Spawn())
    val grandchild = spawn(child)(Spawn())
    awaitRef(sys, grandchild.id)
    close(sys)
  }

  // ============================================================================
  // 4. AskForRef
  // ============================================================================

  test("AskForRef returns InvalidId for an unknown id") {
    val sys = newSystem[String]("sys")
    expectFailureCF(sys ? AskForRef("nonexistent"), InvalidIdException("nonexistent"))
    close(sys)
  }

  test("AskForRef with the system's own id behaves as a local lookup") {
    val sys = newSystem[String]("sys")
    val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    awaitRef(sys, "a")
    resultCF(sys ? AskForRef("a", sys.id)) match {
      case Ref(ref) => assert(ref.isLocal); assertEquals(ref.path, ActorPath.Local("a"))
      case other    => fail(s"Expected Ref, got $other")
    }
    expectFailureCF(sys ? AskForRef("nonexistent", sys.id), InvalidIdException("nonexistent"))
    close(a, sys)
  }

  test("AskForRef returns InvalidId after the actor is closed") {
    val sys = newSystem[String]("sys")
    val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    awaitRef(sys, "a")
    close(a)
    awaitInvalid(sys, "a")
    close(sys)
  }

  // ============================================================================
  // 5. ActorClosed
  // ============================================================================

  test("ActorClosed via ? removes the actor from the registry") {
    val sys = newSystem[String]("sys")
    val a   = create[String]("x") { case (msg, _) => Some(s"A: $msg") }
    awaitCF(sys ? Register(a))
    expectSuccessCF(sys ? ActorClosed("x"), Done)
    awaitInvalid(sys, "x")
    close(a, sys)
  }

  test("ActorClosed for a non-existent id is safe and returns InvalidId") {
    val sys = newSystem[String]("sys")
    expectFailureCF(sys ? ActorClosed("nope"), InvalidIdException("nope"))
    close(sys)
  }

  test("ActorClosed on system cascades to removeChild") {
    val sys   = newSystem[String]("sys")
    val child = spawn(sys)(Spawn(actorId = "c"))
    awaitRef(sys, "c")
    awaitCF(child ? Close)
    awaitInvalid(sys, "c")
    // Children map cleaned: can re-spawn with same id
    val child2 = spawn(sys)(Spawn(actorId = "c"))
    assertEquals(child2.id, "c")
    close(sys)
  }

  test("Closing a spawned child does not deregister its siblings") {
    val sys = newSystem[String]("sys")
    val c1  = spawn(sys)(Spawn(actorId = "c1"))
    val c2  = spawn(sys)(Spawn(actorId = "c2"))
    awaitRef(sys, "c1")
    awaitRef(sys, "c2")
    awaitCF(c1 ? Close)
    awaitInvalid(sys, "c1")
    val ref = awaitRef(sys, "c2")
    assertEquals(ref.path.actorId, "c2")
    close(c2); close(sys)
  }

  // ============================================================================
  // 7. ActorRef / Ref
  // ============================================================================

  test("Ref from AskForRef is usable for ? (ask)") {
    val sys = newSystem[String]("sys")
    val a   = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    val ref = awaitRef(sys, "a")
    expectSuccessCF(ref ? "42", "A: 42")
    close(a, sys)
  }

  test("Ref from AskForRef is usable for ! (bang)") {
    val sys      = newSystem[String]("sys")
    val received = SourceSignal(0)
    val a = ActorBuilder[String, Int](0)
      .withId("a")
      .withBehavior("default", { case (msg, _) => received.mutate(_ + 1); Some(s"A: $msg") })
      .withSystem(sys).build()
    val ref = awaitRef(sys, "a")
    ref ! "42"
    waitFor(received, 1)
    close(a, sys)
  }

  test("LocalActorRef.path is ActorPath.Local(actor.id)") {
    val sys = newSystem[String]("sys")
    val a   = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    val ref = awaitRef(sys, "a")
    assert(ref.isLocal)
    assertEquals(ref.path.actorId, "a")
    assertEquals(ref.path, ActorPath.Local("a"))
    close(a, sys)
  }

  // ============================================================================
  // 8. Cross-actor communication through the system
  // ============================================================================

  test("Two actors communicate via AskForRef") {
    val sys = newSystem[String]("sys")
    val a   = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    val b   = newActor(sys, "b", { case (msg, _) => Some(s"B: $msg") })
    awaitRef(sys, "a")
    awaitRef(sys, "b")
    val aRef = awaitRef(sys, "a")
    expectSuccessCF(aRef ? "10", "A: 10")
    val bRef = awaitRef(sys, "b")
    expectSuccessCF(bRef ? "20", "B: 20")
    close(a, b, sys)
  }

  // ============================================================================
  // 9. Concurrency
  // ============================================================================

  test("Concurrent registrations from multiple threads are thread-safe") {
    val sys = newSystem[String]("sys")
    val numThreads = 10
    val actorsPerThread = 5
    val actors = TrieMap.empty[String, Actor[String, Int] & Closeable]

    val futures: Seq[Future[Unit]] = (0 until numThreads).map { t =>
      Future {
        (0 until actorsPerThread).foreach { i =>
          val id = s"actor-$t-$i"
          val a = newActor(sys, id, { case (msg, _) => Some(s"$id: $msg") })
          actors.put(id, a)
        }
      }
    }
    Await.result(Future.sequence(futures), 10.seconds)

    actors.keys.foreach { id => awaitRef(sys, id) }
    assertEquals(actors.size, numThreads * actorsPerThread)
    close(actors.values.toArray*)
    close(sys)
  }

  test("Concurrent AskForRef queries during registration are safe") {
    val sys = newSystem[String]("sys")
    val numActors = 50
    val actors = (0 until numActors).map { i =>
      newActor(sys, s"a$i", { case (msg, _) => Some(s"a$i: $msg") })
    }

    val queryFutures: Seq[Future[Unit]] = actors.map { a =>
      Future { awaitRef(sys, a.id) }
    }
    Await.result(Future.sequence(queryFutures), 10.seconds)
    close(actors*)
    close(sys)
  }

  test("Concurrent spawning on the system registers all children") {
    val sys = newSystem[String]("sys")
    
    val numThreads = 10
    val spawnsPerThread = 5
    val expected = numThreads * spawnsPerThread
    val children = TrieMap.empty[String, ActorRef[String]]

    val futures: Seq[Future[Unit]] = (0 until numThreads).map { _ =>
      Future {
        (0 until spawnsPerThread).foreach { _ =>
          resultCF(sys ? Spawn()) match {
            case NewChild(c) => children.put(c.id, c.asInstanceOf[ActorRef[String]])
            case other       => throw new AssertionError(s"Unexpected response: $other")
          }
        }
      }
    }
    Await.result(Future.sequence(futures), 20.seconds)

    assertEquals(children.size, expected)
    children.keys.foreach { id => awaitRef(sys, id) }
    children.values.foreach(close)
    close(sys)
  }

  // ============================================================================
  // 10. Path-based sends to not-yet-registered actors (Issue #3)
  // ============================================================================

  test("Path-based bang to not-yet-registered actor should be queued and delivered") {
    val sys = newSystem[String]("sys")
    val received = SourceSignal(0)
    
    // Create a child with a behavior that updates received signal
    val child = newActor(sys, "test-child", { case (msg, _) => 
      received.mutate(_ + 1); Some(s"Child: $msg") 
    })
    
    // The child is not yet registered in the system
    
    // Send a message via path to the child when it's NOT registered
    sys.tell("42", ActorPath.Remote(sys.id, "test-child"), "")
    
    // Now register the child manually
    awaitCF(sys ? Register(child))
    
    // Wait to see if the message is processed
    Thread.sleep(200)
    
    // After the fix: message should be queued and delivered (received count = 1)
    // Before the fix: message was silently dropped (received count = 0)
    assertEquals(received.currentValue, Some(1), "After fix: message to not-yet-registered actor should be queued and delivered")
    close(child, sys)
  }

  // ============================================================================
  // 11. Cross-system delivery of system messages
  // ============================================================================

  test("cross-system tell of Pause via a Remote path pauses the peer's actor") {
    val (a, b) = crossRegistered()
    val received = SourceSignal(0)
    val onB = newActor(b, "onB", { case (msg, _) => received.mutate(_ + 1); Some(s"B: $msg") })

    val ref = awaitRef(b, "onB")
    ref ! "1"
    assert(waitFor(received, 1), "warm-up message was not delivered")

    a.toRemote ! (Pause, ActorPath.Remote("B", "onB"))
    assert(waitFor(onB.isPausedSignal, true), "the peer's actor was not paused by a cross-system Pause")
    ref ! "2"
    Thread.sleep(300)
    assertEquals(received.currentValue, Some(1), "the paused actor processed a message")

    a.toRemote ! (Unpause, ActorPath.Remote("B", "onB"))
    assert(waitFor(onB.isPausedSignal, false), "the peer's actor was not unpaused by a cross-system Unpause")
    assert(waitFor(received, 2), "the message sent while paused was not processed after Unpause")

    close(onB, a, b)
  }

  test("cross-system ask of a SystemMsg via a Remote path returns Done") {
    val (a, b) = crossRegistered()
    val onB    = newActor(b, "onB", { case (msg, _) => Some(s"B: $msg") })
    awaitRef(b, "onB")
    expectSuccessCF(a.ask(Pause, ActorPath.Remote("B", "onB")), Done)
    assert(waitFor(onB.isPausedSignal, true), "the peer's actor was not paused by a cross-system ask")
    expectSuccessCF(a.ask(Unpause, ActorPath.Remote("B", "onB")), Done)
    assert(waitFor(onB.isPausedSignal, false), "the peer's actor was not unpaused by a cross-system ask")
    close(onB, a, b)
  }

  test("a RemoteActorRef forwards system messages") {
    val (a, b) = crossRegistered()
    val onB = newActor(b, "onB", { case (msg, _) => Some(s"B: $msg") })

    awaitRef(b, "onB")
    val ref = remoteRef(a, "onB", "B")
    assert(!ref.isLocal)
    ref ! Pause
    assert(waitFor(onB.isPausedSignal, true), "the peer's actor was not paused through the RemoteActorRef")
    expectSuccessCF(ref ? Unpause, Done)
    assert(waitFor(onB.isPausedSignal, false), "the peer's actor was not unpaused through the RemoteActorRef")
    expectSuccessCF(ref ? "7", "B: 7")

    close(onB, a, b)
  }

  test("cross-system Close via a RemoteActorRef closes the peer's actor and deregisters it") {
    val (a, b) = crossRegistered()
    val onB    = newActor(b, "onB", { case (msg, _) => Some(s"B: $msg") })

    awaitRef(b, "onB")
    val ref = remoteRef(a, "onB", "B")
    expectSuccessCF(ref ? Close, Done)
    assert(waitFor(onB.isClosedSignal, true), "the peer's actor was not closed through the RemoteActorRef")
    awaitInvalid(b, "onB")

    close(onB, a, b)
  }

  test("cross-system Close via a path closes the peer's actor") {
    val (a, b) = crossRegistered()
    val onB = newActor(b, "onB", { case (msg, _) => Some(s"B: $msg") })

    awaitRef(b, "onB")
    a.tell(Close, ActorPath.Remote("B", "onB"))
    assert(waitFor(onB.isClosedSignal, true), "the peer's actor was not closed by a path-based Close")
    awaitInvalid(b, "onB")

    close(onB, a, b)
  }

  test("cross-system AddBehavior and RemoveBehavior reach the peer's actor") {
    val (a, b) = crossRegistered()
    val onB    = newActor(b, "onB", { case (msg, _) if msg.startsWith("d:") => Some(s"D: $msg") })
    val extra: PF[String, Int] = { case (msg, _) if msg.startsWith("e:") => Some(s"E: $msg") }

    awaitRef(b, "onB")
    val ref = remoteRef(a, "onB", "B")
    assert(tryResultCF(ref ? "e:1").isFailure, "the default behavior should not handle 'e:' messages")

    expectSuccessCF(a.ask(AddBehavior[String, Int]("extra" -> extra), ActorPath.Remote("B", "onB")), Done)
    expectSuccessCF(ref ? "e:1", "E: e:1")

    expectSuccessCF(a.ask(RemoveBehavior("extra"), ActorPath.Remote("B", "onB")), Done)
    assert(tryResultCF(ref ? "e:2").isFailure, "the removed behavior still handled the message")

    close(onB, a, b)
  }

  test("cross-system AskForRefAsync for an actor missing on the peer fails with InvalidIdException") {
    val (a, b) = crossRegistered()
    val capturer = new CapturingActor(a)
    capturer.initialize()
    expectFailureCF(a ? AskForRefAsync(capturer, "nonexistent", "B"), InvalidIdException("nonexistent"))
    Thread.sleep(100)
    assert(capturer.receivedRef.isEmpty, "the sender actor received a Ref for a nonexistent actor")
    close(capturer, a, b)
  }

  // ============================================================================
  // 12. System messages routed to the system itself
  // ============================================================================

  test("SystemMsg via self-addressed path forms reaches the system itself") {
    val sys = newSystem[String]("sys")

    List(ActorPath.Direct, ActorPath.Local("sys"), ActorPath.Remote("", "sys"), ActorPath.Remote("sys", "sys")).foreach { path =>
      expectSuccessCF(sys.ask(Pause, path), Done)
      assert(waitFor(sys.isPausedSignal, true), s"the system was not paused via $path")
      expectSuccessCF(sys.ask(Unpause, path), Done)
      assert(waitFor(sys.isPausedSignal, false), s"the system was not unpaused via $path")
    }

    sys.tell(Pause, ActorPath.Direct)
    assert(waitFor(sys.isPausedSignal, true), "the system was not paused by a bang via Direct")
    sys.tell(Unpause, ActorPath.Local("sys"))
    assert(waitFor(sys.isPausedSignal, false), "the system was not unpaused by a bang via Local")

    close(sys)
  }

  test("SystemMsg ask via a Remote path naming the own system and a registered actor is delivered by the actor") {
    val sys = newSystem[String]("sys")
    val act = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })

    awaitRef(sys, "a")
    expectSuccessCF(sys.ask(Pause, ActorPath.Remote("sys", "a")), Done)
    assert(waitFor(act.isPausedSignal, true), "the actor was not paused by an ask through a Remote path")
    assert(!sys.isPaused, "the system itself was paused")
    expectSuccessCF(sys.ask(Unpause, ActorPath.Remote("sys", "a")), Done)
    assert(waitFor(act.isPausedSignal, false), "the actor was not unpaused by an ask through a Remote path")

    close(act, sys)
  }

  test("a SystemMsg bang to a not-yet-registered local actor is requeued and delivered") {
    val sys = newSystem[String]("sys")
    val received = SourceSignal(0)

    // the actor does not exist yet: the system message must be requeued (SysRequeue), not dropped
    sys.tell(Pause, ActorPath.Remote("sys", "late"))
    val late = newActor(sys, "late", { case (msg, _) => received.mutate(_ + 1); Some(s"L: $msg") })
    assert(waitFor(late.isPausedSignal, true), "the requeued Pause was not delivered to the late actor")

    val ref = awaitRef(sys, "late")
    ref ! "1"
    Thread.sleep(300)
    assertEquals(received.currentValue, Some(0), "the paused actor processed a message")

    expectSuccessCF(sys.ask(Unpause, ActorPath.Remote("sys", "late")), Done)
    assert(waitFor(received, 1), "the message sent while paused was not processed after Unpause")
    close(late, sys)
  }

  test("a SystemMsg bang to a never-registered actor is dropped after retries without affecting the system") {
    val sys = newSystem[String]("sys")

    sys.tell(Pause, ActorPath.Remote("sys", "missing"))
    Thread.sleep(500) // longer than the ~3-heartbeat requeue window (defBeat = 100 ms)
    assert(!sys.isPaused, "the requeued message affected the system itself")

    // the system remains fully functional
    val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    expectSuccessCF(awaitRef(sys, "a") ? "1", "A: 1")
    close(a, sys)
  }

  // ============================================================================
  // 13. Failure policy for the SystemMsg path-based API
  // ============================================================================

  test("SystemMsg tell to an unknown system id is silently dropped") {
    val sys = newSystem[String]("sys")

    sys.tell(Pause, ActorPath.Remote("UNKNOWN", "x"))
    Thread.sleep(200)
    assert(!sys.isPaused, "the system itself was paused by a message to an unknown system")
    val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    expectSuccessCF(awaitRef(sys, "a") ? "1", "A: 1")
    close(a, sys)
  }

  test("SystemMsg ask to an unknown system id fails with InvalidSystemIdException") {
    val sys = newSystem[String]("sys")
    expectFailureCF(sys.ask(Pause, ActorPath.Remote("UNKNOWN", "x")), InvalidIdException("UNKNOWN"))
    close(sys)
  }

  test("SystemMsg ask to a missing actor on a known peer falls through to the peer's own stream") {
    // documents the current policy: a missing actor id on the peer makes the ask land in the peer's
    // system stream, so the operation is applied to the peer's system actor itself
    val (a, b) = crossRegistered()
    expectSuccessCF(a.ask(Pause, ActorPath.Remote("B", "missing")), Done)
    assert(waitFor(b.isPausedSignal, true), "the peer's system was not paused by the fall-through ask")
    expectSuccessCF(a.ask(Unpause, ActorPath.Remote("B", "missing")), Done)
    assert(waitFor(b.isPausedSignal, false), "the peer's system was not unpaused by the fall-through ask")

    close(a, b)
  }

  // ============================================================================
  // 14. Systems-map lifecycle
  // ============================================================================

  test("RegisterSystem with a duplicate id returns InvalidId and keeps the original mapping") {
    val (a, b) = crossRegistered()
    val onA = newActor(a, "onA", { case (msg, _) => Some(s"A: $msg") })
    expectFailureCF(a ? RegisterSystem(b.toRemote), InvalidIdException(b.id))
    awaitRef(a, "onA")
    resultCF(b ? AskForRef("onA", "A")) match {
      case Ref(ref) => expectSuccessCF(ref.asInstanceOf[ActorRef[String]] ? "1", "A: 1")
      case other    => fail(s"the original peer mapping was lost: $other")
    }
    close(onA, a, b)
  }

  test("SystemClosed via ask unregisters the peer; unknown ids complete with InvalidId") {
    val (a, b) = crossRegistered()
    val onA = newActor(a, "onA", { case (msg, _) => Some(s"A: $msg") })

    awaitRef(a, "onA")
    resultCF(b ? AskForRef("onA", "A")) match {
      case Ref(_) =>
      case other  => fail(s"Unexpected AskForRef response: $other")
    }

    expectSuccessCF(b ? SystemClosed("A"), Done)
    expectFailureCF(b ? AskForRef("onA", "A"), InvalidIdException("A"))

    // unknown ids must not hang the ask - they complete with InvalidId
    expectFailureCF(b ? UnregisterSystem("ghost"), InvalidIdException("ghost"))
    expectFailureCF(b ? SystemClosed("ghost"), InvalidIdException("ghost"))

    close(onA, a, b)
  }

  test("Unregister removes a registered actor and returns InvalidId for unknown ids") {
    val sys = newSystem[String]("sys")
    val act = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    awaitRef(sys, "a")
    expectSuccessCF(sys ? Unregister("a"), Done)
    awaitInvalid(sys, "a")
    expectFailureCF(sys ? Unregister("nope"), InvalidIdException("nope"))
    close(act, sys)
  }

  test("tell is a no-op and ask fails on a closed system") {
    val sys = newSystem[String]("sys")
    close(sys)
    sys.tell(Pause, ActorPath.Direct)      // must not throw
    sys.tell("42", ActorPath.Direct, "")   // must not throw
    expectFailureCF(sys.ask(Pause, ActorPath.Direct), Actor.actorIsClosed)
    expectFailureCF(sys.ask("42", ActorPath.Remote("UNKNOWN", "x"), ""), Actor.actorIsClosed)
  }

  // ============================================================================
  // 15. Concurrency: cross-system system messages
  // ============================================================================

  test("concurrent cross-system system messages are safe") {
    val (a, b) = crossRegistered()
    val numActors = 10
    val numThreads = 8
    val opsPerThread = 5
    val actors = (0 until numActors).map(i => newActor(b, s"m$i", { case (msg, _) => Some(s"m$i: $msg") }))

    actors.foreach(act => awaitRef(b, act.id))
    val futures = (0 until numThreads).map { t =>
      Future {
        (0 until opsPerThread).foreach { i =>
          val n = (t * opsPerThread + i) % numActors
          val id = s"m$n"
          val ref = tryResultCF(a ? AskForRef(id, "B"))(using 20.seconds) match {
            case Success(Ref(ref)) => ref.asInstanceOf[ActorRef[String]]
            case other => throw new AssertionError(s"Unexpected AskForRef response for $id: $other")
          }
          if ((t + i) % 2 == 0) {
            // an asked system message through the RemoteActorRef, synchronized by its response;
            // then a system message banged through the path - Unpause is idempotent, so a reordering
            // of the concurrent bangs can never leave the actor paused
            assertEquals(resultCF(ref ? Pause), Done)
            a.tell(Unpause, ActorPath.Remote("B", id))
          } else {
            // asked system messages through the path
            assertEquals(resultCF(a.ask(Pause, ActorPath.Remote("B", id))), Done)
            assertEquals(resultCF(a.ask(Unpause, ActorPath.Remote("B", id))), Done)
          }
          assertEquals(resultCF(ref ? "ping"), s"m$n: ping")
        }
      }
    }
    Await.result(Future.sequence(futures), 60.seconds)

    // after the storm all actors are alive, unpaused, and responsive
    actors.foreach { act =>
      val ref = awaitRef(b, act.id)
      assertEquals(resultCF(ref ? Unpause), Done)
      assert(!act.isPaused, s"actor ${act.id} is paused after the storm")
      assertEquals(resultCF(ref ? "ping"), s"${act.id}: ping")
    }

    close(actors*)
    close(a, b)
  }

  // ============================================================================
  // 16. Registering with tags
  // ============================================================================

  test("Register with tags makes the actor findable by a tag query") {
    val sys = newSystem[String]("sys")
    val a = newTaggedActor(sys, "a", ActorTag.Log, ActorTag.Stats)
    val byLog = refByTags(sys, ActorTag.Log)
    assertEquals(byLog.id, "a")
    assertEquals(byLog.path, ActorPath.Local("a"))
    assertEquals(refByTags(sys, ActorTag.Log, ActorTag.Stats).id, "a")
    expectSuccessCF(byLog ? "ping", "a: ping")
    close(a, sys)
  }

  test("Register with a duplicate id returns InvalidId and keeps the original entry with its tags") {
    val sys = newSystem[String]("sys")
    val a   = newTaggedActor(sys, "dup", ActorTag.Log)
    val b   = create[String]("dup") { case (msg, _) => Some(s"B: $msg") }
    val tags: Set[ActorTag] = Set(ActorTag.Stats)
    expectFailureCF(sys ? Register(b, tags), InvalidIdException("dup"))
    assertEquals(refByTags(sys, ActorTag.Log).id, "dup")
    expectFailureCF(sys ? AskForRef(tags = tags), NoRefFound(tags))
    close(a, b, sys)
  }

  test("user-defined tags can be used to register and find actors") {
    val sys = newSystem[String]("sys")
    val a = newTaggedActor(sys, "a", Custom)
    assertEquals(refByTags(sys, Custom).id, "a")
    expectFailureCF(sys ? AskForRef(tags = Set(ActorTag.Log)), NoRefFound(Set(ActorTag.Log)))
    close(a, sys)
  }

  // ============================================================================
  // 17. AskForRef by tags
  // ============================================================================

  test("a tag query matches only if all tags in the query are registered") {
    val sys = newSystem[String]("sys")
    val a = newTaggedActor(sys, "a", ActorTag.Log, ActorTag.Stats)
    val b = newTaggedActor(sys, "b", ActorTag.Log)
    assertEquals(refByTags(sys, ActorTag.Log, ActorTag.Stats).id, "a")
    // a subset query matches both actors, and collectFirst over a HashMap has no defined order
    assert(Set("a", "b").contains(refByTags(sys, ActorTag.Log).id),
      "the subset tag query returned an unexpected actor")
    expectFailureCF(sys ? AskForRef(tags = Set(ActorTag.Log, ActorTag.Stats, Custom)),
      NoRefFound(Set(ActorTag.Log, ActorTag.Stats, Custom)))
    close(a, b, sys)
  }

  test("a tag query matching several actors returns one of them") {
    val sys = newSystem[String]("sys")
    val a1 = newTaggedActor(sys, "a1", ActorTag.Log)
    val a2 = newTaggedActor(sys, "a2", ActorTag.Log)
    val id = refByTags(sys, ActorTag.Log).id
    assert(Set("a1", "a2").contains(id), s"the tag query returned an unexpected actor: $id")
    close(a1, a2, sys)
  }

  test("a tag query with no matching actor fails with NoRefFound") {
    val sys = newSystem[String]("sys")
    // auto-registration on the system uses an empty tag set
    val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    awaitRef(sys, "a")
    expectFailureCF(sys ? AskForRef(tags = Set(ActorTag.Log)), NoRefFound(Set(ActorTag.Log)))
    close(a, sys)
  }

  test("a tag query with the System tag finds the system itself registered as an actor") {
    val sys = newSystem[String]("sys")
    val ref = refByTags(sys, ActorTag.System)
    assertEquals(ref.id, "sys")
    assert(ref.isLocal)
    expectSuccessCF(ref ? Pause, Done)
    assert(waitFor(sys.isPausedSignal, true), "the system was not paused through the ref found by tags")
    expectSuccessCF(ref ? Unpause, Done)
    close(sys)
  }

  test("a tag query with the system's own id behaves as a local query") {
    val sys = newSystem[String]("sys")
    val a = newTaggedActor(sys, "a", ActorTag.Log)
    resultCF(sys ? AskForRef("", "sys", Set(ActorTag.Log))) match {
      case Ref(ref) => assertEquals(ref.id, "a")
      case other   => fail(s"Expected Ref, got $other")
    }
    close(a, sys)
  }

  // ============================================================================
  // 18. AskForRefAsync with tags
  // ============================================================================

  test("AskForRefAsync with tags delivers the Ref to the sender and returns Done") {
    val sys = newSystem[String]("sys")
    val a = newTaggedActor(sys, "a", ActorTag.Log)
    val capturer = new CapturingActor(sys)
    capturer.initialize()
    expectSuccessCF(sys ? AskForRefAsync(capturer, tags = Set(ActorTag.Log)), Done)
    val ref = awaitCaptured(capturer)
    assertEquals(ref.id, "a")
    expectSuccessCF(ref ? "ping", "a: ping")
    close(capturer, a, sys)
  }

  test("AskForRefAsync with tags and no match fails with NoRefFound and sends nothing to the sender") {
    val sys = newSystem[String]("sys")
    val capturer = new CapturingActor(sys)
    capturer.initialize()
    expectFailureCF(sys ? AskForRefAsync(capturer, tags = Set(ActorTag.Log)), NoRefFound(Set(ActorTag.Log)))
    Thread.sleep(200)
    assert(capturer.receivedCount == 0, "the sender actor received a Ref for a query with no match")
    close(capturer, sys)
  }

  test("AskForRefAsync for a remote actor delivers a RemoteActorRef to the sender") {
    val (a, b) = crossRegistered()
    val onB = newActor(b, "onB", { case (msg, _) => Some(s"B: $msg") })
    val capturer = new CapturingActor(a)
    capturer.initialize()
    awaitRef(b, "onB")
    expectSuccessCF(a ? AskForRefAsync(capturer, "onB", "B"), Done)
    val ref = awaitCaptured(capturer)
    assert(!ref.isLocal, "the sender received a local ref to a remote actor")
    assertEquals(ref.path, ActorPath.Remote("B", "onB"))
    expectSuccessCF(ref ? "7", "B: 7")
    close(capturer, onB, a, b)
  }

  // ============================================================================
  // 19. AskForSystem / AskForSystemAsync
  // ============================================================================

  test("AskForSystem with an empty id or the system's own id returns the system itself as a usable ref") {
    val sys = newSystem[String]("sys")
    List(AskForSystem(), AskForSystem("sys")).foreach { q =>
      val ref = systemRef(sys, q)
      assert(ref.isLocal)
      assertEquals(ref.id, "sys")
      expectSuccessCF(ref ? Pause, Done)
      assert(waitFor(sys.isPausedSignal, true), s"the system was not paused through the ref from $q")
      expectSuccessCF(ref ? Unpause, Done)
      assert(waitFor(sys.isPausedSignal, false), s"the system was not unpaused through the ref from $q")
    }
    close(sys)
  }

  test("AskForSystem for a registered remote system returns a usable RemoteActorRef") {
    val (a, b) = crossRegistered()
    val ref = systemRef(a, AskForSystem("B"))
    assert(!ref.isLocal)
    assertEquals(ref.path, ActorPath.Remote("B", "B"))
    expectSuccessCF(ref ? Pause, Done)
    assert(waitFor(b.isPausedSignal, true), "the peer's system was not paused through the ref")
    assert(!a.isPaused, "the asking system was paused instead of the peer")
    expectSuccessCF(ref ? Unpause, Done)
    assert(waitFor(b.isPausedSignal, false), "the peer's system was not unpaused through the ref")
    close(a, b)
  }

  test("AskForSystem for an unknown system id fails with InvalidSystemIdException") {
    val sys = newSystem[String]("sys")
    expectFailureCF(sys ? AskForSystem("UNKNOWN"), InvalidIdException("UNKNOWN"))
    close(sys)
  }

  test("AskForSystem by tags returns the tagged system") {
    val a = newSystem[String]("A")
    val b = newSystem("B")
    awaitCF(a ? RegisterSystem(b.toRemote, Set(ActorTag.Stats)))
    val ref = systemRef(a, AskForSystem(tags = Set(ActorTag.Stats)))
    assert(!ref.isLocal)
    assertEquals(ref.id, "B")
    assertEquals(ref.path, ActorPath.Remote("B", "B"))
    expectSuccessCF(ref ? Pause, Done)
    assert(waitFor(b.isPausedSignal, true), "the tagged system was not paused through the ref")
    expectSuccessCF(ref ? Unpause, Done)
    assert(waitFor(b.isPausedSignal, false), "the tagged system was not unpaused through the ref")
    // the system registers itself in the systems map with the System tag
    assertEquals(systemRef(a, AskForSystem(tags = Set(ActorTag.System))).id, "A")
    close(a, b)
  }

  test("AskForSystem by tags with no match fails with NoRefFound") {
    val sys = newSystem[String]("sys")
    expectFailureCF(sys ? AskForSystem(tags = Set(ActorTag.Log)), NoRefFound(Set(ActorTag.Log)))
    close(sys)
  }

  test("AskForSystemAsync with an empty id delivers a ref to the system itself to the sender") {
    val sys      = newSystem[String]("sys")
    val capturer = new CapturingActor(sys)
    capturer.initialize()
    expectSuccessCF(sys ? AskForSystemAsync(capturer), Done)
    val ref = awaitCaptured(capturer)
    assert(ref.isLocal)
    assertEquals(ref.id, "sys")
    expectSuccessCF(ref ? Pause, Done)
    assert(waitFor(sys.isPausedSignal, true), "the system was not paused through the delivered ref")
    expectSuccessCF(ref ? Unpause, Done)
    close(capturer, sys)
  }

  test("AskForSystemAsync for a registered remote system delivers a RemoteActorRef to the sender") {
    val (a, b) = crossRegistered()
    val capturer = new CapturingActor(a)
    capturer.initialize()
    expectSuccessCF(a ? AskForSystemAsync(capturer, "B"), Done)
    val ref = awaitCaptured(capturer)
    assert(!ref.isLocal)
    assertEquals(ref.path, ActorPath.Remote("B", "B"))
    expectSuccessCF(ref ? Pause, Done)
    assert(waitFor(b.isPausedSignal, true), "the peer's system was not paused through the delivered ref")
    expectSuccessCF(ref ? Unpause, Done)
    close(capturer, a, b)
  }

  test("AskForSystemAsync for an unknown system id fails with InvalidSystemIdException and sends nothing to the sender") {
    val sys      = newSystem[String]("sys")
    val capturer = new CapturingActor(sys)
    capturer.initialize()
    expectFailureCF(sys ? AskForSystemAsync(capturer, "UNKNOWN"), InvalidIdException("UNKNOWN"))
    Thread.sleep(100)
    assert(capturer.receivedCount == 0, "the sender actor received a Ref for an unknown system")
    close(capturer, sys)
  }

  test("AskForSystemAsync by tags delivers the tagged system's ref to the sender") {
    val a        = newSystem("A")
    val b        = newSystem("B")
    val capturer = new CapturingActor(a)
    capturer.initialize()

    awaitCF(a ? RegisterSystem(b.toRemote, Set(ActorTag.Stats)))
    expectSuccessCF(a ? AskForSystemAsync(capturer, tags = Set(ActorTag.Stats)), Done)
    val ref = awaitCaptured(capturer)
    assert(!ref.isLocal)
    assertEquals(ref.id, "B")
    assertEquals(ref.path, ActorPath.Remote("B", "B"))
    expectSuccessCF(ref ? Pause, Done)
    assert(waitFor(b.isPausedSignal, true), "the tagged system was not paused through the delivered ref")
    expectSuccessCF(ref ? Unpause, Done)

    close(capturer, a, b)
  }

  test("AskForSystemAsync by tags with no match fails with NoRefFound and sends nothing to the sender") {
    val sys      = newSystem[String]("sys")
    val capturer = new CapturingActor(sys)
    capturer.initialize()
    expectFailureCF(sys ? AskForSystemAsync(capturer, tags = Set(ActorTag.Log)), NoRefFound(Set(ActorTag.Log)))
    Thread.sleep(100)
    assert(capturer.receivedCount == 0, "the sender actor received a Ref for a tag query with no match")
    close(capturer, sys)
  }

  // ============================================================================
  // 20. Tag registry lifecycle and concurrency
  // ============================================================================

  test("a tag query returns NoRefFound after the tagged actor is unregistered") {
    val sys = newSystem[String]("sys")
    val a   = newTaggedActor(sys, "a", ActorTag.Log)
    assertEquals(refByTags(sys, ActorTag.Log).id, "a")
    expectSuccessCF(sys ? Unregister("a"), Done)
    expectFailureCF(sys ? AskForRef(tags = Set(ActorTag.Log)), NoRefFound(Set(ActorTag.Log)))
    close(a, sys)
  }

  test("a tag query over systems returns NoRefFound after the peer is unregistered") {
    val a = newSystem[String]("A")
    val b = newSystem[String]("B")
    val tags: Set[ActorTag] = Set(ActorTag.Stats)
    awaitCF(a ? RegisterSystem(b.toRemote, tags))
    assertEquals(systemRef(a, AskForSystem(tags = tags)).id, "B")
    expectSuccessCF(a ? UnregisterSystem("B"), Done)
    expectFailureCF(a ? AskForSystem(tags = tags), NoRefFound(tags))
    close(a, b)
  }

  test("concurrent tagged registrations and tag queries are safe") {
    val sys = newSystem[String]("sys")
    val numThreads = 8
    val perThread = 5
    val queriesPerThread = 10
    val actors = TrieMap.empty[String, Actor[String, Int] & Closeable]

    val futures: Seq[Future[Unit]] = (0 until numThreads).map { t =>
      Future {
        val tag = if (t % 2 == 0) ActorTag.Log else ActorTag.Stats
        (0 until perThread).foreach { i =>
          val id = s"${tag.name}$t-$i"
          val a = create[String, Int](id, 0, { case (msg, _) => Some(s"$id: $msg") })
          actors.put(id, a)
          awaitCF(sys ? Register(a, Set(tag)))
        }
        (0 until queriesPerThread).foreach { _ =>
          // the own tag always matches - this thread's own registrations are already done
          resultCF(sys ? AskForRef(tags = Set(tag))) match {
            case Ref(ref) => assert(ref.id.startsWith(tag.name), s"the tag query returned an unexpected actor: ${ref.id}")
            case other    => throw new AssertionError(s"the tag query failed: $other")
          }
          // no actor is ever registered with both tags
          tryResultCF(sys ? AskForRef(tags = Set(ActorTag.Log, ActorTag.Stats))) match {
            case Failure(NoRefFound(_)) =>
            case other => throw new AssertionError(s"no actor has both tags, but the query returned: $other")
          }
        }
      }
    }

    Await.result(Future.sequence(futures), 30.seconds)
    close(actors.values.toArray*)
    close(sys)
  }

  // ============================================================================
  // 21. Tags on actors created with ActorBuilder
  // ============================================================================

  test("withTags on the builder is used when the actor auto-registers on its system") {
    val sys = newSystem[String]("sys")
    val a = ActorBuilder[String, Int](0)
      .withId("built")
      .withBehavior("default", { case (msg, _) => Some(s"built: $msg") })
      .withTags(Set(ActorTag.Log))
      .withSystem(sys)
      .build()

    val ref = awaitRefByTags(sys, ActorTag.Log)
    assertEquals(ref.id, "built")
    assertEquals(resultCF(ref ? "1"), "built: 1")

    close(a, sys)
  }

  // ============================================================================
  // 22. Tags of actor systems
  // ============================================================================

  test("a system with custom tags is discoverable by them and still by the System tag") {
    val sys = ActorSystem[String, Int]("custom", 0, Actor.defBeat, Set(ActorTag.Log, ActorTag.Stats))
    // the system registers itself in its own actorRefs with its custom tags
    assertEquals(refByTags(sys, ActorTag.Log, ActorTag.Stats).id, "custom")
    // the constructor always unions the System tag with the user-provided tags
    assertEquals(refByTags(sys, ActorTag.System).id, "custom")
    assertEquals(refByTags(sys, ActorTag.System, ActorTag.Log).id, "custom")
    // and in its own systems map
    assertEquals(systemRef(sys, AskForSystem(tags = Set(ActorTag.Stats))).id, "custom")
    assertEquals(systemRef(sys, AskForSystem(tags = Set(ActorTag.System, ActorTag.Log))).id, "custom")
    close(sys)
  }

  // ============================================================================
  // 23. Tags inherited by spawned actors
  // ============================================================================

  test("children spawned by a tagged actor inherit its tags plus the Child tag") {
    val sys    = newSystem[String]("sys")
    val parent = ActorBuilder[String, Int](0)
      .withId("parent")
      .withBehavior("default", { case (msg, _) => Some(s"parent: $msg") })
      .withTags(Set(ActorTag.Log))
      .withSystem(sys)
      .build()

    val child = spawn(parent)(SystemMsg.Spawn())
    // the parent has Log but not Child, so {Log, Child} uniquely identifies the child
    assertEquals(awaitRefByTags(sys, ActorTag.Log, ActorTag.Child).id, child.id)
    assertEquals(awaitRefByTags(sys, ActorTag.Child).id, child.id)
    // a plain Log query matches both the parent and the child
    val logId = awaitRefByTags(sys, ActorTag.Log).id
    assert(Set("parent", child.id).contains(logId), s"the Log query returned an unexpected actor: $logId")

    // the grandchild inherits the same set - Child is not duplicated
    val grandchild = spawn(child)(SystemMsg.Spawn())
    val gcId       = awaitRefByTags(sys, ActorTag.Log, ActorTag.Child).id
    assert(Set(child.id, grandchild.id).contains(gcId), s"the {Log, Child} query returned an unexpected actor: $gcId")

    close(parent); close(sys)
  }

  test("children spawned by the system inherit its tags plus the Child tag") {
    val sys   = ActorSystem[String, Int]("sys", 0, Actor.defBeat, Set(ActorTag.Stats))
    val child = spawn(sys)(SystemMsg.Spawn())
    // the system's member tags are {Stats, System} (System is unioned in), so the child gets {Stats, System, Child}
    assertEquals(awaitRefByTags(sys, ActorTag.Stats, ActorTag.Child).id, child.id)
    assertEquals(awaitRefByTags(sys, ActorTag.Child).id, child.id)
    // consequence of the symmetric inheritance: a System-tag query matches both the system and its children
    val sysId = awaitRefByTags(sys, ActorTag.System).id
    assert(Set(sys.id, child.id).contains(sysId), s"the System query returned an unexpected actor: $sysId")

    close(sys)
  }
}
