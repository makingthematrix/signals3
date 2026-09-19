package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.testutils.*
import io.github.makingthematrix.signals3.*
import munit.FunSuite

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}

/**
 * Unit tests for ActorSystem functionality: registration, lookup, deregistration,
 * spawn integration, cross-actor communication through the registry, and concurrency.
 */
class ActorSystemSpec extends FunSuite {
  private val eventContext = EventContext()
  import Threading.defaultContext

  given Timeout: FiniteDuration = 5.seconds

  override def beforeEach(context: BeforeEach): Unit =
    eventContext.start()

  override def afterEach(context: AfterEach): Unit =
    eventContext.stop()

  // ============================================================================
  // Helpers
  // ============================================================================

  private def newSystem(): ActorSystem[Int, String, Int] =
    ActorSystem[Int, String, Int]("sys", 0, Actor.defBeat)

  private def newActor(sys: ActorSystem[Int, String, Int], id: String,
                       pf: Actor.PF[Int, String, Int]): Actor[Int, String, Int] =
    ActorBuilder[Int, String, Int]()
      .withId(id).withState(0).withBehavior("default", pf).withSystem(sys).build()

  private def close(actor: Actor[?, ?, ?]): Unit = {
    actor.asInstanceOf[Closeable].close()
    waitFor(actor.isClosedSignal, true)
  }

  private def spawn(parent: Actor[Int, String, Int])(data: parent.SystemMsg.Spawn): Actor[Int, String, Int] = {
    import parent.SystemMsg
    val rsp = Await.result(parent ? data, 5.seconds)
    rsp match {
      case SystemMsg.NewChild(child) => child
      case SystemMsg.InvalidId        => throw new AssertionError(s"Spawn with id '${data.actorId}' was rejected as InvalidId")
      case other                      => throw new AssertionError(s"Unexpected spawn response: $other")
    }
  }

