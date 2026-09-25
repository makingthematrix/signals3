package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.testutils.*
import io.github.makingthematrix.signals3.*
import munit.FunSuite

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

/**
 * Unit tests for cross-system actor communication: message routing through
 * [[ActorPath]], system registration through [[RemoteSystem.RemoteSystemMsg]],
 * [[RemoteActorRef]] usage, and the failure policy for invalid actor/system ids.
 *
 * These tests exercise two actor systems in the same JVM connected through
 * RegisterSystem, which is the same code path a real network transport would use.
 */
class ActorSystemRemoteSpec extends FunSuite {
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

  private def newSystem(id: String): ActorSystem[Int, String, Int] =
    ActorSystem[Int, String, Int](id, 0, Actor.defBeat)

  private def close(actor: Actor[?, ?, ?]): Unit = {
    actor.asInstanceOf[Closeable].close()
    waitFor(actor.isClosedSignal, true)
  }

  private def newActorOn(sys: ActorSystem[Int, String, Int], id: String,
                         pf: Actor.PF[Int, String, Int]): Actor[Int, String, Int] =
    ActorBuilder[Int, String, Int]()
      .withId(id).withState(0).withBehavior("default", pf).withSystem(sys).build()

  private def awaitRef(sys: ActorSystem[Int, String, Int], id: String): ActorRef[Int, String] = {
    import sys.SystemMsg.*
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < 5000) {
      try {
        Await.result(sys ? AskForRef(id), 1.second) match {
          case Ref(ref) => return ref
          case InvalidId => Thread.sleep(50)
          case other => throw new AssertionError(s"Unexpected response: $other")
        }
      } catch {
        case _: scala.concurrent.TimeoutException => Thread.sleep(50)
      }
    }
    fail(s"Actor '$id' was not registered within 5 seconds")
  }

  /** Two cross-registered systems: each one holds the other in its `systems` map. */
  private def crossRegistered(systemAId: String = "A", systemBId: String = "B")
    : (ActorSystem[Int, String, Int], ActorSystem[Int, String, Int]) = {
    val a = newSystem(systemAId)
    val b = newSystem(systemBId)
    awaitCF(a ? a.SystemMsg.RegisterSystem(b))
    awaitCF(b ? b.SystemMsg.RegisterSystem(a))
    (a, b)
  }

  /** An actor that captures the Ref delivered by AskForRefAsync in a system message. */
  private class CapturingBaseActor(sys: ActorSystem[Int, String, Int])(using ec: scala.concurrent.ExecutionContext)
    extends BaseActor[Int, String, Int]("capturing", 0, Actor.defBeat, None, Some(sys)) {
    import SystemMsg.*
    @volatile var receivedRef: Option[ActorRef[Int, String]] = None
    override protected def processSysEntry(msg: SysEntry): Unit = msg match {
      case (Ref(ref), p) =>
        receivedRef = Some(ref)
        respond(p, Done)
      case other =>
        super.processSysEntry(other)
    }
  }

  // ============================================================================
  // 1. Routing to invalid ids must not recurse
  // ============================================================================

  test("bang to a nonexistent actor addressed by the system's own id is dropped, not recursed") {
    val sys = newSystem("sys")
    try {
      sys.bang(42, ActorPath.Remote("sys", "nonexistent"), "")
      Thread.sleep(300)
    } catch {
      case e: StackOverflowError => fail(s"StackOverflowError: ${e.getStackTrace.take(5).mkString(" | ")}")
    } finally {
      scala.util.Try(close(sys))
    }
  }

  test("ask to a nonexistent actor addressed by the system's own id fails, not recursed") {
    val sys = newSystem("sys")
    try {
      val cf = sys.ask(42, ActorPath.Remote("sys", "nonexistent"), "")
      Await.ready(cf.future, 5.seconds)
      assert(cf.future.value.exists(_.isFailure), s"expected failure, got ${cf.future.value}")
    } catch {
      case e: StackOverflowError => fail(s"StackOverflowError: ${e.getStackTrace.take(5).mkString(" | ")}")
    } finally {
      scala.util.Try(close(sys))
    }
  }

  test("cross-system bang to a nonexistent actor on the peer is dropped, not recursed") {
    val (a, b) = crossRegistered()
    try {
      a.bang(42, ActorPath.Remote("B", "nonexistent"), "")
      Thread.sleep(300)
    } catch {
      case e: StackOverflowError => fail(s"StackOverflowError: ${e.getStackTrace.take(5).mkString(" | ")}")
    } finally {
      scala.util.Try(close(a)); scala.util.Try(close(b))
    }
  }

  // ============================================================================
  // 2. Local delivery through refs and paths
  // ============================================================================

  test("a ref obtained via AskForRef delivers immediately after spawn, without waiting for a heartbeat") {
    val sys = newSystem("sys")
    import sys.SystemMsg.*
    val received = SourceSignal(0)
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => received.mutate(_ + 1); Some(s"C: $msg") }
    try {
      Await.result(sys ? Spawn(actorId = "c", behaviors = List("default" -> behavior)), 5.seconds) match {
        case NewChild(child) =>
          Await.result(sys ? AskForRef("c"), 5.seconds) match {
            case Ref(ref) =>
              ref ! 42
              assert(waitFor(received, 1), "message sent through a fresh ref was dropped")
              close(child)
            case other => fail(s"Unexpected AskForRef response: $other")
          }
        case other => fail(s"Unexpected spawn response: $other")
      }
    } finally {
      scala.util.Try(close(sys))
    }
  }

  test("a Local path sent from inside a behavior is routed through the system") {
    val sys = newSystem("sys")
    val received = SourceSignal(0)
    val senderBehavior: Actor.PF[Int, String, Int] =
      { case (msg, a) => a.bang(msg, ActorPath.Local("target"), ""); Some("sent") }
    val targetBehavior: Actor.PF[Int, String, Int] =
      { case (msg, _) => received.mutate(_ + 1); Some(s"T: $msg") }
    try {
      val sender = newActorOn(sys, "sender", senderBehavior)
      val target = newActorOn(sys, "target", targetBehavior)
      awaitRef(sys, "sender")
      awaitRef(sys, "target")
      Await.result(sys ? sys.SystemMsg.AskForRef("sender"), 5.seconds) match {
        case sys.SystemMsg.Ref(senderRef) =>
          senderRef ! 42
          assert(waitFor(received, 1), "message sent via a Local path from a behavior was not delivered")
        case other => fail(s"Unexpected AskForRef response: $other")
      }
      close(sender); close(target)
    } finally {
      scala.util.Try(close(sys))
    }
  }

  // ============================================================================
  // 3. Failure policy for invalid ids
  // ============================================================================

  test("AskForRef with an unknown system id fails with IllegalArgumentException") {
    val (a, b) = crossRegistered()
    try {
      val cf = a ? a.SystemMsg.AskForRef("someActor", "UNKNOWN")
      Await.ready(cf.future, 5.seconds)
      cf.future.value match {
        case Some(scala.util.Failure(t: IllegalArgumentException)) => ()
        case other => fail(s"expected IllegalArgumentException failure, got $other")
      }
    } finally {
      scala.util.Try(close(a)); scala.util.Try(close(b))
    }
  }

  test("AskForRefAsync with an unknown system id completes the asker with a failure instead of hanging") {
    val (a, b) = crossRegistered()
    val capturer = new CapturingBaseActor(a)
    capturer.initialize()
    try {
      val cf = a ? a.SystemMsg.AskForRefAsync(capturer, "someActor", "UNKNOWN")
      Await.ready(cf.future, 5.seconds)
      cf.future.value match {
        case Some(scala.util.Failure(t: IllegalArgumentException)) => ()
        case Some(scala.util.Success(m)) => fail(s"expected failure, got $m")
        case None => fail("AskForRefAsync with unknown system id never completes")
      }
    } finally {
      scala.util.Try(close(capturer)); scala.util.Try(close(a)); scala.util.Try(close(b))
    }
  }

  test("local AskForRef with an unknown actor id returns the InvalidId sentinel") {
    val sys = newSystem("sys")
    try {
      Await.result(sys ? sys.SystemMsg.AskForRef("nonexistent"), 5.seconds) match {
        case sys.SystemMsg.InvalidId => ()
        case other => fail(s"Expected InvalidId, got $other")
      }
    } finally {
      scala.util.Try(close(sys))
    }
  }

  test("cross-system AskForRef for an actor missing on the peer fails with IllegalArgumentException") {
    val (a, b) = crossRegistered()
    try {
      val cf = a ? a.SystemMsg.AskForRef("nonexistent", "B")
      Await.ready(cf.future, 5.seconds)
      cf.future.value match {
        case Some(scala.util.Failure(t: IllegalArgumentException)) => ()
        case other => fail(s"expected IllegalArgumentException failure, got $other")
      }
    } finally {
      scala.util.Try(close(a)); scala.util.Try(close(b))
    }
  }

  // ============================================================================
  // 4. Cross-system communication
  // ============================================================================

  test("cross-system AskForRef returns a usable RemoteActorRef") {
    val (a, b) = crossRegistered()
    val received = SourceSignal(0)
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => received.mutate(_ + 1); Some(s"B: $msg") }
    val actorOnB = newActorOn(b, "onB", behavior)
    try {
      awaitRef(b, "onB")
      Await.result(a ? a.SystemMsg.AskForRef("onB", "B"), 5.seconds) match {
        case a.SystemMsg.Ref(ref) =>
          assert(!ref.isLocal)
          assertEquals(ref.path, ActorPath.Remote("B", "onB"))
          ref ! 42
          assert(waitFor(received, 1), "cross-system bang was not delivered")
          assertEquals(resultCF(ref ? 43), "B: 43")
        case other => fail(s"Unexpected AskForRef response: $other")
      }
    } finally {
      scala.util.Try(close(actorOnB)); scala.util.Try(close(a)); scala.util.Try(close(b))
    }
  }

  test("cross-system AskForRef immediately after actor creation on the peer returns a usable ref") {
    val (a, b) = crossRegistered()
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"B: $msg") }
    val actorOnB = newActorOn(b, "fresh", behavior)
    try {
      // no awaitRef here: the lookup must queue behind the peer's pending Register
      Await.result(a ? a.SystemMsg.AskForRef("fresh", "B"), 5.seconds) match {
        case a.SystemMsg.Ref(ref) =>
          assertEquals(resultCF(ref ? 7), "B: 7")
        case other => fail(s"Unexpected AskForRef response: $other")
      }
    } finally {
      scala.util.Try(close(actorOnB)); scala.util.Try(close(a)); scala.util.Try(close(b))
    }
  }

  test("a child spawned on the peer is immediately discoverable via cross-system AskForRef") {
    val (a, b) = crossRegistered()
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"B: $msg") }
    try {
      Await.result(b ? b.SystemMsg.Spawn(actorId = "spawned", behaviors = List("default" -> behavior)), 5.seconds) match {
        case b.SystemMsg.NewChild(child) =>
          Await.result(a ? a.SystemMsg.AskForRef("spawned", "B"), 5.seconds) match {
            case a.SystemMsg.Ref(ref) =>
              assertEquals(resultCF(ref ? 1), "B: 1")
              close(child)
            case other => fail(s"Unexpected AskForRef response: $other")
          }
        case other => fail(s"Unexpected spawn response: $other")
      }
    } finally {
      scala.util.Try(close(a)); scala.util.Try(close(b))
    }
  }

  test("cross-system AskForRefAsync delivers a usable Ref to the sender actor") {
    val (a, b) = crossRegistered()
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"B: $msg") }
    val actorOnB = newActorOn(b, "onB2", behavior)
    val capturer = new CapturingBaseActor(a)
    capturer.initialize()
    try {
      awaitRef(a, "capturing")
      Await.result(a ? a.SystemMsg.AskForRefAsync(capturer, "onB2", "B"), 5.seconds) match {
        case a.SystemMsg.Done =>
          val start = System.currentTimeMillis()
          while (System.currentTimeMillis() - start < 5000 && capturer.receivedRef.isEmpty) Thread.sleep(50)
          capturer.receivedRef match {
            case Some(ref) => assertEquals(resultCF(ref ? 7), "B: 7")
            case None => fail("sender actor never received the remote Ref")
          }
        case other => fail(s"Unexpected AskForRefAsync response: $other")
      }
    } finally {
      scala.util.Try(close(capturer)); scala.util.Try(close(actorOnB)); scala.util.Try(close(a)); scala.util.Try(close(b))
    }
  }

  test("cross-system actor-id collision: a path naming the peer's system reaches the peer's actor, not self") {
    val (a, b) = crossRegistered()
    val gotOnA = SourceSignal(0)
    val gotOnB = SourceSignal(0)
    val xa = newActorOn(a, "sameid", { case (msg, _) => gotOnA.mutate(_ + 1); Some("A-x") })
    val xb = newActorOn(b, "sameid", { case (msg, _) => gotOnB.mutate(_ + 1); Some("B-x") })
    try {
      awaitRef(a, "sameid")
      awaitRef(b, "sameid")
      xa.bang(42, ActorPath.Remote("B", "sameid"), "")
      Thread.sleep(500)
      assertEquals(gotOnA.currentValue.getOrElse(0), 0, "message was delivered to the sending actor itself")
      assert(waitFor(gotOnB, 1), "message was not delivered to the peer's actor")
    } finally {
      scala.util.Try(close(xa)); scala.util.Try(close(xb)); scala.util.Try(close(a)); scala.util.Try(close(b))
    }
  }

  // ============================================================================
  // 5. System lifecycle and cleanup
  // ============================================================================

  test("closing a system makes its peers unregister it") {
    val (a, b) = crossRegistered()
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"A: $msg") }
    val actorOnA = newActorOn(a, "onA", behavior)
    try {
      awaitRef(a, "onA")
      Await.result(b ? b.SystemMsg.AskForRef("onA", "A"), 5.seconds) match {
        case b.SystemMsg.Ref(_) => () // the peer is reachable before shutdown
        case other => fail(s"Unexpected AskForRef response: $other")
      }
      close(a)
      // the peer processes UnregisterSystem on its next heartbeat
      val start = System.currentTimeMillis()
      var rsp: Option[scala.util.Try[b.SystemMsg]] = None
      while (rsp.forall(_.isSuccess) && System.currentTimeMillis() - start < 5000) {
        rsp = Some(scala.util.Try(Await.result(b ? b.SystemMsg.AskForRef("onA", "A"), 1.second)))
        if (rsp.forall(_.isSuccess)) Thread.sleep(50)
      }
      rsp match {
        case Some(scala.util.Failure(t: IllegalArgumentException)) => ()
        case other => fail(s"expected IllegalArgumentException failure after the peer closed, got $other")
      }
    } finally {
      scala.util.Try(close(actorOnA)); scala.util.Try(close(b))
    }
  }

  test("a new system can register under the id of a closed system") {
    val (a, b) = crossRegistered()
    var a2: Option[ActorSystem[Int, String, Int]] = None
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"A2: $msg") }
    try {
      close(a)
      val newA = newSystem("A")
      a2 = Some(newA)
      awaitCF(b ? b.SystemMsg.RegisterSystem(newA))
      val actorOnA2 = newActorOn(newA, "onA2", behavior)
      // no awaitRef here: the lookup must queue behind the pending Register
      Await.result(b ? b.SystemMsg.AskForRef("onA2", "A"), 5.seconds) match {
        case b.SystemMsg.Ref(ref) => assertEquals(resultCF(ref ? 1), "A2: 1")
        case other => fail(s"Unexpected AskForRef response: $other")
      }
    } finally {
      a2.foreach(s => scala.util.Try(close(s))); scala.util.Try(close(b))
    }
  }

  test("UnregisterSystem removes the peer and makes it unroutable") {
    val (a, b) = crossRegistered()
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"A: $msg") }
    val actorOnA = newActorOn(a, "onA3", behavior)
    try {
      awaitRef(a, "onA3")
      Await.result(b ? b.SystemMsg.AskForRef("onA3", "A"), 5.seconds) match {
        case b.SystemMsg.Ref(_) => ()
        case other => fail(s"Unexpected AskForRef response: $other")
      }
      assertEquals(resultCF(b ? b.SystemMsg.UnregisterSystem("A")), b.SystemMsg.Done)
      val start = System.currentTimeMillis()
      var rsp: Option[scala.util.Try[b.SystemMsg]] = None
      while (rsp.forall(_.isSuccess) && System.currentTimeMillis() - start < 5000) {
        rsp = Some(scala.util.Try(Await.result(b ? b.SystemMsg.AskForRef("onA3", "A"), 1.second)))
        if (rsp.forall(_.isSuccess)) Thread.sleep(50)
      }
      rsp match {
        case Some(scala.util.Failure(t: IllegalArgumentException)) => ()
        case other => fail(s"expected IllegalArgumentException failure after UnregisterSystem, got $other")
      }
    } finally {
      scala.util.Try(close(actorOnA)); scala.util.Try(close(a)); scala.util.Try(close(b))
    }
  }

  // ============================================================================
  // 6. Stale refs
  // ============================================================================

  test("a RemoteActorRef to a closed actor fails on ask and drops on bang, without hanging") {
    val (a, b) = crossRegistered()
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"B: $msg") }
    val actorOnB = newActorOn(b, "doomed", behavior)
    try {
      awaitRef(b, "doomed")
      val ref = Await.result(a ? a.SystemMsg.AskForRef("doomed", "B"), 5.seconds) match {
        case a.SystemMsg.Ref(ref) => ref
        case other => fail(s"Unexpected AskForRef response: $other")
      }
      assertEquals(resultCF(ref ? 1), "B: 1") // works before close
      close(actorOnB)
      // ActorClosed propagates to the registry on the next heartbeat
      val start = System.currentTimeMillis()
      var failed = false
      while (!failed && System.currentTimeMillis() - start < 5000) {
        val cf = ref ? 1
        Await.ready(cf.future, 1.second)
        failed = cf.future.value.exists(_.isFailure)
        if (!failed) Thread.sleep(50)
      }
      assert(failed, "ask through a stale RemoteActorRef should fail after the actor closed")
      ref ! 2 // bang returns normally even though the actor is gone
    } finally {
      scala.util.Try(close(actorOnB)); scala.util.Try(close(a)); scala.util.Try(close(b))
    }
  }

  // ============================================================================
  // 7. Behavior ids through refs and paths
  // ============================================================================

  test("behavior ids are honored through a LocalActorRef") {
    val sys = newSystem("sys")
    val receivedUpper = SourceSignal(0)
    val upper: Actor.PF[Int, String, Int] = { case (msg, _) => receivedUpper.mutate(_ + 1); Some(s"U:$msg") }
    val lower: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"l:$msg") }
    val multi = ActorBuilder[Int, String, Int]()
      .withId("multi").withState(0)
      .withBehavior("upper", upper).withBehavior("lower", lower)
      .withSystem(sys).build()
    try {
      val ref = awaitRef(sys, "multi")
      assertEquals(resultCF(ref ? (1, "upper")), "U:1")
      assertEquals(resultCF(ref ? (1, "lower")), "l:1")
      ref ! (1, "upper")
      assert(waitFor(receivedUpper, 2), "bang with a behavior id was not processed by that behavior")
    } finally {
      scala.util.Try(close(multi)); scala.util.Try(close(sys))
    }
  }

  test("behavior ids are honored through a RemoteActorRef and through path-based routing") {
    val (a, b) = crossRegistered()
    val receivedUpper = SourceSignal(0)
    val upper: Actor.PF[Int, String, Int] = { case (msg, _) => receivedUpper.mutate(_ + 1); Some(s"U:$msg") }
    val lower: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"l:$msg") }
    val multi = ActorBuilder[Int, String, Int]()
      .withId("multiB").withState(0)
      .withBehavior("upper", upper).withBehavior("lower", lower)
      .withSystem(b).build()
    try {
      awaitRef(b, "multiB")
      Await.result(a ? a.SystemMsg.AskForRef("multiB", "B"), 5.seconds) match {
        case a.SystemMsg.Ref(ref) =>
          assertEquals(resultCF(ref ? (1, "upper")), "U:1")
          assertEquals(resultCF(ref ? (1, "lower")), "l:1")
        case other => fail(s"Unexpected AskForRef response: $other")
      }
      // path-based ask with a behavior id, remote and local paths
      assertEquals(resultCF(a.ask(1, ActorPath.Remote("B", "multiB"), "upper")), "U:1")
      assertEquals(resultCF(b.ask(1, ActorPath.Local("multiB"), "lower")), "l:1")
      // path-based bang with a behavior id
      a.bang(1, ActorPath.Remote("B", "multiB"), "upper")
      assert(waitFor(receivedUpper, 3), "path-based bang with a behavior id was not processed by that behavior")
    } finally {
      scala.util.Try(close(multi)); scala.util.Try(close(a)); scala.util.Try(close(b))
    }
  }

  // ============================================================================
  // 8. RemoteSystemMsg handling
  // ============================================================================

  test("asking a system with an unhandled RemoteSystemMsg fails") {
    val sys = newSystem("sys")
    try {
      val cf = sys ? RemoteSystem.RemoteSystemMsg.Done
      Await.ready(cf.future, 5.seconds)
      cf.future.value match {
        case Some(scala.util.Failure(t: IllegalArgumentException)) => ()
        case other => fail(s"expected IllegalArgumentException failure, got $other")
      }
    } finally {
      scala.util.Try(close(sys))
    }
  }

  test("SystemClosed via ask unregisters the peer system and returns Done") {
    val (a, b) = crossRegistered()
    try {
      assertEquals(resultCF(a ? RemoteSystem.RemoteSystemMsg.SystemClosed(b.id)), RemoteSystem.RemoteSystemMsg.Done)
      val cf = a ? a.SystemMsg.AskForRef("anything", "B")
      Await.ready(cf.future, 5.seconds)
      assert(cf.future.value.exists(_.isFailure), s"expected failure after SystemClosed, got ${cf.future.value}")
    } finally {
      scala.util.Try(close(a)); scala.util.Try(close(b))
    }
  }

  test("banging a system with a non-SystemClosed RemoteSystemMsg is ignored and harmless") {
    val sys = newSystem("sys")
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"A: $msg") }
    try {
      sys ! RemoteSystem.RemoteSystemMsg.AskForRef("whatever")
      val a = newActorOn(sys, "a", behavior)
      val ref = awaitRef(sys, "a")
      assertEquals(resultCF(ref ? 1), "A: 1")
      close(a)
    } finally {
      scala.util.Try(close(sys))
    }
  }

  // ============================================================================
  // 9. Message-loss policy for invalid ids
  // ============================================================================

  test("bang to a missing actor or system is silently dropped") {
    val sys = newSystem("sys")
    try {
      sys.bang(42, ActorPath.Remote("sys", "missing"), "")
      sys.bang(42, ActorPath.Local("missing"), "")
      sys.bang(42, ActorPath.Remote("UNKNOWN", "missing"), "")
    } finally {
      scala.util.Try(close(sys))
    }
  }

  test("ask to a missing actor on the own system fails without hanging") {
    val sys = newSystem("sys")
    try {
      val cfRemote = sys.ask(42, ActorPath.Remote("sys", "missing"), "")
      val cfLocal = sys.ask(42, ActorPath.Local("missing"), "")
      Await.ready(cfRemote.future, 5.seconds)
      Await.ready(cfLocal.future, 5.seconds)
      assert(cfRemote.future.value.exists(_.isFailure), s"expected failure, got ${cfRemote.future.value}")
      assert(cfLocal.future.value.exists(_.isFailure), s"expected failure, got ${cfLocal.future.value}")
    } finally {
      scala.util.Try(close(sys))
    }
  }

  // ============================================================================
  // 10. Concurrency
  // ============================================================================

  test("concurrent cross-system AskForRef resolves all actors while registration is in flight") {
    val (a, b) = crossRegistered()
    val numActors = 30
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"B:$msg") }
    val actors = (0 until numActors).map(i => newActorOn(b, s"s$i", behavior))
    try {
      val futures = (0 until numActors).map { i =>
        Future {
          Await.result(a ? a.SystemMsg.AskForRef(s"s$i", "B"), 20.seconds) match {
            case a.SystemMsg.Ref(ref) => assertEquals(resultCF(ref ? i), s"B:$i")
            case other => fail(s"Unexpected AskForRef response for s$i: $other")
          }
        }
      }
      Await.result(Future.sequence(futures), 60.seconds)
    } finally {
      actors.foreach(actor => scala.util.Try(close(actor)))
      scala.util.Try(close(a)); scala.util.Try(close(b))
    }
  }

  // ============================================================================
  // 11. Deep registration across spawn
  // ============================================================================

  test("a grandchild spawned on the peer is discoverable through the cross-system registry") {
    val (a, b) = crossRegistered()
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"G:$msg") }
    try {
      val child = Await.result(b ? b.SystemMsg.Spawn(actorId = "kid"), 5.seconds) match {
        case b.SystemMsg.NewChild(child) => child
        case other => fail(s"Unexpected spawn response: $other")
      }
      val grandchild = Await.result(child ? child.SystemMsg.Spawn(actorId = "grandkid", behaviors = List("default" -> behavior)), 5.seconds) match {
        case child.SystemMsg.NewChild(grandchild) => grandchild
        case other => fail(s"Unexpected spawn response: $other")
      }
      // no awaitRef here: the lookup must queue behind the pending Register
      Await.result(a ? a.SystemMsg.AskForRef("grandkid", "B"), 5.seconds) match {
        case a.SystemMsg.Ref(ref) => assertEquals(resultCF(ref ? 1), "G:1")
        case other => fail(s"Unexpected AskForRef response: $other")
      }
      close(grandchild); close(child)
    } finally {
      scala.util.Try(close(a)); scala.util.Try(close(b))
    }
  }
}
