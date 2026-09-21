package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.testutils.*
import io.github.makingthematrix.signals3.*
import munit.FunSuite

import scala.concurrent.duration.*
import scala.concurrent.Await

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
  private class CapturingActor(sys: ActorSystem[Int, String, Int])(using ec: scala.concurrent.ExecutionContext)
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
    val capturer = new CapturingActor(a)
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
    val capturer = new CapturingActor(a)
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
}