  private def awaitRef(sys: ActorSystem[Int, String, Int], id: String): ActorRef[Int, String] = {
    import sys.SystemMsg.*
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < 5000) {
      try {
        Await.result(sys ? AskForLocalRef(id), 1.second) match {
          case Ref(ref) => return ref
          case InvalidId => Thread.sleep(50)
          case other => throw new AssertionError(s"Unexpected response: $other")
        }
      } catch {
        case _: TimeoutException => Thread.sleep(50)
      }
    }
    fail(s"Actor '$id' was not registered within 5 seconds")
  }

  private def awaitInvalid(sys: ActorSystem[Int, String, Int], id: String): Unit = {
    import sys.SystemMsg.*
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < 5000) {
      try {
        Await.result(sys ? AskForLocalRef(id), 1.second) match {
          case InvalidId => return
          case Ref(_) => Thread.sleep(50)
          case other => throw new AssertionError(s"Unexpected response: $other")
        }
      } catch {
        case _: TimeoutException => Thread.sleep(50)
      }
    }
    fail(s"Actor '$id' was still registered after 5 seconds")
  }

  // ============================================================================
  // CapturingActor for AskForRefAsync tests
  // ============================================================================

  private class CapturingActor(sys: ActorSystem[Int, String, Int])(using ec: ExecutionContext)
    extends ActorImpl[Int, String, Int]("capturing", 0, Actor.defBeat, None, Some(sys)) {
    import SystemMsg.*

    val receivedName = SourceSignal("")
    @volatile var receivedRef: Option[ActorRef[Int, String]] = None
    val receivedInvalid = SourceSignal(false)

    override protected def processSysEntry(msg: SysEntry): Unit = msg match {
      case (Ref(ref), p) =>
        receivedRef = Some(ref)
        receivedName ! ref.path.actorId
        respond(p, Done)
      case (InvalidId, p) =>
        receivedInvalid ! true
        respond(p, Done)
      case other =>
        super.processSysEntry(other)
    }
  }

  private def newCapturer(sys: ActorSystem[Int, String, Int]): CapturingActor = {
    val c = CapturingActor(sys)
    c.initialize()
    c
  }

  private def awaitCapturedRef(capturer: CapturingActor): ActorRef[Int, String] = {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < 5000) {
      capturer.receivedRef match {
        case Some(ref) => return ref
        case None => Thread.sleep(50)
      }
    }
    fail("CapturingActor did not receive a Ref within 5 seconds")
  }

  // ============================================================================
  // 1. Construction and initialization
  // ============================================================================

  test("ActorSystem.apply is initialized and has the given id") {
    val sys = newSystem()
    assert(sys.isInitialized)
    assertEquals(sys.id, "sys")
    assert(!sys.isClosed)
    close(sys)
  }

  // ============================================================================
  // 2. Register
  // ============================================================================

  test("Actor built with withSystem auto-registers on initialize") {
    val sys = newSystem()
    val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    val ref = awaitRef(sys, "a")
    assertEquals(ref.path.actorId, "a")
    close(a)
    close(sys)
  }

  test("Register via ? stores the actor and returns Ref") {
    val sys = newSystem()
    import sys.SystemMsg.*
    val a = ActorBuilder[Int, String, Int]()
      .withId("manual").withState(0)
      .withBehavior("default", { case (msg, _) => Some(s"M: $msg") })
      .build()
    val rsp = resultCF(sys ? Register(a))
    rsp match {
      case Ref(ref) => assertEquals(ref.path.actorId, "manual")
      case other => fail(s"Expected Ref, got $other")
    }
    close(a)
    close(sys)
  }

  test("Register with a duplicate id overwrites the previous entry") {
    val sys = newSystem()
    import sys.SystemMsg.*
    val a = ActorBuilder[Int, String, Int]()
      .withId("dup").withState(0)
      .withBehavior("default", { case (msg, _) => Some(s"A: $msg") })
      .build()
    val b = ActorBuilder[Int, String, Int]()
      .withId("dup").withState(0)
      .withBehavior("default", { case (msg, _) => Some(s"B: $msg") })
      .build()
    awaitCF(sys ? Register(a))
    awaitCF(sys ? Register(b))
    val ref = awaitRef(sys, "dup")
    assertEquals(resultCF(ref ? 1), "B: 1")
    close(a)
    close(b)
    close(sys)
  }

  test("Register multiple actors with distinct ids") {
    val sys = newSystem()
    val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    val b = newActor(sys, "b", { case (msg, _) => Some(s"B: $msg") })
    val c = newActor(sys, "c", { case (msg, _) => Some(s"C: $msg") })
    assertEquals(awaitRef(sys, "a").path.actorId, "a")
    assertEquals(awaitRef(sys, "b").path.actorId, "b")
    assertEquals(awaitRef(sys, "c").path.actorId, "c")
    close(a); close(b); close(c)
    close(sys)
  }

  // ============================================================================
  // 3. Spawn integration with the system registry
  // ============================================================================

  test("Spawned child auto-registers on the system") {
    val sys = newSystem()
    import sys.SystemMsg.*
    val child = spawn(sys)(Spawn(actorId = "c"))
    val ref = awaitRef(sys, "c")
    assertEquals(ref.path.actorId, "c")
    close(child)
    close(sys)
  }

  test("Grandchild auto-registers on the same system") {
    val sys = newSystem()
    val child = spawn(sys)(sys.SystemMsg.Spawn())
    val grandchild = spawn(child)(child.SystemMsg.Spawn())
    awaitRef(sys, grandchild.id)
    close(grandchild)
    close(child)
    close(sys)
  }

  test("Spawned child with auto-generated id is registered under that id") {
    val sys = newSystem()
    val child = spawn(sys)(sys.SystemMsg.Spawn())
    val ref = awaitRef(sys, child.id)
    assertEquals(ref.path.actorId, child.id)
    close(child)
    close(sys)
  }

  test("Spawned sibling children are all registered") {
    val sys = newSystem()
    import sys.SystemMsg.*
    val c1 = spawn(sys)(Spawn(actorId = "c1"))
    val c2 = spawn(sys)(Spawn(actorId = "c2"))
    val c3 = spawn(sys)(Spawn(actorId = "c3"))
    awaitRef(sys, "c1")
    awaitRef(sys, "c2")
    awaitRef(sys, "c3")
    close(c1); close(c2); close(c3)
    close(sys)
  }

  // ============================================================================
  // 4. AskForRef
  // ============================================================================

  test("AskForRef returns Ref for a registered id") {
    val sys = newSystem()
    val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    val ref = awaitRef(sys, "a")
    assert(ref.isLocal)
    assertEquals(ref.path.actorId, "a")
    close(a)
    close(sys)
  }

  test("AskForRef returns InvalidId for an unknown id") {
    val sys = newSystem()
    import sys.SystemMsg.*
    val rsp = resultCF(sys ? AskForLocalRef("nonexistent"))
    assertEquals(rsp, InvalidId)
    close(sys)
  }

  test("AskForRef returns InvalidId after the actor is closed") {
    val sys = newSystem()
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
    val sys = newSystem()
    import sys.SystemMsg.*
    val a = ActorBuilder[Int, String, Int]()
      .withId("x").withState(0)
      .withBehavior("default", { case (msg, _) => Some(s"A: $msg") })
      .build()
    awaitCF(sys ? Register(a))
    val rsp = resultCF(sys ? ActorClosed("x"))
    assertEquals(rsp, Done)
    awaitInvalid(sys, "x")
    close(a)
    close(sys)
  }

  test("Auto-deregistration on close") {
    val sys = newSystem()
    val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    awaitRef(sys, "a")
    close(a)
    awaitInvalid(sys, "a")
    close(sys)
  }

  test("ActorClosed for a non-existent id is safe") {
    val sys = newSystem()
    import sys.SystemMsg.*
    val rsp = resultCF(sys ? ActorClosed("nope"))
    assertEquals(rsp, Done)
    close(sys)
  }

  test("ActorClosed on system cascades to removeChild") {
    val sys = newSystem()
    import sys.SystemMsg.*
    val child = spawn(sys)(Spawn(actorId = "c"))
    awaitRef(sys, "c")
    close(child)
    awaitInvalid(sys, "c")
    // Children map cleaned: can re-spawn with same id
    val child2 = spawn(sys)(Spawn(actorId = "c"))
    assertEquals(child2.id, "c")
    close(child2)
    close(sys)
  }

  test("Closing a spawned child does not deregister its siblings") {
    val sys = newSystem()
    import sys.SystemMsg.*
    val c1 = spawn(sys)(Spawn(actorId = "c1"))
    val c2 = spawn(sys)(Spawn(actorId = "c2"))
    awaitRef(sys, "c1")
    awaitRef(sys, "c2")
    close(c1)
    awaitInvalid(sys, "c1")
    val ref = awaitRef(sys, "c2")
    assertEquals(ref.path.actorId, "c2")
    close(c2)
    close(sys)
  }

  // ============================================================================
  // 6. AskForRefAsync
  // ============================================================================

  test("AskForRefAsync delivers Ref to the sender actor") {
    val sys = newSystem()
    import sys.SystemMsg.*
    val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    awaitRef(sys, "a")
    val capturer = newCapturer(sys)
    awaitRef(sys, "capturing")
    val rsp = resultCF(sys ? AskForLocalRefAsync(capturer, "a"))
    assertEquals(rsp, Done)
    val ref = awaitCapturedRef(capturer)
    assertEquals(ref.path.actorId, "a")
    close(capturer)
    close(a)
    close(sys)
  }

  test("AskForRefAsync delivers InvalidId to the sender when not found") {
    val sys = newSystem()
    import sys.SystemMsg.*
    val capturer = newCapturer(sys)
    awaitRef(sys, "capturing")
    val rsp = resultCF(sys ? AskForLocalRefAsync(capturer, "nonexistent"))
    assertEquals(rsp, Done)
    assert(waitFor(capturer.receivedInvalid, true))
    close(capturer)
    close(sys)
  }

  test("AskForRefAsync response to the asker is always Done") {
    val sys = newSystem()
    import sys.SystemMsg.*
    val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    awaitRef(sys, "a")
    val capturer = newCapturer(sys)
    awaitRef(sys, "capturing")
    assertEquals(resultCF(sys ? AskForLocalRefAsync(capturer, "a")), Done)
    assertEquals(resultCF(sys ? AskForLocalRefAsync(capturer, "nonexistent")), Done)
    close(capturer)
    close(a)
    close(sys)
  }

  // ============================================================================
  // 7. ActorRef / Ref
  // ============================================================================

  test("Ref from AskForRef is usable for ? (ask)") {
    val sys = newSystem()
    val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    val ref = awaitRef(sys, "a")
    assertEquals(resultCF(ref ? 42), "A: 42")
    close(a)
    close(sys)
  }

  test("Ref from AskForRef is usable for ! (bang)") {
    val sys = newSystem()
    val received = SourceSignal(0)
    val a = ActorBuilder[Int, String, Int]()
      .withId("a").withState(0)
      .withBehavior("default", { case (msg, _) => received.mutate(_ + 1); Some(s"A: $msg") })
      .withSystem(sys).build()
    val ref = awaitRef(sys, "a")
    ref ! 42
    waitFor(received, 1)
    close(a)
    close(sys)
  }

  test("LocalActorRef.path is ActorPath.Local(actor.id)") {
    val sys = newSystem()
    val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    val ref = awaitRef(sys, "a")
    assert(ref.isLocal)
    assertEquals(ref.path.actorId, "a")
    assertEquals(ref.path, ActorPath.Local("a"))
    close(a)
    close(sys)
  }

  // ============================================================================
  // 8. Cross-actor communication through the system
  // ============================================================================

  test("Two actors communicate via AskForRef") {
    val sys = newSystem()
    val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    val b = newActor(sys, "b", { case (msg, _) => Some(s"B: $msg") })
    awaitRef(sys, "a")
    awaitRef(sys, "b")
    val aRef = awaitRef(sys, "a")
    assertEquals(resultCF(aRef ? 10), "A: 10")
    val bRef = awaitRef(sys, "b")
    assertEquals(resultCF(bRef ? 20), "B: 20")
    close(a); close(b)
    close(sys)
  }

  test("Two actors communicate via AskForRefAsync") {
    val sys = newSystem()
    import sys.SystemMsg.*
    val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    awaitRef(sys, "a")
    val capturer = newCapturer(sys)
    awaitRef(sys, "capturing")
    resultCF(sys ? AskForLocalRefAsync(capturer, "a"))
    val ref = awaitCapturedRef(capturer)
    assertEquals(resultCF(ref ? 42), "A: 42")
    close(capturer)
    close(a)
    close(sys)
  }

  // ============================================================================
  // 9. Concurrency
  // ============================================================================

  test("Concurrent registrations from multiple threads are thread-safe") {
    val sys = newSystem()
    val numThreads = 10
    val actorsPerThread = 5
    val actors = scala.collection.concurrent.TrieMap.empty[String, Actor[Int, String, Int]]

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
    actors.values.foreach(close)
    close(sys)
  }

  test("Concurrent AskForRef queries during registration are safe") {
    val sys = newSystem()
    val numActors = 50
    val actors = (0 until numActors).map { i =>
      newActor(sys, s"a$i", { case (msg, _) => Some(s"a$i: $msg") })
    }

    val queryFutures: Seq[Future[Unit]] = actors.map { a =>
      Future { awaitRef(sys, a.id) }
    }
    Await.result(Future.sequence(queryFutures), 10.seconds)

    actors.foreach(close)
    close(sys)
  }

  test("Concurrent spawning on the system registers all children") {
    val sys = newSystem()
    import sys.SystemMsg.*
    val numThreads = 10
    val spawnsPerThread = 5
    val expected = numThreads * spawnsPerThread
    val children = scala.collection.concurrent.TrieMap.empty[String, Actor[Int, String, Int]]

    val futures: Seq[Future[Unit]] = (0 until numThreads).map { _ =>
      Future {
        (0 until spawnsPerThread).foreach { _ =>
          val rsp = Await.result(sys ? Spawn(actorId = ""), 2.seconds)
          rsp match {
            case NewChild(c) => children.put(c.id, c)
            case other => throw new AssertionError(s"Unexpected response: $other")
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
}
