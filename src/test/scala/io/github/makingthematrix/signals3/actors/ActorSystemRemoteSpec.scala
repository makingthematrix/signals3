package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.testutils.*
import io.github.makingthematrix.signals3.*
import io.github.makingthematrix.signals3.actors.Actor.InvalidIdException
import io.github.makingthematrix.signals3.actors.ActorSystem.InvalidSystemIdException
import munit.FunSuite

import scala.concurrent.{Future, TimeoutException}
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

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
        tryResultCF(sys ? AskForRef(id)) match {
          case Success(Ref(ref))                 => return ref
          case Failure(InvalidIdException(`id`)) => Thread.sleep(50)
          case other                             => fail(s"Unexpected response: $other")
        }
      } catch {
        case _: TimeoutException => Thread.sleep(50)
      }
    }
    fail(s"Actor '$id' was not registered within 5 seconds")
  }

  /** Two cross-registered systems: each one holds the other in its `systems` map. */
  private def crossRegistered(systemAId: String = "A", systemBId: String = "B"): (ActorSystem[Int, String, Int], ActorSystem[Int, String, Int]) = {
    val a = newSystem(systemAId)
    val b = newSystem(systemBId)
    awaitCF(a ? a.SystemMsg.RegisterSystem(b))
    awaitCF(b ? b.SystemMsg.RegisterSystem(a))
    (a, b)
  }

  /** An actor that captures the Ref delivered by AskForRefAsync in a system message. */
  private class CapturingActorImpl(sys: ActorSystem[Int, String, Int])(using ec: scala.concurrent.ExecutionContext)
    extends ActorImpl[Int, String, Int]("capturing", 0, Actor.defBeat, None, Some(sys)) {
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
      Try(close(sys))
    }
  }

  test("ask to a nonexistent actor addressed by the system's own id fails, not recursed") {
    val sys = newSystem("sys")
    try {
      val cf = sys.ask(42, ActorPath.Remote("sys", "nonexistent"), "")
      awaitCF(cf)
      assert(cf.future.value.exists(_.isFailure), s"expected failure, got ${cf.future.value}")
    } catch {
      case e: StackOverflowError => fail(s"StackOverflowError: ${e.getStackTrace.take(5).mkString(" | ")}")
    } finally {
      Try(close(sys))
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
      Try(close(a)); Try(close(b))
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

    tryResultCF(sys ? Spawn(actorId = "c", behaviors = List("default" -> behavior))) match {
      case Success(NewChild(child)) =>
        tryResultCF(sys ? AskForRef("c")) match {
          case Success(Ref(ref)) =>
            ref ! 42
            assert(waitFor(received, 1), "message sent through a fresh ref was dropped")
            close(child)
          case other => fail(s"Unexpected AskForRef response: $other")
        }
      case other => fail(s"Unexpected spawn response: $other")
    }

    Try(close(sys))
  }

  test("a Local path sent from inside a behavior is routed through the system") {
    val sys = newSystem("sys")
    val received = SourceSignal(0)
    val senderBehavior: Actor.PF[Int, String, Int] = { case (msg, a) => a.bang(msg, ActorPath.Local("target"), ""); Some("sent") }
    val targetBehavior: Actor.PF[Int, String, Int] = { case (msg, _) => received.mutate(_ + 1); Some(s"T: $msg") }

    val sender = newActorOn(sys, "sender", senderBehavior)
    val target = newActorOn(sys, "target", targetBehavior)
    awaitRef(sys, "sender")
    awaitRef(sys, "target")
    tryResultCF(sys ? sys.SystemMsg.AskForRef("sender")) match {
      case Success(sys.SystemMsg.Ref(senderRef)) =>
        senderRef ! 42
        assert(waitFor(received, 1), "message sent via a Local path from a behavior was not delivered")
      case other => fail(s"Unexpected AskForRef response: $other")
    }
    close(sender); close(target)
    Try(close(sys))
  }

  // ============================================================================
  // 3. Failure policy for invalid ids
  // ============================================================================

  test("AskForRef with an unknown system id fails with IllegalArgumentException") {
    val (a, b) = crossRegistered()
    val cf: CloseableFuture[a.SystemMsg] = a ? a.SystemMsg.AskForRef("someActor", "UNKNOWN")
    cf.onComplete {
      case Failure(InvalidSystemIdException("UNKNOWN")) => ()
      case other => fail(s"expected InvalidSystemIdException failure, got $other")
    }
    awaitCF(cf)
    Try(close(a)); Try(close(b))
  }

  test("AskForRefAsync with an unknown system id completes the asker with a failure instead of hanging") {
    val (a, b) = crossRegistered()
    val capturer = new CapturingActorImpl(a)
    capturer.initialize()
    tryResultCF(a ? a.SystemMsg.AskForRefAsync(capturer, "someActor", "UNKNOWN")) match {
      case Failure(InvalidSystemIdException("UNKNOWN")) => ()
      case Success(m) => fail(s"expected failure, got $m")
      case _ => fail("AskForRefAsync with unknown system id never completes")
    }
    Try(close(capturer)); Try(close(a)); Try(close(b))
  }

  test("local AskForRef with an unknown actor id returns the InvalidIdException") {
    val sys = newSystem("sys")
    tryResultCF(sys ? sys.SystemMsg.AskForRef("nonexistent")) match {
      case Failure(InvalidIdException("nonexistent")) => ()
      case other => fail(s"Expected InvalidId, got $other")
    }
    Try(close(sys))
  }

  test("cross-system AskForRef for an actor missing on the peer fails with InvalidIdException") {
    val (a, b) = crossRegistered()
    tryResultCF(a ? a.SystemMsg.AskForRef("nonexistent", "B")) match {
      case Failure(InvalidIdException("nonexistent")) => ()
      case other => fail(s"expected InvalidIdException failure, got $other")
    }
    Try(close(a)); Try(close(b))
  }

  // ============================================================================
  // 4. Cross-system communication
  // ============================================================================

  test("cross-system AskForRef returns a usable RemoteActorRef") {
    val (a, b) = crossRegistered()
    val received = SourceSignal(0)
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => received.mutate(_ + 1); Some(s"B: $msg") }
    val actorOnB = newActorOn(b, "onB", behavior)

    awaitRef(b, "onB")
    tryResultCF(a ? a.SystemMsg.AskForRef("onB", "B")) match {
      case Success(a.SystemMsg.Ref(ref)) =>
        assert(!ref.isLocal)
        assertEquals(ref.path, ActorPath.Remote("B", "onB"))
        ref ! 42
        assert(waitFor(received, 1), "cross-system bang was not delivered")
        assertEquals(resultCF(ref ? 43), "B: 43")
      case other => fail(s"Unexpected AskForRef response: $other")
    }
    Try(close(actorOnB)); Try(close(a)); Try(close(b))
  }

  test("cross-system AskForRef immediately after actor creation on the peer returns a usable ref") {
    val (a, b) = crossRegistered()
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"B: $msg") }
    val actorOnB = newActorOn(b, "fresh", behavior)
    // no awaitRef here: the lookup must queue behind the peer's pending Register
    tryResultCF(a ? a.SystemMsg.AskForRef("fresh", "B")) match {
      case Success(a.SystemMsg.Ref(ref)) => assertEquals(resultCF(ref ? 7), "B: 7")
      case other => fail(s"Unexpected AskForRef response: $other")
    }
    Try(close(actorOnB)); Try(close(a)); Try(close(b))
  }

  test("a child spawned on the peer is immediately discoverable via cross-system AskForRef") {
    val (a, b) = crossRegistered()
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"B: $msg") }
    tryResultCF(b ? b.SystemMsg.Spawn(actorId = "spawned", behaviors = List("default" -> behavior))) match {
      case Success(b.SystemMsg.NewChild(child)) =>
        tryResultCF(a ? a.SystemMsg.AskForRef("spawned", "B")) match {
          case Success(a.SystemMsg.Ref(ref)) =>
            assertEquals(resultCF(ref ? 1), "B: 1")
            close(child)
          case other => fail(s"Unexpected AskForRef response: $other")
        }
      case other => fail(s"Unexpected spawn response: $other")
      }
    Try(close(a)); Try(close(b))
  }

  test("cross-system AskForRefAsync delivers a usable Ref to the sender actor") {
    val (a, b) = crossRegistered()
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"B: $msg") }
    val actorOnB = newActorOn(b, "onB2", behavior)
    val capturer = new CapturingActorImpl(a)
    capturer.initialize()
    awaitRef(a, "capturing")
    tryResultCF(a ? a.SystemMsg.AskForRefAsync(capturer, "onB2", "B")) match {
      case Success(a.SystemMsg.Done) =>
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5000 && capturer.receivedRef.isEmpty) Thread.sleep(50)
        capturer.receivedRef match {
          case Some(ref) => assertEquals(resultCF(ref ? 7), "B: 7")
          case None => fail("sender actor never received the remote Ref")
        }
      case other => fail(s"Unexpected AskForRefAsync response: $other")
    }
    Try(close(capturer)); Try(close(actorOnB)); Try(close(a)); Try(close(b))
  }

  test("cross-system actor-id collision: a path naming the peer's system reaches the peer's actor, not self") {
    val (a, b) = crossRegistered()
    val gotOnA = SourceSignal(0)
    val gotOnB = SourceSignal(0)
    val xa = newActorOn(a, "sameid", { case (_, _) => gotOnA.mutate(_ + 1); Some("A-x") })
    val xb = newActorOn(b, "sameid", { case (_, _) => gotOnB.mutate(_ + 1); Some("B-x") })

    awaitRef(a, "sameid")
    awaitRef(b, "sameid")
    xa.bang(42, ActorPath.Remote("B", "sameid"), "")
    Thread.sleep(500)
    assertEquals(gotOnA.currentValue.getOrElse(0), 0, "message was delivered to the sending actor itself")
    assert(waitFor(gotOnB, 1), "message was not delivered to the peer's actor")

    Try(close(xa)); Try(close(xb)); Try(close(a)); Try(close(b))
  }

  // ============================================================================
  // 5. System lifecycle and cleanup
  // ============================================================================

  test("closing a system makes its peers unregister it") {
    val (a, b) = crossRegistered()
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"A: $msg") }
    val actorOnA = newActorOn(a, "onA", behavior)

    awaitRef(a, "onA")
    tryResultCF(b ? b.SystemMsg.AskForRef("onA", "A")) match {
      case Success(b.SystemMsg.Ref(_)) => () // the peer is reachable before shutdown
      case other => fail(s"Unexpected AskForRef response: $other")
    }
    close(a)
    // the peer processes UnregisterSystem on its next heartbeat
    val start = System.currentTimeMillis()
    var rsp: Option[Try[b.SystemMsg]] = None
    while (rsp.forall(_.isSuccess) && System.currentTimeMillis() - start < 5000) {
      rsp = Some(tryResultCF(b ? b.SystemMsg.AskForRef("onA", "A")))
      if (rsp.forall(_.isSuccess)) Thread.sleep(50)
    }
    rsp match {
      case Some(Failure(_: IllegalArgumentException)) => ()
      case other => fail(s"expected IllegalArgumentException failure after the peer closed, got $other")
    }

    Try(close(actorOnA)); Try(close(b))
  }

  test("a new system can register under the id of a closed system") {
    val (a, b) = crossRegistered()
    var a2: Option[ActorSystem[Int, String, Int]] = None
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"A2: $msg") }

    close(a)
    val newA = newSystem("A")
    a2 = Some(newA)
    awaitCF(b ? b.SystemMsg.RegisterSystem(newA))
    newActorOn(newA, "onA2", behavior)
    // no awaitRef here: the lookup must queue behind the pending Register
    tryResultCF(b ? b.SystemMsg.AskForRef("onA2", "A")) match {
      case Success(b.SystemMsg.Ref(ref)) => assertEquals(resultCF(ref ? 1), "A2: 1")
      case other => fail(s"Unexpected AskForRef response: $other")
    }
    a2.foreach(s => Try(close(s))); Try(close(b))
  }

  test("UnregisterSystem removes the peer and makes it unroutable") {
    val (a, b) = crossRegistered()
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"A: $msg") }
    val actorOnA = newActorOn(a, "onA3", behavior)

    awaitRef(a, "onA3")
    tryResultCF[b.SystemMsg](b ? b.SystemMsg.AskForRef("onA3", "A")) match {
      case Success(b.SystemMsg.Ref(_)) => ()
      case other => fail(s"Unexpected AskForRef response: $other")
    }
    assertEquals(resultCF(b ? b.SystemMsg.UnregisterSystem("A")), b.SystemMsg.Done)
    val start = System.currentTimeMillis()
    var rsp: Option[Try[b.SystemMsg]] = None
    while (rsp.forall(_.isSuccess) && System.currentTimeMillis() - start < 5000) {
      rsp = Some(tryResultCF(b ? b.SystemMsg.AskForRef("onA3", "A")))
      if (rsp.forall(_.isSuccess)) Thread.sleep(50)
    }
    rsp match {
      case Some(Failure(_: IllegalArgumentException)) => ()
      case other => fail(s"expected IllegalArgumentException failure after UnregisterSystem, got $other")
    }

    Try(close(actorOnA)); Try(close(a)); Try(close(b))
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

    awaitRef(b, "multiB")
    tryResultCF(a ? a.SystemMsg.AskForRef("multiB", "B")) match {
      case Success(a.SystemMsg.Ref(ref)) =>
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

    Try(close(multi)); Try(close(a)); Try(close(b))
  }

  // ============================================================================
  // 8. RemoteSystemMsg handling
  // ============================================================================

  test("asking a system with an unhandled RemoteSystemMsg fails") {
    val sys = newSystem("sys")
    val cf = sys ? RemoteSystem.RemoteSystemMsg.Done
    cf.onComplete {
      case Failure(_: IllegalArgumentException) => ()
      case other => fail(s"expected IllegalArgumentException failure, got $other")
    }
    awaitCF(cf)

    Try(close(sys))
  }

  test("banging a system with a non-SystemClosed RemoteSystemMsg is ignored and harmless") {
    val sys = newSystem("sys")
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"A: $msg") }
    sys ! RemoteSystem.RemoteSystemMsg.AskForRef("whatever")
    val a = newActorOn(sys, "a", behavior)
    val ref = awaitRef(sys, "a")
    assertEquals(resultCF(ref ? 1), "A: 1")
    close(a)

    Try(close(sys))
  }

  // ============================================================================
  // 9. Message-loss policy for invalid ids
  // ============================================================================

  test("bang to a missing actor or system is silently dropped") {
    val sys = newSystem("sys")
    sys.bang(42, ActorPath.Remote("sys", "missing"), "")
    sys.bang(42, ActorPath.Local("missing"), "")
    sys.bang(42, ActorPath.Remote("UNKNOWN", "missing"), "")

    Try(close(sys))
  }

  test("ask to a missing actor on the own system fails without hanging") {
    val sys = newSystem("sys")
    val cfRemote = sys.ask(42, ActorPath.Remote("sys", "missing"), "")
    val cfLocal = sys.ask(42, ActorPath.Local("missing"), "")
    awaitCF(cfRemote)
     awaitCF(cfLocal)
    assert(cfRemote.future.value.exists(_.isFailure), s"expected failure, got ${cfRemote.future.value}")
    assert(cfLocal.future.value.exists(_.isFailure), s"expected failure, got ${cfLocal.future.value}")

    Try(close(sys))
  }

  // ============================================================================
  // 10. Concurrency
  // ============================================================================

  test("concurrent cross-system AskForRef resolves all actors while registration is in flight") {
    val (a, b) = crossRegistered()
    val numActors = 30
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"B:$msg") }
    val actors = (0 until numActors).map(i => newActorOn(b, s"s$i", behavior))
    val futures = (0 until numActors).map { i =>
      Future {
        tryResultCF(a ? a.SystemMsg.AskForRef(s"s$i", "B"))(using 20.seconds) match {
          case Success(a.SystemMsg.Ref(ref)) => assertEquals(resultCF(ref ? i), s"B:$i")
          case other => fail(s"Unexpected AskForRef response for s$i: $other")
        }
      }
    }
    await(Future.sequence(futures))(using 60.seconds)

    actors.foreach(actor => Try(close(actor)))
    Try(close(a)); Try(close(b))
  }

  // ============================================================================
  // 11. Deep registration across spawn
  // ============================================================================

  test("a grandchild spawned on the peer is discoverable through the cross-system registry") {
    val (a, b) = crossRegistered()
    val behavior: Actor.PF[Int, String, Int] = { case (msg, _) => Some(s"G:$msg") }
    val child = tryResultCF(b ? b.SystemMsg.Spawn(actorId = "kid")) match {
      case Success(b.SystemMsg.NewChild(child)) => child
      case other => fail(s"Unexpected spawn response: $other")
    }
    val grandchild = tryResultCF(child ? child.SystemMsg.Spawn(actorId = "grandkid", behaviors = List("default" -> behavior))) match {
      case Success(child.SystemMsg.NewChild(grandchild)) => grandchild
      case other => fail(s"Unexpected spawn response: $other")
    }
    // no awaitRef here: the lookup must queue behind the pending Register
    tryResultCF(a ? a.SystemMsg.AskForRef("grandkid", "B")) match {
      case Success(a.SystemMsg.Ref(ref)) => assertEquals(resultCF(ref ? 1), "G:1")
      case other => fail(s"Unexpected AskForRef response: $other")
    }
    close(grandchild); close(child)

    Try(close(a)); Try(close(b))
  }
}
