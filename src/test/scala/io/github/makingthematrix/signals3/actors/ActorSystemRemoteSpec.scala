package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.*
import Actor.{InvalidIdException, PF, UnhandledMsgException}
import SystemMsg.*
import io.github.makingthematrix.signals3.Utils.*
import actors.Utils.*
import munit.FunSuite

import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

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
  // 1. Routing to invalid ids must not recurse
  // ============================================================================

  test("cross-system bang to a nonexistent actor on the peer is dropped, not recursed") {
    val (a, b) = crossRegistered()
    a.tell("42", ActorPath.Remote("B", "nonexistent"), "")
    Thread.sleep(300)
    close(a, b)
  }

  // ============================================================================
  // 2. Local delivery through refs and paths
  // ============================================================================

  test("a ref obtained via AskForRef delivers immediately after spawn, without waiting for a heartbeat") {
    val sys = newSystem[String]("sys")
    val received = SourceSignal(0)
    val behavior: PF[String, Int] = { case (msg, _) => received.mutate(_ + 1); Some(s"C: $msg") }

    val child = spawn(sys)(Spawn(actorId = "c", behaviors = List("default" -> behavior)))
    resultCF(sys ? AskForRef("c")) match {
      case Ref(ref) =>
        ref.asInstanceOf[ActorRef[String]] ! "42"
        assert(waitFor(received, 1), "message sent through a fresh ref was dropped")
      case other => fail(s"Unexpected AskForRef response: $other")
    }
    close(child)
    close(sys)
  }

  test("a Local path sent from inside a behavior is routed through the system") {
    val sys = newSystem[String]("sys")
    val received = SourceSignal(0)
    val senderBehavior: PF[String, Int] = { case (msg, a) => a.tell(msg, ActorPath.Local("target"), ""); Some("sent") }
    val targetBehavior: PF[String, Int] = { case (msg, _) => received.mutate(_ + 1); Some(s"T: $msg") }

    val sender = newActor(sys, "sender", senderBehavior)
    val target = newActor(sys, "target", targetBehavior)
    awaitRef(sys, "target")
    val senderRef = awaitRef(sys, "sender")
    senderRef ! "42"
    assert(waitFor(received, 1), "message sent via a Local path from a behavior was not delivered")
    close(sender, target, sys)
  }

  // ============================================================================
  // 3. Failure policy for invalid ids
  // ============================================================================

  test("AskForRef with an unknown system id fails with IllegalArgumentException") {
    val (a, b) = crossRegistered()
    expectFailureCF(a ? AskForRef("someActor", "UNKNOWN"), InvalidIdException("UNKNOWN"))
    close(a, b)
  }

  test("AskForRefAsync with an unknown system id completes the asker with a failure instead of hanging") {
    val (a, b) = crossRegistered()
    val capturer = new CapturingActor(a)
    capturer.initialize()
    expectFailureCF(a ? AskForRefAsync(capturer, "someActor", "UNKNOWN"), InvalidIdException("UNKNOWN"))
    close(capturer, a, b)
  }

  test("cross-system AskForRef for an actor missing on the peer fails with InvalidIdException") {
    val (a, b) = crossRegistered()
    expectFailureCF(a ? AskForRef("nonexistent", "B"), InvalidIdException("nonexistent"))
    close(a, b)
  }

  // ============================================================================
  // 4. Cross-system communication
  // ============================================================================

  test("cross-system AskForRef returns a usable RemoteActorRef") {
    val (a, b) = crossRegistered()
    val received = SourceSignal(0)
    val behavior: PF[String, Int] = { case (msg, _) => received.mutate(_ + 1); Some(s"B: $msg") }
    val actorOnB = newActor(b, "onB", behavior)

    awaitRef(b, "onB")
    val ref = remoteRef(a, "onB", "B")
    assert(!ref.isLocal)
    assertEquals(ref.path, ActorPath.Remote("B", "onB"))
    ref ! "42"
    assert(waitFor(received, 1), "cross-system bang was not delivered")
    expectSuccessCF(ref ? "43", "B: 43")
    close(actorOnB, a, b)
  }

  test("a child spawned on the peer is immediately discoverable via cross-system AskForRef") {
    val (a, b) = crossRegistered()
    val behavior: PF[String, Int] = { case (msg, _) => Some(s"B: $msg") }
    val child = spawn(b)(Spawn(actorId = "spawned", behaviors = List("default" -> behavior)))
    resultCF(a ? AskForRef("spawned", "B")) match {
      case Ref(ref) => expectSuccessCF(ref.asInstanceOf[ActorRef[String]] ? "1", "B: 1")
      case other    => fail(s"Unexpected AskForRef response: $other")
    }
    close(child)
    close(a, b)
  }

  test("cross-system AskForRefAsync delivers a usable Ref to the sender actor") {
    val (a, b) = crossRegistered()
    val behavior: PF[String, Int] = { case (msg, _) => Some(s"B: $msg") }
    val actorOnB = newActor(b, "onB2", behavior)
    val capturer = new CapturingActor(a)
    capturer.initialize()
    expectSuccessCF(a ? AskForRefAsync(capturer, "onB2", "B"), Done)
    val ref = awaitCaptured(capturer)
    expectSuccessCF(ref ? "7", "B: 7")
    close(capturer, actorOnB, a, b)
  }

  test("cross-system actor-id collision: a path naming the peer's system reaches the peer's actor, not self") {
    val (a, b) = crossRegistered()
    val gotOnA = SourceSignal(0)
    val gotOnB = SourceSignal(0)
    val xa = newActor(a, "sameid", { case (_, _) => gotOnA.mutate(_ + 1); Some("A-x") })
    val xb = newActor(b, "sameid", { case (_, _) => gotOnB.mutate(_ + 1); Some("B-x") })

    awaitRef(a, "sameid")
    awaitRef(b, "sameid")
    xa.tell("42", ActorPath.Remote("B", "sameid"), "")
    Thread.sleep(500)
    assertEquals(gotOnA.currentValue.getOrElse(0), 0, "message was delivered to the sending actor itself")
    assert(waitFor(gotOnB, 1), "message was not delivered to the peer's actor")

    close(xa, xb, a, b)
  }

  // ============================================================================
  // 5. System lifecycle and cleanup
  // ============================================================================

  test("closing a system makes its peers unregister it") {
    val (a, b) = crossRegistered()
    val behavior: PF[String, Int] = { case (msg, _) => Some(s"A: $msg") }
    val actorOnA = newActor(a, "onA", behavior)

    awaitRef(a, "onA")
    resultCF(b ? AskForRef("onA", "A")) match {
      case Ref(_) => () // the peer is reachable before shutdown
      case other  => fail(s"Unexpected AskForRef response: $other")
    }
    close(a)
    // the peer processes UnregisterSystem on its next heartbeat
    val start = System.currentTimeMillis()
    while (tryResultCF(b ? AskForRef("onA", "A")).isSuccess && System.currentTimeMillis() - start < 5000)
      Thread.sleep(50)
    expectFailureCF(b ? AskForRef("onA", "A"), InvalidIdException("A"))

    close(actorOnA, b)
  }

  test("a new system can register under the id of a closed system") {
    val (a, b) = crossRegistered()
    val behavior: PF[String, Int] = { case (msg, _) => Some(s"A2: $msg") }

    close(a)
    val newA = newSystem[String]("A")
    awaitCF(b ? RegisterSystem(newA))
    newActor(newA, "onA2", behavior)
    // no awaitRef here: the lookup must queue behind the pending Register
    resultCF(b ? AskForRef("onA2", "A")) match {
      case Ref(ref) => expectSuccessCF(ref.asInstanceOf[ActorRef[String]] ? "1", "A2: 1")
      case other    => fail(s"Unexpected AskForRef response: $other")
    }
    close(newA, b)
  }

  test("UnregisterSystem removes the peer and makes it unroutable") {
    val (a, b) = crossRegistered()
    val behavior: PF[String, Int] = { case (msg, _) => Some(s"A: $msg") }
    val actorOnA = newActor(a, "onA3", behavior)

    awaitRef(a, "onA3")
    resultCF(b ? AskForRef("onA3", "A")) match {
      case Ref(_) => () // the peer is reachable before the unregistration
      case other  => fail(s"Unexpected AskForRef response: $other")
    }
    expectSuccessCF(b ? UnregisterSystem("A"), Done)
    // the unregistration is processed asynchronously
    val start = System.currentTimeMillis()
    while (tryResultCF(b ? AskForRef("onA3", "A")).isSuccess && System.currentTimeMillis() - start < 5000)
      Thread.sleep(50)
    expectFailureCF(b ? AskForRef("onA3", "A"), InvalidIdException("A"))

    close(actorOnA, a, b)
  }

  test("behavior ids are honored through a RemoteActorRef and through path-based routing") {
    val (a, b) = crossRegistered()
    val receivedUpper = SourceSignal(0)
    val upper: PF[String, Int] = { case (msg, _) => receivedUpper.mutate(_ + 1); Some(s"U:$msg") }
    val lower: PF[String, Int] = { case (msg, _) => Some(s"l:$msg") }
    val multi = ActorBuilder[String, Int](0)
      .withId("multiB")
      .withBehavior("upper", upper).withBehavior("lower", lower)
      .withSystem(b).build()

    awaitRef(b, "multiB")
    val ref = remoteRef(a, "multiB", "B")
    expectSuccessCF(ref ? ("1", "upper"), "U:1")
    expectSuccessCF(ref ? ("1", "lower"), "l:1")
    // path-based ask with a behavior id, remote and local paths
    expectSuccessCF(a.ask("1", ActorPath.Remote("B", "multiB"), "upper"), "U:1")
    expectSuccessCF(b.ask("1", ActorPath.Local("multiB"), "lower"), "l:1")
    // path-based bang with a behavior id
    a.tell("1", ActorPath.Remote("B", "multiB"), "upper")
    assert(waitFor(receivedUpper, 3), "path-based bang with a behavior id was not processed by that behavior")

    close(multi, a, b)
  }

  // ============================================================================
  // 8. RemoteSystemMsg handling
  // ============================================================================

  test("asking a system with an unhandled RemoteSystemMsg fails") {
    val sys = newSystem("sys")
    expectFailureCF(sys ? Done, UnhandledMsgException(Done.toString))
    close(sys)
  }

  test("banging a system with a non-SystemClosed RemoteSystemMsg is ignored and harmless") {
    val sys = newSystem[String]("sys")
    val behavior: PF[String, Int] = { case (msg, _) => Some(s"A: $msg") }
    sys ! AskForRef("whatever")
    val a = newActor(sys, "a", behavior)
    val ref = awaitRef(sys, "a")
    expectSuccessCF(ref ? "1", "A: 1")
    close(a, sys)
  }

  // ============================================================================
  // 9. Message-loss policy for invalid ids
  // ============================================================================

  test("bang to a missing actor or system is silently dropped") {
    val sys = newSystem[String]("sys")
    sys.tell("42", ActorPath.Remote("sys", "missing"), "")
    sys.tell("42", ActorPath.Local("missing"), "")
    sys.tell("42", ActorPath.Remote("UNKNOWN", "missing"), "")

    close(sys)
  }

  test("ask to a missing actor on the own system fails without hanging") {
    val sys = newSystem[String]("sys")
    // the ask falls through to the system's own message stream, which ignores it - hence the "No response" failure
    expectFailureCF(sys.ask("42", ActorPath.Remote("sys", "missing"), ""), Actor.FailToRespond[String].failed.get)
    expectFailureCF(sys.ask("42", ActorPath.Local("missing"), ""), Actor.FailToRespond[String].failed.get)

    close(sys)
  }

  // ============================================================================
  // 10. Concurrency
  // ============================================================================

  test("concurrent cross-system AskForRef resolves all actors while registration is in flight") {
    val (a, b) = crossRegistered()
    val numActors = 30
    val behavior: PF[String, Int] = { case (msg, _) => Some(s"B:$msg") }
    val actors = (0 until numActors).map(i => newActor(b, s"s$i", behavior))
    val futures = (0 until numActors).map { i =>
      Future {
        tryResultCF(a ? AskForRef(s"s$i", "B"))(using 20.seconds) match {
          case Success(Ref(ref)) => assertEquals(resultCF(ref.asInstanceOf[ActorRef[String]] ? i.toString), s"B:$i")
          case other => fail(s"Unexpected AskForRef response for s$i: $other")
        }
      }
    }
    await(Future.sequence(futures))(using 60.seconds)

    close(actors*)
    close(a, b)
  }

  // ============================================================================
  // 11. Deep registration across spawn
  // ============================================================================

  test("a grandchild spawned on the peer is discoverable through the cross-system registry") {
    val (a, b) = crossRegistered()
    val behavior: PF[String, Int] = { case (msg, _) => Some(s"G:$msg") }
    val child = spawn(b)(Spawn(actorId = "kid"))
    val grandchild = spawn(child)(Spawn(actorId = "grandkid", behaviors = List("default" -> behavior)))
    // no awaitRef here: the lookup must queue behind the pending Register
    resultCF(a ? AskForRef("grandkid", "B")) match {
      case Ref(ref) => expectSuccessCF(ref.asInstanceOf[ActorRef[String]] ? "1", "G:1")
      case other    => fail(s"Unexpected AskForRef response: $other")
    }
    close(grandchild)
    close(child)
    close(a, b)
  }
}
