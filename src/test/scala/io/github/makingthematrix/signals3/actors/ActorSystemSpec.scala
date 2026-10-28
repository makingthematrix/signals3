package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.testutils.*
import io.github.makingthematrix.signals3.*
import io.github.makingthematrix.signals3.actors.SystemMsg.*
import io.github.makingthematrix.signals3.actors.Actor.{InvalidIdException, NoRefFound}
import io.github.makingthematrix.signals3.actors.ActorSystem.InvalidSystemIdException
import munit.FunSuite

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.util.{Failure, Success, Try}

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

  private def newSystem(): ActorSystem[String, Int] = newSystem("sys")

  private def newSystem(id: String): ActorSystem[String, Int] =
    ActorSystem[String, Int](id, 0, Actor.defBeat)

  private def newActor(sys: ActorSystem[String, Int], id: String,
                       pf: Actor.PF[String, Int]): Actor[String, Int] =
    ActorBuilder[String, Int](0)
      .withId(id).withState(0).withBehavior("default", pf).withSystem(sys).build()

  private def close(actor: Actor[?, ?]): Unit = {
    actor.asInstanceOf[Closeable].close()
    waitFor(actor.isClosedSignal, true)
  }

  private def close(actorRef: ActorRef[?]): Unit =
    tryResultCF(actorRef ? Close) match {
      case Success(Done) =>
      case Success(msg) => fail(s"closing ${actorRef.id} failed: $msg")
      case Failure(ex) => fail(ex.getMessage)
    }

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

  private def awaitRef(sys: ActorSystem[String, Int], id: String): ActorRef[String] = {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < 5000) {
      try {
        tryResultCF(sys ? AskForRef(id)) match {
          case Success(Ref(ref))                 => return ref.asInstanceOf[ActorRef[String]]
          case Failure(InvalidIdException(`id`)) => Thread.sleep(50)
          case other                             => fail(s"Unexpected response: $other")
        }
      } catch {
        case _: TimeoutException => Thread.sleep(50)
      }
    }
    fail(s"Actor '$id' was not registered within 5 seconds")
  }

  private def awaitInvalid(sys: ActorSystem[String, Int], id: String): Unit = {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < 5000) {
      try {
        tryResultCF[SystemMsg](sys ? AskForRef(id)) match {
          case Failure(InvalidIdException(`id`)) => return
          case Success(Ref(_)) => Thread.sleep(50)
          case other => throw new AssertionError(s"Unexpected response: $other")
        }
      } catch {
        case _: TimeoutException => Thread.sleep(50)
      }
    }
    fail(s"Actor '$id' was still registered after 5 seconds")
  }

  /** Two cross-registered systems: each one holds the other in its `systems` map. */
  private def crossRegistered(systemAId: String = "A", systemBId: String = "B"): (ActorSystem[String, Int], ActorSystem[String, Int]) = {
    val a = newSystem(systemAId)
    val b = newSystem(systemBId)
    awaitCF(a ? RegisterSystem(b))
    awaitCF(b ? RegisterSystem(a))
    (a, b)
  }

  /** Looks up an actor on a peer system and returns the (remote) ref. */
  private def remoteRef(sys: ActorSystem[String, Int], actorId: String, systemId: String): ActorRef[String] =
    tryResultCF(sys ? AskForRef(actorId, systemId)) match {
      case Success(Ref(ref)) => ref.asInstanceOf[ActorRef[String]]
      case other            => fail(s"Unexpected AskForRef response: $other")
    }

  /** A user-defined tag - verifies that `ActorTag` is genuinely extensible. */
  private case object Custom extends ActorTag("custom")

  /** An actor that captures the Ref delivered by AskForRefAsync in a system message. */
  private class CapturingActorImpl(sys: ActorSystem[String, Int])(using ExecutionContext)
    extends ActorImpl[String, Int]("capturing", 0, Actor.defBeat, Set.empty, None, Some(sys)) {
    @volatile var receivedRef: Option[ActorRef[String]] = None
    @volatile var receivedCount: Int = 0
    override protected def processSysEntry(msg: SysEntry): Unit = msg match {
      case (Ref(ref), p) =>
        receivedRef = Some(ref.asInstanceOf[ActorRef[String]])
        receivedCount += 1
        respond(p, Done)
      case other =>
        super.processSysEntry(other)
    }
  }

  /** An actor built without a system and registered manually with the given tags. */
  private def newTaggedActor(sys: ActorSystem[String, Int], id: String, tags: ActorTag*): Actor[String, Int] = {
    val a = ActorBuilder[String, Int](0)
      .withId(id)
      .withBehavior("default", { case (msg, _) => Some(s"$id: $msg") })
      .build()
    resultCF(sys ? Register(a, tags.toSet)) match {
      case Ref(_) => a
      case other  => fail(s"registering '$id' with tags ${tags.map(_.name).mkString(",")} failed: $other")
    }
  }

  /** Looks up an actor by tags - all tags in the query must match. */
  private def refByTags(sys: ActorSystem[String, Int], tags: ActorTag*): ActorRef[String] =
    resultCF(sys ? AskForRef(tags = tags.toSet)) match {
      case Ref(ref) => ref.asInstanceOf[ActorRef[String]]
      case other    => fail(s"Expected a Ref for tags ${tags.map(_.name).mkString(",")}, got $other")
    }

  /** Polls a tag query until an actor registered with the given tags is found -
    * registration is asynchronous with respect to actor creation. */
  private def awaitRefByTags(sys: ActorSystem[String, Int], tags: ActorTag*): ActorRef[String] = {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < 5000) {
      tryResultCF(sys ? AskForRef(tags = tags.toSet)) match {
        case Success(Ref(ref)) => return ref.asInstanceOf[ActorRef[String]]
        case Failure(_)       => Thread.sleep(50) // not registered (yet)
        case other            => fail(s"Unexpected AskForRef response: $other")
      }
    }
    fail(s"No actor with tags ${tags.map(_.name).mkString(",")} was registered within 5 seconds")
  }

  /** Looks up a system by id or by tags. */
  private def systemRef(sys: ActorSystem[String, Int], msg: SystemMsg): ActorRef[String] =
    resultCF(sys ? msg) match {
      case Ref(ref) => ref.asInstanceOf[ActorRef[String]]
      case other    => fail(s"Expected a Ref of a system, got $other")
    }

  /** Waits until the capturing actor receives a Ref. */
  private def awaitCaptured(capturer: CapturingActorImpl): ActorRef[String] = {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < 5000) {
      capturer.receivedRef match {
        case Some(ref) => return ref
        case None     => Thread.sleep(50)
      }
    }
    fail("the sender actor did not receive the Ref within 5 seconds")
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

  test("Register via ? stores the actor and returns Ref") {
    val sys = newSystem()
    
    val a = ActorBuilder[String, Int](0)
      .withId("manual")
      .withBehavior("default", { case (msg, _) => Some(s"M: $msg") })
      .build()
    resultCF(sys ? Register(a)) match {
      case Ref(ref) => assertEquals(ref.id, "manual")
      case other => fail(s"Expected Ref, got $other")
    }
    close(a)
    close(sys)
  }

  test("Register with a duplicate id fails and does not overwrite the previous entry") {
    val sys = newSystem()
    val a = ActorBuilder[String, Int](0)
      .withId("dup")
      .withBehavior("default", { case (msg, _) => Some(s"A: $msg") })
      .build()
    val b = ActorBuilder[String, Int](0) // it's ok to have two unconnected actors with the same id
      .withId("dup")
      .withBehavior("default", { case (msg, _) => Some(s"B: $msg") })
      .build()

    tryResultCF(sys ? Register(a)) match {
      case Success(Ref(ref)) => assertEquals(ref.id, "dup")
      case Failure(ex) => fail(ex.getMessage)
      case _ => fail("registering the first actor failed")
    }

    // it's not ok register them in the same actor system
    tryResultCF(sys ? Register(a)) match {
      case Success(Ref(ref)) => fail("registering the second actor succeeded - it should have failed")
      case Success(InvalidId) =>
      case Failure(ex) => fail(ex.getMessage)
      case res => fail(s"something went wrong: $res")
    }

    val ref = awaitRef(sys, "dup")
    assertEquals(resultCF(ref ? "1"), "A: 1")
    close(a)
    close(b)
    close(sys)
  }

  // ============================================================================
  // 3. Spawn integration with the system registry
  // ============================================================================

  test("Spawned child auto-registers on the system") {
    val sys = newSystem()
    val child = spawn(sys)(Spawn(actorId = "c"))
    val ref = awaitRef(sys, "c")
    assertEquals(ref.path.actorId, "c")
    close(child)
    close(sys)
  }

  test("Grandchild auto-registers on the same system") {
    val sys = newSystem()
    val child = spawn(sys)(SystemMsg.Spawn())
    val grandchild = spawn(child)(Spawn())
    awaitRef(sys, grandchild.id)
    close(sys)
  }

  // ============================================================================
  // 4. AskForRef
  // ============================================================================

  test("AskForRef returns InvalidId for an unknown id") {
    val sys = newSystem()
    
    val rsp = tryResultCF(sys ? AskForRef("nonexistent"))
    assertEquals(rsp, Failure(InvalidIdException("nonexistent")))
    close(sys)
  }

  test("AskForRef with the system's own id behaves as a local lookup") {
    val sys = newSystem()
    
    val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    awaitRef(sys, "a")
    resultCF(sys ? AskForRef("a", sys.id)) match {
      case Ref(ref) =>
        assert(ref.isLocal)
        assertEquals(ref.path, ActorPath.Local("a"))
      case other => fail(s"Expected Ref, got $other")
    }
    assertEquals(tryResultCF(sys ? AskForRef("nonexistent", sys.id)), Failure(InvalidIdException("nonexistent")))
    close(a)
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
    
    val a = ActorBuilder[String, Int](0)
      .withId("x")
      .withBehavior("default", { case (msg, _) => Some(s"A: $msg") })
      .build()
    awaitCF(sys ? Register(a))
    val rsp = resultCF(sys ? ActorClosed("x"))
    assertEquals(rsp, Done)
    awaitInvalid(sys, "x")
    close(a)
    close(sys)
  }

  test("ActorClosed for a non-existent id is safe and returns InvalidId") {
    val sys = newSystem()
    
    val rsp = resultCF(sys ? ActorClosed("nope"))
    assertEquals(rsp, InvalidId)
    close(sys)
  }

  test("ActorClosed on system cascades to removeChild") {
    val sys = newSystem()
    
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
    val sys = newSystem()
    
    val c1 = spawn(sys)(Spawn(actorId = "c1"))
    val c2 = spawn(sys)(Spawn(actorId = "c2"))
    awaitRef(sys, "c1")
    awaitRef(sys, "c2")
    awaitCF(c1 ? Close)
    awaitInvalid(sys, "c1")
    val ref = awaitRef(sys, "c2")
    assertEquals(ref.path.actorId, "c2")
    close(c2)
    close(sys)
  }

  // ============================================================================
  // 7. ActorRef / Ref
  // ============================================================================

  test("Ref from AskForRef is usable for ? (ask)") {
    val sys = newSystem()
    val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    val ref = awaitRef(sys, "a")
    assertEquals(resultCF(ref ? "42"), "A: 42")
    close(a)
    close(sys)
  }

  test("Ref from AskForRef is usable for ! (bang)") {
    val sys = newSystem()
    val received = SourceSignal(0)
    val a = ActorBuilder[String, Int](0)
      .withId("a")
      .withBehavior("default", { case (msg, _) => received.mutate(_ + 1); Some(s"A: $msg") })
      .withSystem(sys).build()
    val ref = awaitRef(sys, "a")
    ref ! "42"
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
    assertEquals(resultCF(aRef ? "10"), "A: 10")
    val bRef = awaitRef(sys, "b")
    assertEquals(resultCF(bRef ? "20"), "B: 20")
    close(a); close(b)
    close(sys)
  }

  // ============================================================================
  // 9. Concurrency
  // ============================================================================

  test("Concurrent registrations from multiple threads are thread-safe") {
    val sys = newSystem()
    val numThreads = 10
    val actorsPerThread = 5
    val actors = scala.collection.concurrent.TrieMap.empty[String, Actor[String, Int]]

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
    
    val numThreads = 10
    val spawnsPerThread = 5
    val expected = numThreads * spawnsPerThread
    val children = scala.collection.concurrent.TrieMap.empty[String, ActorRef[String]]

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
    val sys = newSystem()
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
    
    close(child)
    close(sys)
  }

  // ============================================================================
  // 11. Cross-system delivery of system messages
  // ============================================================================

  test("cross-system tell of Pause via a Remote path pauses the peer's actor") {
    val (a, b) = crossRegistered()
    val received = SourceSignal(0)
    val onB = newActor(b, "onB", { case (msg, _) => received.mutate(_ + 1); Some(s"B: $msg") })
    try {
      val ref = awaitRef(b, "onB")
      ref ! "1"
      assert(waitFor(received, 1), "warm-up message was not delivered")

      a.tell(Pause, ActorPath.Remote("B", "onB"))
      assert(waitFor(onB.isPausedSignal, true), "the peer's actor was not paused by a cross-system Pause")
      ref ! "2"
      Thread.sleep(300)
      assertEquals(received.currentValue, Some(1), "the paused actor processed a message")

      a.tell(Unpause, ActorPath.Remote("B", "onB"))
      assert(waitFor(onB.isPausedSignal, false), "the peer's actor was not unpaused by a cross-system Unpause")
      assert(waitFor(received, 2), "the message sent while paused was not processed after Unpause")
    } finally {
      Try(close(onB)); Try(close(a)); Try(close(b))
    }
  }

  test("cross-system ask of a SystemMsg via a Remote path returns Done") {
    val (a, b) = crossRegistered()
    val onB = newActor(b, "onB", { case (msg, _) => Some(s"B: $msg") })
    try {
      awaitRef(b, "onB")
      assertEquals(resultCF(a.ask(Pause, ActorPath.Remote("B", "onB"))), Done)
      assert(waitFor(onB.isPausedSignal, true), "the peer's actor was not paused by a cross-system ask")
      assertEquals(resultCF(a.ask(Unpause, ActorPath.Remote("B", "onB"))), Done)
      assert(waitFor(onB.isPausedSignal, false), "the peer's actor was not unpaused by a cross-system ask")
    } finally {
      Try(close(onB)); Try(close(a)); Try(close(b))
    }
  }

  test("a RemoteActorRef forwards system messages") {
    val (a, b) = crossRegistered()
    val onB = newActor(b, "onB", { case (msg, _) => Some(s"B: $msg") })
    try {
      awaitRef(b, "onB")
      val ref = remoteRef(a, "onB", "B")
      assert(!ref.isLocal)
      ref ! Pause
      assert(waitFor(onB.isPausedSignal, true), "the peer's actor was not paused through the RemoteActorRef")
      assertEquals(resultCF(ref ? Unpause), Done)
      assert(waitFor(onB.isPausedSignal, false), "the peer's actor was not unpaused through the RemoteActorRef")
      assertEquals(resultCF(ref ? "7"), "B: 7")
    } finally {
      Try(close(onB)); Try(close(a)); Try(close(b))
    }
  }

  test("cross-system Close via a RemoteActorRef closes the peer's actor and deregisters it") {
    val (a, b) = crossRegistered()
    val onB = newActor(b, "onB", { case (msg, _) => Some(s"B: $msg") })
    try {
      awaitRef(b, "onB")
      val ref = remoteRef(a, "onB", "B")
      assertEquals(resultCF(ref ? Close), Done)
      assert(waitFor(onB.isClosedSignal, true), "the peer's actor was not closed through the RemoteActorRef")
      awaitInvalid(b, "onB")
    } finally {
      Try(close(onB)); Try(close(a)); Try(close(b))
    }
  }

  test("cross-system Close via a path closes the peer's actor") {
    val (a, b) = crossRegistered()
    val onB = newActor(b, "onB", { case (msg, _) => Some(s"B: $msg") })
    try {
      awaitRef(b, "onB")
      a.tell(Close, ActorPath.Remote("B", "onB"))
      assert(waitFor(onB.isClosedSignal, true), "the peer's actor was not closed by a path-based Close")
      awaitInvalid(b, "onB")
    } finally {
      Try(close(onB)); Try(close(a)); Try(close(b))
    }
  }

  test("cross-system AddBehavior and RemoveBehavior reach the peer's actor") {
    val (a, b) = crossRegistered()
    val onB = newActor(b, "onB", { case (msg, _) if msg.startsWith("d:") => Some(s"D: $msg") })
    val extra: Actor.PF[String, Int] = { case (msg, _) if msg.startsWith("e:") => Some(s"E: $msg") }
    try {
      awaitRef(b, "onB")
      val ref = remoteRef(a, "onB", "B")
      assert(tryResultCF(ref ? "e:1").isFailure, "the default behavior should not handle 'e:' messages")

      assertEquals(resultCF(a.ask(AddBehavior[String, Int]("extra" -> extra), ActorPath.Remote("B", "onB"))), Done)
      assertEquals(resultCF(ref ? "e:1"), "E: e:1")

      assertEquals(resultCF(a.ask(RemoveBehavior("extra"), ActorPath.Remote("B", "onB"))), Done)
      assert(tryResultCF(ref ? "e:2").isFailure, "the removed behavior still handled the message")
    } finally {
      Try(close(onB)); Try(close(a)); Try(close(b))
    }
  }

  test("cross-system AskForRefAsync for an actor missing on the peer fails with InvalidIdException") {
    val (a, b) = crossRegistered()
    val capturer = new CapturingActorImpl(a)
    capturer.initialize()
    try {
      tryResultCF(a ? AskForRefAsync(capturer, "nonexistent", "B")) match {
        case Failure(InvalidIdException("nonexistent")) => ()
        case other => fail(s"expected InvalidIdException failure, got $other")
      }
      Thread.sleep(200)
      assert(capturer.receivedRef.isEmpty, "the sender actor received a Ref for a nonexistent actor")
    } finally {
      Try(close(capturer)); Try(close(a)); Try(close(b))
    }
  }

  // ============================================================================
  // 12. System messages routed to the system itself
  // ============================================================================

  test("SystemMsg via self-addressed path forms reaches the system itself") {
    val sys = newSystem("sys")
    try {
      val selfPaths = List(ActorPath.Direct, ActorPath.Local("sys"), ActorPath.Remote("", "sys"), ActorPath.Remote("sys", "sys"))
      selfPaths.foreach { path =>
        assertEquals(resultCF(sys.ask(Pause, path)), Done, s"ask Pause via $path")
        assert(waitFor(sys.isPausedSignal, true), s"the system was not paused via $path")
        assertEquals(resultCF(sys.ask(Unpause, path)), Done, s"ask Unpause via $path")
        assert(waitFor(sys.isPausedSignal, false), s"the system was not unpaused via $path")
      }
      sys.tell(Pause, ActorPath.Direct)
      assert(waitFor(sys.isPausedSignal, true), "the system was not paused by a bang via Direct")
      sys.tell(Unpause, ActorPath.Local("sys"))
      assert(waitFor(sys.isPausedSignal, false), "the system was not unpaused by a bang via Local")
    } finally {
      Try(close(sys))
    }
  }

  test("SystemMsg ask via a Remote path naming the own system and a registered actor is delivered by the actor") {
    val sys = newSystem("sys")
    val act = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    try {
      awaitRef(sys, "a")
      assertEquals(resultCF(sys.ask(Pause, ActorPath.Remote("sys", "a"))), Done)
      assert(waitFor(act.isPausedSignal, true), "the actor was not paused by an ask through a Remote path")
      assert(!sys.isPaused, "the system itself was paused")
      assertEquals(resultCF(sys.ask(Unpause, ActorPath.Remote("sys", "a"))), Done)
      assert(waitFor(act.isPausedSignal, false), "the actor was not unpaused by an ask through a Remote path")
    } finally {
      Try(close(act)); Try(close(sys))
    }
  }

  test("a SystemMsg bang to a not-yet-registered local actor is requeued and delivered") {
    val sys = newSystem("sys")
    val received = SourceSignal(0)
    try {
      // the actor does not exist yet: the system message must be requeued (SysRequeue), not dropped
      sys.tell(Pause, ActorPath.Remote("sys", "late"))
      val late = newActor(sys, "late", { case (msg, _) => received.mutate(_ + 1); Some(s"L: $msg") })
      assert(waitFor(late.isPausedSignal, true), "the requeued Pause was not delivered to the late actor")

      val ref = awaitRef(sys, "late")
      ref ! "1"
      Thread.sleep(300)
      assertEquals(received.currentValue, Some(0), "the paused actor processed a message")

      assertEquals(resultCF(sys.ask(Unpause, ActorPath.Remote("sys", "late"))), Done)
      assert(waitFor(received, 1), "the message sent while paused was not processed after Unpause")
      close(late)
    } finally {
      Try(close(sys))
    }
  }

  test("a SystemMsg bang to a never-registered actor is dropped after retries without affecting the system") {
    val sys = newSystem("sys")
    try {
      sys.tell(Pause, ActorPath.Remote("sys", "missing"))
      Thread.sleep(500) // longer than the ~3-heartbeat requeue window (defBeat = 100 ms)
      assert(!sys.isPaused, "the requeued message affected the system itself")

      // the system remains fully functional
      val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
      assertEquals(resultCF(awaitRef(sys, "a") ? "1"), "A: 1")
      close(a)
    } finally {
      Try(close(sys))
    }
  }

  // ============================================================================
  // 13. Failure policy for the SystemMsg path-based API
  // ============================================================================

  test("SystemMsg tell to an unknown system id is silently dropped") {
    val sys = newSystem("sys")
    try {
      sys.tell(Pause, ActorPath.Remote("UNKNOWN", "x"))
      Thread.sleep(200)
      assert(!sys.isPaused, "the system itself was paused by a message to an unknown system")
      val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
      assertEquals(resultCF(awaitRef(sys, "a") ? "1"), "A: 1")
      close(a)
    } finally {
      Try(close(sys))
    }
  }

  test("SystemMsg ask to an unknown system id fails with InvalidSystemIdException") {
    val sys = newSystem("sys")
    try {
      tryResultCF(sys.ask(Pause, ActorPath.Remote("UNKNOWN", "x"))) match {
        case Failure(InvalidSystemIdException("UNKNOWN")) => ()
        case other => fail(s"expected InvalidSystemIdException failure, got $other")
      }
    } finally {
      Try(close(sys))
    }
  }

  test("SystemMsg ask to a missing actor on a known peer falls through to the peer's own stream") {
    // documents the current policy: a missing actor id on the peer makes the ask land in the peer's
    // system stream, so the operation is applied to the peer's system actor itself
    val (a, b) = crossRegistered()
    try {
      assertEquals(resultCF(a.ask(Pause, ActorPath.Remote("B", "missing"))), Done)
      assert(waitFor(b.isPausedSignal, true), "the peer's system was not paused by the fall-through ask")
      assertEquals(resultCF(a.ask(Unpause, ActorPath.Remote("B", "missing"))), Done)
      assert(waitFor(b.isPausedSignal, false), "the peer's system was not unpaused by the fall-through ask")
    } finally {
      Try(close(a)); Try(close(b))
    }
  }

  // ============================================================================
  // 14. Systems-map lifecycle
  // ============================================================================

  test("RegisterSystem with a duplicate id returns InvalidId and keeps the original mapping") {
    val (a, b) = crossRegistered()
    val onA = newActor(a, "onA", { case (msg, _) => Some(s"A: $msg") })
    try {
      assertEquals(resultCF(a ? RegisterSystem(b)), InvalidId, "duplicate RegisterSystem succeeded")
      awaitRef(a, "onA")
      resultCF(b ? AskForRef("onA", "A")) match {
        case Ref(ref) => assertEquals(resultCF(ref.asInstanceOf[ActorRef[String]] ? "1"), "A: 1")
        case other    => fail(s"the original peer mapping was lost: $other")
      }
    } finally {
      Try(close(onA)); Try(close(a)); Try(close(b))
    }
  }

  test("SystemClosed via ask unregisters the peer; unknown ids complete with InvalidId") {
    val (a, b) = crossRegistered()
    val onA = newActor(a, "onA", { case (msg, _) => Some(s"A: $msg") })
    try {
      awaitRef(a, "onA")
      resultCF(b ? AskForRef("onA", "A")) match {
        case Ref(_) => ()
        case other  => fail(s"Unexpected AskForRef response: $other")
      }

      assertEquals(resultCF(b ? SystemClosed("A")), Done)
      tryResultCF(b ? AskForRef("onA", "A")) match {
        case Failure(InvalidSystemIdException("A")) => ()
        case other => fail(s"expected InvalidSystemIdException after SystemClosed, got $other")
      }

      // unknown ids must not hang the ask - they complete with InvalidId
      assertEquals(resultCF(b ? UnregisterSystem("ghost")), InvalidId)
      assertEquals(resultCF(b ? SystemClosed("ghost")), InvalidId)
    } finally {
      Try(close(onA)); Try(close(a)); Try(close(b))
    }
  }

  test("Unregister removes a registered actor and returns InvalidId for unknown ids") {
    val sys = newSystem("sys")
    val act = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    try {
      awaitRef(sys, "a")
      assertEquals(resultCF(sys ? Unregister("a")), Done)
      awaitInvalid(sys, "a")
      assertEquals(resultCF(sys ? Unregister("nope")), InvalidId)
    } finally {
      Try(close(act)); Try(close(sys))
    }
  }

  test("tell is a no-op and ask fails on a closed system") {
    val sys = newSystem("sys")
    close(sys)
    sys.tell(Pause, ActorPath.Direct)      // must not throw
    sys.tell("42", ActorPath.Direct, "")   // must not throw
    tryResultCF(sys.ask(Pause, ActorPath.Direct)) match {
      case Failure(_: IllegalStateException) => ()
      case other => fail(s"expected the closed-actor failure, got $other")
    }
    tryResultCF(sys.ask("42", ActorPath.Remote("UNKNOWN", "x"), "")) match {
      case Failure(_: IllegalStateException) => ()
      case other => fail(s"expected the closed-actor failure, got $other")
    }
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
    try {
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
    } finally {
      actors.foreach(act => Try(close(act)))
      Try(close(a)); Try(close(b))
    }
  }

  // ============================================================================
  // 16. Registering with tags
  // ============================================================================

  test("Register with tags makes the actor findable by a tag query") {
    val sys = newSystem()
    val a = newTaggedActor(sys, "a", ActorTag.Log, ActorTag.Stats)
    try {
      val byLog = refByTags(sys, ActorTag.Log)
      assertEquals(byLog.id, "a")
      assertEquals(byLog.path, ActorPath.Local("a"))
      assertEquals(refByTags(sys, ActorTag.Log, ActorTag.Stats).id, "a")
      assertEquals(resultCF(byLog ? "ping"), "a: ping")
    } finally {
      Try(close(a)); Try(close(sys))
    }
  }

  test("Register with a duplicate id returns InvalidId and keeps the original entry with its tags") {
    val sys = newSystem()
    val a = newTaggedActor(sys, "dup", ActorTag.Log)
    val b = ActorBuilder[String, Int](0)
      .withId("dup")
      .withBehavior("default", { case (msg, _) => Some(s"B: $msg") })
      .build()
    try {
      assertEquals(resultCF(sys ? Register(b, Set(ActorTag.Stats))), InvalidId)
      assertEquals(refByTags(sys, ActorTag.Log).id, "dup")
      tryResultCF(sys ? AskForRef(tags = Set(ActorTag.Stats))) match {
        case Failure(NoRefFound(_)) => ()
        case other => fail(s"expected NoRefFound after a failed duplicate registration, got $other")
      }
    } finally {
      Try(close(a)); Try(close(b)); Try(close(sys))
    }
  }

  test("user-defined tags can be used to register and find actors") {
    val sys = newSystem()
    val a = newTaggedActor(sys, "a", Custom)
    try {
      assertEquals(refByTags(sys, Custom).id, "a")
      tryResultCF(sys ? AskForRef(tags = Set(ActorTag.Log))) match {
        case Failure(NoRefFound(_)) => ()
        case other => fail(s"expected NoRefFound for a tag the actor does not have, got $other")
      }
    } finally {
      Try(close(a)); Try(close(sys))
    }
  }

  // ============================================================================
  // 17. AskForRef by tags
  // ============================================================================

  test("a tag query matches only if all tags in the query are registered") {
    val sys = newSystem()
    val a = newTaggedActor(sys, "a", ActorTag.Log, ActorTag.Stats)
    val b = newTaggedActor(sys, "b", ActorTag.Log)
    try {
      assertEquals(refByTags(sys, ActorTag.Log, ActorTag.Stats).id, "a")
      // a subset query matches both actors, and collectFirst over a HashMap has no defined order
      assert(Set("a", "b").contains(refByTags(sys, ActorTag.Log).id),
        "the subset tag query returned an unexpected actor")
      tryResultCF(sys ? AskForRef(tags = Set(ActorTag.Log, ActorTag.Stats, Custom))) match {
        case Failure(NoRefFound(_)) => ()
        case other => fail(s"expected NoRefFound when the query has a tag no actor has, got $other")
      }
    } finally {
      Try(close(a)); Try(close(b)); Try(close(sys))
    }
  }

  test("a tag query matching several actors returns one of them") {
    val sys = newSystem()
    val a1 = newTaggedActor(sys, "a1", ActorTag.Log)
    val a2 = newTaggedActor(sys, "a2", ActorTag.Log)
    try {
      val id = refByTags(sys, ActorTag.Log).id
      assert(Set("a1", "a2").contains(id), s"the tag query returned an unexpected actor: $id")
    } finally {
      Try(close(a1)); Try(close(a2)); Try(close(sys))
    }
  }

  test("a tag query with no matching actor fails with NoRefFound") {
    val sys = newSystem()
    // auto-registration on the system uses an empty tag set
    val a = newActor(sys, "a", { case (msg, _) => Some(s"A: $msg") })
    try {
      awaitRef(sys, "a")
      tryResultCF(sys ? AskForRef(tags = Set(ActorTag.Log))) match {
        case Failure(NoRefFound(tags)) => assertEquals(tags, Set[ActorTag](ActorTag.Log))
        case other => fail(s"expected NoRefFound, got $other")
      }
    } finally {
      Try(close(a)); Try(close(sys))
    }
  }

  test("a tag query with the System tag finds the system itself registered as an actor") {
    val sys = newSystem("sys")
    try {
      val ref = refByTags(sys, ActorTag.System)
      assertEquals(ref.id, "sys")
      assert(ref.isLocal)
      assertEquals(resultCF(ref ? Pause), Done)
      assert(waitFor(sys.isPausedSignal, true), "the system was not paused through the ref found by tags")
      assertEquals(resultCF(ref ? Unpause), Done)
    } finally {
      Try(close(sys))
    }
  }

  test("a tag query with the system's own id behaves as a local query") {
    val sys = newSystem("sys")
    val a = newTaggedActor(sys, "a", ActorTag.Log)
    try {
      resultCF(sys ? AskForRef("", "sys", Set(ActorTag.Log))) match {
        case Ref(ref) => assertEquals(ref.id, "a")
        case other   => fail(s"Expected Ref, got $other")
      }
    } finally {
      Try(close(a)); Try(close(sys))
    }
  }

  // ============================================================================
  // 18. AskForRefAsync with tags
  // ============================================================================

  test("AskForRefAsync with tags delivers the Ref to the sender and returns Done") {
    val sys = newSystem()
    val a = newTaggedActor(sys, "a", ActorTag.Log)
    val capturer = new CapturingActorImpl(sys)
    capturer.initialize()
    try {
      assertEquals(resultCF(sys ? AskForRefAsync(capturer, tags = Set(ActorTag.Log))), Done)
      val ref = awaitCaptured(capturer)
      assertEquals(ref.id, "a")
      assertEquals(resultCF(ref ? "ping"), "a: ping")
    } finally {
      Try(close(capturer)); Try(close(a)); Try(close(sys))
    }
  }

  test("AskForRefAsync with tags and no match fails with NoRefFound and sends nothing to the sender") {
    val sys = newSystem()
    val capturer = new CapturingActorImpl(sys)
    capturer.initialize()
    try {
      tryResultCF(sys ? AskForRefAsync(capturer, tags = Set(ActorTag.Log))) match {
        case Failure(NoRefFound(_)) => ()
        case other => fail(s"expected NoRefFound, got $other")
      }
      Thread.sleep(200)
      assert(capturer.receivedCount == 0, "the sender actor received a Ref for a query with no match")
    } finally {
      Try(close(capturer)); Try(close(sys))
    }
  }

  test("AskForRefAsync for a remote actor delivers a RemoteActorRef to the sender") {
    val (a, b) = crossRegistered()
    val onB = newActor(b, "onB", { case (msg, _) => Some(s"B: $msg") })
    val capturer = new CapturingActorImpl(a)
    capturer.initialize()
    try {
      awaitRef(b, "onB")
      assertEquals(resultCF(a ? AskForRefAsync(capturer, "onB", "B")), Done)
      val ref = awaitCaptured(capturer)
      assert(!ref.isLocal, "the sender received a local ref to a remote actor")
      assertEquals(ref.path, ActorPath.Remote("B", "onB"))
      assertEquals(resultCF(ref ? "7"), "B: 7")
    } finally {
      Try(close(capturer)); Try(close(onB)); Try(close(a)); Try(close(b))
    }
  }

  // ============================================================================
  // 19. AskForSystem / AskForSystemAsync
  // ============================================================================

  test("AskForSystem with an empty id or the system's own id returns the system itself as a usable ref") {
    val sys = newSystem("sys")
    try {
      List(AskForSystem(), AskForSystem("sys")).foreach { q =>
        val ref = systemRef(sys, q)
        assert(ref.isLocal)
        assertEquals(ref.id, "sys")
        assertEquals(resultCF(ref ? Pause), Done)
        assert(waitFor(sys.isPausedSignal, true), s"the system was not paused through the ref from $q")
        assertEquals(resultCF(ref ? Unpause), Done)
        assert(waitFor(sys.isPausedSignal, false), s"the system was not unpaused through the ref from $q")
      }
    } finally {
      Try(close(sys))
    }
  }

  test("AskForSystem for a registered remote system returns a usable RemoteActorRef") {
    val (a, b) = crossRegistered()
    try {
      val ref = systemRef(a, AskForSystem("B"))
      assert(!ref.isLocal)
      assertEquals(ref.path, ActorPath.Remote("B", "B"))
      assertEquals(resultCF(ref ? Pause), Done)
      assert(waitFor(b.isPausedSignal, true), "the peer's system was not paused through the ref")
      assert(!a.isPaused, "the asking system was paused instead of the peer")
      assertEquals(resultCF(ref ? Unpause), Done)
      assert(waitFor(b.isPausedSignal, false), "the peer's system was not unpaused through the ref")
    } finally {
      Try(close(a)); Try(close(b))
    }
  }

  test("AskForSystem for an unknown system id fails with InvalidSystemIdException") {
    val sys = newSystem("sys")
    try {
      tryResultCF(sys ? AskForSystem("UNKNOWN")) match {
        case Failure(InvalidSystemIdException("UNKNOWN")) => ()
        case other => fail(s"expected InvalidSystemIdException, got $other")
      }
    } finally {
      Try(close(sys))
    }
  }

  test("AskForSystem by tags returns the tagged system") {
    val a = newSystem("A")
    val b = newSystem("B")
    try {
      awaitCF(a ? RegisterSystem(b, Set(ActorTag.Stats)))
      val ref = systemRef(a, AskForSystem(tags = Set(ActorTag.Stats)))
      assert(!ref.isLocal)
      assertEquals(ref.id, "B")
      assertEquals(ref.path, ActorPath.Remote("B", "B"))
      assertEquals(resultCF(ref ? Pause), Done)
      assert(waitFor(b.isPausedSignal, true), "the tagged system was not paused through the ref")
      assertEquals(resultCF(ref ? Unpause), Done)
      assert(waitFor(b.isPausedSignal, false), "the tagged system was not unpaused through the ref")
      // the system registers itself in the systems map with the System tag
      assertEquals(systemRef(a, AskForSystem(tags = Set(ActorTag.System))).id, "A")
    } finally {
      Try(close(a)); Try(close(b))
    }
  }

  test("AskForSystem by tags with no match fails with NoRefFound") {
    val sys = newSystem("sys")
    try {
      tryResultCF(sys ? AskForSystem(tags = Set(ActorTag.Log))) match {
        case Failure(NoRefFound(_)) => ()
        case other => fail(s"expected NoRefFound, got $other")
      }
    } finally {
      Try(close(sys))
    }
  }

  test("AskForSystemAsync with an empty id delivers a ref to the system itself to the sender") {
    val sys = newSystem("sys")
    val capturer = new CapturingActorImpl(sys)
    capturer.initialize()
    try {
      assertEquals(resultCF(sys ? AskForSystemAsync(capturer)), Done)
      val ref = awaitCaptured(capturer)
      assert(ref.isLocal)
      assertEquals(ref.id, "sys")
      assertEquals(resultCF(ref ? Pause), Done)
      assert(waitFor(sys.isPausedSignal, true), "the system was not paused through the delivered ref")
      assertEquals(resultCF(ref ? Unpause), Done)
    } finally {
      Try(close(capturer)); Try(close(sys))
    }
  }

  test("AskForSystemAsync for a registered remote system delivers a RemoteActorRef to the sender") {
    val (a, b) = crossRegistered()
    val capturer = new CapturingActorImpl(a)
    capturer.initialize()
    try {
      assertEquals(resultCF(a ? AskForSystemAsync(capturer, "B")), Done)
      val ref = awaitCaptured(capturer)
      assert(!ref.isLocal)
      assertEquals(ref.path, ActorPath.Remote("B", "B"))
      assertEquals(resultCF(ref ? Pause), Done)
      assert(waitFor(b.isPausedSignal, true), "the peer's system was not paused through the delivered ref")
      assertEquals(resultCF(ref ? Unpause), Done)
    } finally {
      Try(close(capturer)); Try(close(a)); Try(close(b))
    }
  }

  test("AskForSystemAsync for an unknown system id fails with InvalidSystemIdException and sends nothing to the sender") {
    val sys = newSystem("sys")
    val capturer = new CapturingActorImpl(sys)
    capturer.initialize()
    try {
      tryResultCF(sys ? AskForSystemAsync(capturer, "UNKNOWN")) match {
        case Failure(InvalidSystemIdException("UNKNOWN")) => ()
        case other => fail(s"expected InvalidSystemIdException, got $other")
      }
      Thread.sleep(200)
      assert(capturer.receivedCount == 0, "the sender actor received a Ref for an unknown system")
    } finally {
      Try(close(capturer)); Try(close(sys))
    }
  }

  test("AskForSystemAsync by tags delivers the tagged system's ref to the sender") {
    val a = newSystem("A")
    val b = newSystem("B")
    val capturer = new CapturingActorImpl(a)
    capturer.initialize()
    try {
      awaitCF(a ? RegisterSystem(b, Set(ActorTag.Stats)))
      assertEquals(resultCF(a ? AskForSystemAsync(capturer, tags = Set(ActorTag.Stats))), Done)
      val ref = awaitCaptured(capturer)
      assert(!ref.isLocal)
      assertEquals(ref.id, "B")
      assertEquals(ref.path, ActorPath.Remote("B", "B"))
      assertEquals(resultCF(ref ? Pause), Done)
      assert(waitFor(b.isPausedSignal, true), "the tagged system was not paused through the delivered ref")
      assertEquals(resultCF(ref ? Unpause), Done)
    } finally {
      Try(close(capturer)); Try(close(a)); Try(close(b))
    }
  }

  test("AskForSystemAsync by tags with no match fails with NoRefFound and sends nothing to the sender") {
    val sys = newSystem("sys")
    val capturer = new CapturingActorImpl(sys)
    capturer.initialize()
    try {
      tryResultCF(sys ? AskForSystemAsync(capturer, tags = Set(ActorTag.Log))) match {
        case Failure(NoRefFound(_)) => ()
        case other => fail(s"expected NoRefFound, got $other")
      }
      Thread.sleep(200)
      assert(capturer.receivedCount == 0, "the sender actor received a Ref for a tag query with no match")
    } finally {
      Try(close(capturer)); Try(close(sys))
    }
  }

  // ============================================================================
  // 20. Tag registry lifecycle and concurrency
  // ============================================================================

  test("a tag query returns NoRefFound after the tagged actor is unregistered") {
    val sys = newSystem()
    val a = newTaggedActor(sys, "a", ActorTag.Log)
    try {
      assertEquals(refByTags(sys, ActorTag.Log).id, "a")
      assertEquals(resultCF(sys ? Unregister("a")), Done)
      tryResultCF(sys ? AskForRef(tags = Set(ActorTag.Log))) match {
        case Failure(NoRefFound(_)) => ()
        case other => fail(s"expected NoRefFound after unregistering the only tagged actor, got $other")
      }
    } finally {
      Try(close(a)); Try(close(sys))
    }
  }

  test("a tag query over systems returns NoRefFound after the peer is unregistered") {
    val a = newSystem("A")
    val b = newSystem("B")
    try {
      awaitCF(a ? RegisterSystem(b, Set(ActorTag.Stats)))
      assertEquals(systemRef(a, AskForSystem(tags = Set(ActorTag.Stats))).id, "B")
      assertEquals(resultCF(a ? UnregisterSystem("B")), Done)
      tryResultCF(a ? AskForSystem(tags = Set(ActorTag.Stats))) match {
        case Failure(NoRefFound(_)) => ()
        case other => fail(s"expected NoRefFound after unregistering the peer, got $other")
      }
    } finally {
      Try(close(a)); Try(close(b))
    }
  }

  test("concurrent tagged registrations and tag queries are safe") {
    val sys = newSystem()
    val numThreads = 8
    val perThread = 5
    val queriesPerThread = 10
    val actors = scala.collection.concurrent.TrieMap.empty[String, Actor[String, Int]]
    try {
      val futures: Seq[Future[Unit]] = (0 until numThreads).map { t =>
        Future {
          val tag = if (t % 2 == 0) ActorTag.Log else ActorTag.Stats
          (0 until perThread).foreach { i =>
            val id = s"${tag.name}$t-$i"
            val a = ActorBuilder[String, Int](0)
              .withId(id)
              .withBehavior("default", { case (msg, _) => Some(s"$id: $msg") })
              .build()
            actors.put(id, a)
            awaitCF(sys ? Register(a, Set(tag)))
          }
          (0 until queriesPerThread).foreach { _ =>
            // the own tag always matches - this thread's own registrations are already done
            resultCF(sys ? AskForRef(tags = Set(tag))) match {
              case Ref(ref) =>
                val id = ref.asInstanceOf[ActorRef[String]].id
                assert(id.startsWith(tag.name), s"the tag query returned an unexpected actor: $id")
              case other => throw new AssertionError(s"the tag query failed: $other")
            }
            // no actor is ever registered with both tags
            tryResultCF(sys ? AskForRef(tags = Set(ActorTag.Log, ActorTag.Stats))) match {
              case Failure(NoRefFound(_)) => ()
              case other => throw new AssertionError(s"no actor has both tags, but the query returned: $other")
            }
          }
        }
      }
      Await.result(Future.sequence(futures), 30.seconds)
    } finally {
      actors.values.foreach(act => Try(close(act)))
      Try(close(sys))
    }
  }

  // ============================================================================
  // 21. Tags on actors created with ActorBuilder
  // ============================================================================

  test("withTags on the builder is used when the actor auto-registers on its system") {
    val sys = newSystem()
    val a = ActorBuilder[String, Int](0)
      .withId("built")
      .withBehavior("default", { case (msg, _) => Some(s"built: $msg") })
      .withTags(Set(ActorTag.Log))
      .withSystem(sys)
      .build()
    try {
      val ref = awaitRefByTags(sys, ActorTag.Log)
      assertEquals(ref.id, "built")
      assertEquals(resultCF(ref ? "1"), "built: 1")
    } finally {
      Try(close(a)); Try(close(sys))
    }
  }

  test("addTag and removeTag accumulate and remove tags on the builder") {
    val sys = newSystem()
    val a = ActorBuilder[String, Int](0)
      .withId("acc")
      .withBehavior("default", { case (msg, _) => Some(s"acc: $msg") })
      .withTags(Set(ActorTag.Log))
      .addTag(ActorTag.Stats)
      .addTag(Custom)
      .removeTag(ActorTag.Log)
      .withSystem(sys)
      .build()
    try {
      assertEquals(awaitRefByTags(sys, ActorTag.Stats, Custom).id, "acc")
      assertEquals(awaitRefByTags(sys, ActorTag.Stats).id, "acc") // a subset query matches
      tryResultCF(sys ? AskForRef(tags = Set(ActorTag.Log))) match {
        case Failure(NoRefFound(_)) => ()
        case other => fail(s"Log was removed on the builder, expected NoRefFound, got $other")
      }
    } finally {
      Try(close(a)); Try(close(sys))
    }
  }

  test("withNoTags clears the tags previously set on the builder") {
    val sys = newSystem()
    val a = ActorBuilder[String, Int](0)
      .withId("cleared")
      .withBehavior("default", { case (msg, _) => Some(s"cleared: $msg") })
      .withTags(Set(ActorTag.Log))
      .withNoTags()
      .withSystem(sys)
      .build()
    try {
      awaitRef(sys, "cleared") // registered, but with an empty tag set
      tryResultCF(sys ? AskForRef(tags = Set(ActorTag.Log))) match {
        case Failure(NoRefFound(_)) => ()
        case other => fail(s"expected NoRefFound after withNoTags, got $other")
      }
    } finally {
      Try(close(a)); Try(close(sys))
    }
  }

  // ============================================================================
  // 22. Tags of actor systems
  // ============================================================================

  test("a system with custom tags is discoverable by them and still by the System tag") {
    val sys = ActorSystem[String, Int]("custom", 0, Actor.defBeat, Set(ActorTag.Log, ActorTag.Stats))
    try {
      // the system registers itself in its own actorRefs with its custom tags
      assertEquals(refByTags(sys, ActorTag.Log, ActorTag.Stats).id, "custom")
      // the constructor always unions the System tag with the user-provided tags
      assertEquals(refByTags(sys, ActorTag.System).id, "custom")
      assertEquals(refByTags(sys, ActorTag.System, ActorTag.Log).id, "custom")
      // and in its own systems map
      assertEquals(systemRef(sys, AskForSystem(tags = Set(ActorTag.Stats))).id, "custom")
      assertEquals(systemRef(sys, AskForSystem(tags = Set(ActorTag.System, ActorTag.Log))).id, "custom")
    } finally {
      Try(close(sys))
    }
  }

  // ============================================================================
  // 23. Tags inherited by spawned actors
  // ============================================================================

  test("children spawned by a tagged actor inherit its tags plus the Child tag") {
    val sys = newSystem()
    val parent = ActorBuilder[String, Int](0)
      .withId("parent")
      .withBehavior("default", { case (msg, _) => Some(s"parent: $msg") })
      .withTags(Set(ActorTag.Log))
      .withSystem(sys)
      .build()
    try {
      val child = spawn(parent)(SystemMsg.Spawn())
      // the parent has Log but not Child, so {Log, Child} uniquely identifies the child
      assertEquals(awaitRefByTags(sys, ActorTag.Log, ActorTag.Child).id, child.id)
      assertEquals(awaitRefByTags(sys, ActorTag.Child).id, child.id)
      // a plain Log query matches both the parent and the child
      val logId = awaitRefByTags(sys, ActorTag.Log).id
      assert(Set("parent", child.id).contains(logId), s"the Log query returned an unexpected actor: $logId")

      // the grandchild inherits the same set - Child is not duplicated
      val grandchild = spawn(child)(SystemMsg.Spawn())
      val gcId = awaitRefByTags(sys, ActorTag.Log, ActorTag.Child).id
      assert(Set(child.id, grandchild.id).contains(gcId),
        s"the {Log, Child} query returned an unexpected actor: $gcId")
    } finally {
      Try(close(parent)); Try(close(sys))
    }
  }

  test("children spawned by the system inherit its tags plus the Child tag") {
    val sys = ActorSystem[String, Int]("sys", 0, Actor.defBeat, Set(ActorTag.Stats))
    try {
      val child = spawn(sys)(SystemMsg.Spawn())
      // the system's member tags are {Stats, System} (System is unioned in), so the child gets {Stats, System, Child}
      assertEquals(awaitRefByTags(sys, ActorTag.Stats, ActorTag.Child).id, child.id)
      assertEquals(awaitRefByTags(sys, ActorTag.Child).id, child.id)
      // consequence of the symmetric inheritance: a System-tag query matches both the system and its children
      val sysId = awaitRefByTags(sys, ActorTag.System).id
      assert(Set(sys.id, child.id).contains(sysId), s"the System query returned an unexpected actor: $sysId")
    } finally {
      Try(close(sys))
    }
  }
}
