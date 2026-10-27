package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.actors.Actor.PF
import io.github.makingthematrix.signals3.actors.SystemMsg.*
import io.github.makingthematrix.signals3.testutils.*
import io.github.makingthematrix.signals3.{Closeable, CloseableFuture, EventContext, Pausable, Signal, Threading}
import munit.FunSuite

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

/**
 * Tests for communication between actors using different message types, based on the `into[Msg]`
 * parameters of `ask` and `tell` in `Actor`, `OtherActor`, `ActorRef`, `ActorSystem`, and `RemoteSystem`.
 *
 * The suite covers:
 * - a conversion inserted at the call site of every overload of `tell`/`ask`/`!`/`?`,
 * - the semantics of those conversions (applied once, skipped for already-conforming arguments, failing at the sender),
 * - cross-type communication between two actor systems through [[ConversionRemoteSystem]].
 *
 * NOTE: This file deliberately does NOT import `scala.language.implicitConversions`.
 * All conversions below are inserted because the parameters they are passed to are `into` types.
 * The suite compiling at all is part of the regression test for that mechanism.
 */
class ConversionActorSpec extends FunSuite {

  // ============================================================================================
  // Fixtures
  // ============================================================================================

  sealed trait Proto
  object Proto {
    final case class Num(n: Int) extends Proto
    final case class Text(s: String) extends Proto
  }
  import Proto.*

  // A marker type: the conversion appends '#', so the behavior can detect how many times it ran.
  final case class Tag(s: String)
  // A type whose conversion always fails.
  final case class Poison()

  // Message types of two actor systems connected through ConversionRemoteSystem.
  // MsgA -> MsgB doubles, MsgB -> MsgA halves, so a double application of either
  // conversion changes the result of the round trip.
  final case class MsgA(x: Int)
  final case class MsgB(y: Int)

  given Conversion[Int, Proto]    = n => Num(n)
  given Conversion[String, Proto] = s => Text(s)
  given Conversion[Tag, Proto]    = t => Text(t.s + "#")
  given Conversion[Poison, Proto] = _ => throw new IllegalArgumentException("poison conversion")
  given Conversion[MsgA, MsgB]    = m => MsgB(m.x * 2)
  given Conversion[MsgB, MsgA]    = m => MsgA(m.y / 2)

  // ============================================================================================
  // Helpers
  // ============================================================================================

  private val eventContext = EventContext()
  import Threading.defaultContext

  given Timeout: FiniteDuration = 5.seconds

  override def beforeEach(context: BeforeEach): Unit =
    eventContext.start()

  override def afterEach(context: AfterEach): Unit =
    eventContext.stop()

  private def close(actor: Actor[?, ?]): Unit = {
    actor.asInstanceOf[Closeable].close()
    waitFor(actor.isClosedSignal, true)
  }

  private def create(pf: PF[Proto, Unit]): Actor[Proto, Unit] & Closeable & Pausable =
    ActorBuilder[Proto]().withBehaviorPF(pf).build()

  /** Builds an actor with named behaviors; the first behavior in the list is the first one tried. */
  private def createWithBehaviors(behaviors: (String, PF[Proto, Unit])*): Actor[Proto, Unit] & Closeable & Pausable =
    behaviors.foldRight(ActorBuilder[Proto]())((beh, b) => b.withBehavior(beh._1, beh._2)).build()

  private def newSystem[Msg](id: String): ActorSystem[Msg, Int] =
    ActorSystem[Msg, Int](id, 0, Actor.defBeat)

  /** Creates an actor registered on the given system; the first behavior in the list is the first one tried. */
  private def newActorOn[Msg](sys: ActorSystem[Msg, Int], id: String, behaviors: (String, PF[Msg, Int])*): Actor[Msg, Int] =
    behaviors.foldRight(ActorBuilder[Msg, Int](0).withId(id))((beh, b) => b.withBehavior(beh._1, beh._2))
      .withSystem(sys).build()

  /** Polls AskForRef until the actor with the given id is registered on the system. */
  private def awaitRef[Msg](sys: ActorSystem[Msg, Int], id: String): ActorRef[Msg] = {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < 5000) {
      tryResultCF(sys ? AskForRef(id))(using 1.seconds) match {
        case Success(Ref(ref)) => return ref.asInstanceOf[ActorRef[Msg]]
        case Success(other)    => fail(s"Unexpected AskForRef response: $other")
        case Failure(_)        => Thread.sleep(50) // not registered yet
      }
    }
    fail(s"Actor '$id' was not registered within 5 seconds")
  }

  /** Two actor systems with different message types: B is registered on A through a conversion bridge. */
  private def convertedBridge(): (ActorSystem[MsgA, Int], ActorSystem[MsgB, Int]) = {
    val a = newSystem[MsgA]("A")
    val b = newSystem[MsgB]("B")
    awaitCF(a ? RegisterSystem(ConversionRemoteSystem[MsgA, MsgB](b)))
    (a, b)
  }

  /** An actor that captures the Ref delivered by AskForRefAsync in a system message. */
  private class CapturingActorImpl(sys: ActorSystem[MsgA, Int])(using ExecutionContext)
    extends ActorImpl[MsgA, Int]("capturing", 0, Actor.defBeat, None, Some(sys)) {
    @volatile var receivedRef: Option[ActorRef[?]] = None
    override protected def processSysEntry(msg: SysEntry): Unit = msg match {
      case (Ref(ref), p) =>
        receivedRef = Some(ref)
        respond(p, Done)
      case other =>
        super.processSysEntry(other)
    }
  }

  // ============================================================================================
  // A. Conversions through every entry point of a single actor
  // ============================================================================================

  test("bang and tell convert the argument and deliver the converted message to the behavior") {
    val got = Signal(Seq.empty[Proto])
    val actor = create { case (p, _) => got.mutate(_ :+ p); None }
    actor ! 42
    actor.tell("hello")
    waitFor(got, Seq(Num(42), Text("hello")))
    close(actor)
  }

  test("ask converts the argument and returns the response typed as Msg") {
    val actor = create {
      case (Num(n), _)   => Some(Text(s"N:$n"))
      case (Text(s), _) => Some(Text(s"T:$s"))
    }
    assertEquals(resultCF(actor ? 42), Text("N:42"))
    assertEquals(resultCF(actor.ask("hi")), Text("T:hi"))
    close(actor)
  }

  test("tell with a behavior id converts before routing to the named behavior") {
    val got = Signal(Seq.empty[String])
    val special: PF[Proto, Unit] = { case (p, _) => got.mutate(_ :+ s"S:$p"); None }
    val default: PF[Proto, Unit] = { case (p, _) => got.mutate(_ :+ s"D:$p"); None }
    val actor = createWithBehaviors("default" -> default, "special" -> special)

    actor.tell(42, "special")
    // NOTE: an `into` parameter converts the whole argument, not the components of a tuple argument,
    // so a convertible message inside a tuple must be converted before the call
    actor ! ("special" -> Text("hello"))

    // both messages were converted and routed to the special behavior, not the default one
    waitFor(got, Seq("S:Num(42)", "S:Text(hello)"))
    close(actor)
  }

  test("ask with a behavior id converts before routing to the named behavior") {
    val special: PF[Proto, Unit] = { case (p, _) => Some(Text(s"S:$p")) }
    val default: PF[Proto, Unit] = { case (p, _) => Some(Text(s"D:$p")) }
    val actor = createWithBehaviors("default" -> default, "special" -> special)

    assertEquals(resultCF(actor.ask(42, "special")), Text("S:Num(42)"))
    // tuple elements are not converted inside the call - the message must be passed as Msg
    assertEquals(resultCF(actor.ask("hello", "special")), Text("S:Text(hello)"))
    // regular routing is untouched by the conversions
    assertEquals(resultCF(actor ? Num(1)), Text("D:Num(1)"))
    close(actor)
  }

  test("path-based tell and ask convert the argument") {
    val got = Signal(Seq.empty[Proto])
    val actor = create {
      case (p @ Num(n), _)  => got.mutate(_ :+ p); Some(Text(s"N:$n"))
      case (p @ Text(s), _) => got.mutate(_ :+ p); Some(Text(s"T:$s"))
    }
    assertEquals(resultCF(actor.ask(42, ActorPath.Direct, "")), Text("N:42"))
    assertEquals(resultCF(actor.ask("hi", ActorPath.Direct, "")), Text("T:hi"))

    actor.tell(7, ActorPath.Direct, "")
    actor.tell("eight", ActorPath.Direct, "")

    waitFor(got, Seq(Num(42), Text("hi"), Num(7), Text("eight")))
    close(actor)
  }

  test("OtherActor extensions convert arguments sent from inside a behavior") {
    val got = Signal(Seq.empty[Proto])
    val asked = Signal[Proto](Text(""))
    val bid: PF[Proto, Unit] = {
      case (p @ Num(n), _) => got.mutate(_ :+ p); Some(Text(s"R:$n"))
      case (p, _)         => got.mutate(_ :+ p); None
    }
    val receiver = createWithBehaviors("bid" -> bid)
    val sender = create { case _ =>
      receiver ! 42
      (receiver ? 7).pipeTo(asked)
      receiver.tell("hello")
      receiver.tell("bid", "tagged")
      None
    }
    sender ! Num(0)
    waitFor(got, Seq(Num(42), Num(7), Text("hello"), Text("tagged")))
    assertEquals(asked.currentValue, Some(Text("R:7")))
    close(sender)
    close(receiver)
  }

  test("LocalActorRef converts arguments through its tell and ask overloads") {
    val sys = newSystem[Proto]("sysLocalRef")
    val got = Signal(Seq.empty[Proto])
    val echo: PF[Proto, Int] = { case (p, _) => got.mutate(_ :+ p); Some(Text(s"ok:$p")) }
    val target = newActorOn(sys, "target", "default" -> echo)
    val ref = awaitRef(sys, "target")
    assert(ref.isLocal)

    ref ! 42
    ref.tell("hello")
    ref !(Text("hello2"), "default")
    waitFor(got, Seq(Num(42), Text("hello"), Text("hello2")))

    assertEquals(resultCF(ref ? 7), Text("ok:Num(7)"))
    assertEquals(resultCF(ref.ask("asked")), Text("ok:Text(asked)"))
    assertEquals(resultCF(ref ? (Text("asked2"), "default")), Text("ok:Text(asked2)"))
    close(target)
    close(sys)
  }

  test("RemoteActorRef converts arguments through its tell and ask overloads") {
    val a = newSystem[Proto]("remoteA")
    val b = newSystem[Proto]("remoteB")
    awaitCF(a ? RegisterSystem(b))
    val got = Signal(Seq.empty[Proto])
    val echo: PF[Proto, Int] = { case (p, _) => got.mutate(_ :+ p); Some(Text(s"ok:$p")) }
    val onB = newActorOn(b, "onB", "default" -> echo)
    awaitRef(b, "onB")

    tryResultCF(a ? AskForRef("onB", "remoteB")) match {
      case Success(Ref(ref0)) =>
        val ref = ref0.asInstanceOf[ActorRef[Proto]]
        assert(!ref.isLocal)
        assertEquals(ref.path, ActorPath.Remote("remoteB", "onB"))

        ref ! 42
        ref ! "hello"
        waitFor(got, Seq(Num(42), Text("hello")))
        assertEquals(resultCF(ref ? 7), Text("ok:Num(7)"))
        assertEquals(resultCF(ref.ask("asked")), Text("ok:Text(asked)"))
        assertEquals(resultCF(ref ? (Text("asked2"), "default")), Text("ok:Text(asked2)"))
      case other => fail(s"Unexpected AskForRef response: $other")
    }
    close(onB)
    Try(close(a))
    Try(close(b))
  }

  // ============================================================================================
  // B. Conversion semantics
  // ============================================================================================

  test("an argument that already conforms to Msg is delivered without any conversion") {
    // even with a Conversion[Proto, Proto] in scope, exact and subtype arguments are passed as-is;
    // summoning the given to keep the compiler from reporting it as unused - the point is that
    // it is never selected for arguments that already conform
    given Conversion[Proto, Proto] = p => Text(s"converted:${p.toString}")
    locally(summon[Conversion[Proto, Proto]])
    val actor = create { case (p, _) => Some(Text(s"got:$p")) }
    assertEquals(resultCF(actor ? Num(1)), Text("got:Num(1)"))
    assertEquals(resultCF(actor ? Text("x")), Text("got:Text(x)"))
    // convertible types still convert in the same scope
    assertEquals(resultCF(actor ? 5), Text("got:Num(5)"))
    close(actor)
  }

  test("the conversion is applied exactly once across direct, LocalActorRef, and RemoteActorRef chains") {
    // the Tag conversion appends one '#'; a message passing through a chain of nested `into` parameters
    // (ref -> system -> actor) must still contain exactly one
    val direct = create { case (Text(s), _) => Some(Text(s)) }
    assertEquals(resultCF(direct ? Tag("direct")), Text("direct#"))
    close(direct)

    val sys = newSystem[Proto]("tagLocal")
    val echoInt: PF[Proto, Int] = { case (Text(s), _) => Some(Text(s)) }
    val target = newActorOn(sys, "t", "default" -> echoInt)
    val ref = awaitRef(sys, "t")
    assertEquals(resultCF(ref ? Tag("local")), Text("local#"))
    close(target)
    close(sys)

    val a = newSystem[Proto]("tagRemoteA")
    val b = newSystem[Proto]("tagRemoteB")
    awaitCF(a ? RegisterSystem(b))
    val onB = newActorOn(b, "onB", "default" -> echoInt)
    awaitRef(b, "onB")
    tryResultCF(a ? AskForRef("onB", "tagRemoteB")) match {
      case Success(Ref(ref0)) =>
        assertEquals(resultCF(ref0.asInstanceOf[ActorRef[Proto]] ? Tag("remote")), Text("remote#"))
      case other => fail(s"Unexpected AskForRef response: $other")
    }
    close(onB)
    Try(close(a))
    Try(close(b))
  }

  test("the right conversion is selected for each source type") {
    val got = Signal(Seq.empty[Proto])
    val actor = create { case (p, _) => got.mutate(_ :+ p); None }
    actor ! 42
    actor ! "text"
    waitFor(got, Seq(Num(42), Text("text")))
    close(actor)
  }

  test("a failing conversion throws at the sender and delivers nothing, leaving the actor usable") {
    val got = Signal(Seq.empty[Proto])
    val actor = create { case (p, _) => got.mutate(_ :+ p); None }
    // the conversion is inserted at the call site, before the message enters the actor machinery
    intercept[IllegalArgumentException] { actor ! Poison(); () }
    intercept[IllegalArgumentException] { actor ? Poison(); () }
    awaitAllTasks
    assert(got.currentValue.exists(_.isEmpty), s"nothing should be delivered, got ${got.currentValue}")
    // the actor itself was not affected
    actor ! 42
    waitFor(got, Seq(Num(42)))
    close(actor)
  }

  // ============================================================================================
  // C. Two actors in one system
  // ============================================================================================

  test("a behavior can send a converted message to another actor through a Local path") {
    val sys = newSystem[Proto]("sysC1")
    val got = Signal(Seq.empty[Proto])
    val target = newActorOn(sys, "target", "default" -> { case (p, _) => got.mutate(_ :+ p); None })
    awaitRef(sys, "target")
    val sender = newActorOn(sys, "sender", "default" -> { case (_, a) => a.tell(42, ActorPath.Local("target"), ""); None })
    sender ! Num(0)
    waitFor(got, Seq(Num(42)))
    close(sender)
    close(target)
    close(sys)
  }

  test("a behavior can ask another actor through a Local path with a converted message") {
    val sys = newSystem[Proto]("sysC2")
    val target = newActorOn(sys, "target", "default" -> { case (p, _) => Some(Text(s"ack:$p")) })
    awaitRef(sys, "target")
    val rsp = Signal[Proto](Text(""))
    val sender = newActorOn(sys, "sender", "default" -> { case (_, a) =>
      a.ask("ping", ActorPath.Local("target"), "").pipeTo(rsp); None
    })
    sender ! Num(0)
    waitFor(rsp, Text("ack:Text(ping)"))
    close(sender)
    close(target)
    close(sys)
  }

  // ============================================================================================
  // D. Cross-system communication through ConversionRemoteSystem
  // ============================================================================================

  test("tell across a ConversionRemoteSystem bridge converts to the target system's message type") {
    val (a, b) = convertedBridge()
    val got = Signal(Seq.empty[MsgB])
    val onB = newActorOn(b, "onB", "default" -> { case (m, _) => got.mutate(_ :+ m); None })
    awaitRef(b, "onB")
    // MsgA(21) becomes MsgB(42) on B
    a.tell(MsgA(21), ActorPath.Remote("B", "onB"), "")
    waitFor(got, Seq(MsgB(42)))
    close(onB)
    Try(close(a))
    Try(close(b))
  }

  test("ask across the bridge converts the message and converts the response back") {
    val (a, b) = convertedBridge()
    val onB = newActorOn(b, "onB", "default" -> { case (MsgB(y), _) => Some(MsgB(y + 1)) })
    awaitRef(b, "onB")
    // MsgA(21) -> MsgB(42) -> response MsgB(43) -> MsgA(21); a second back-conversion would give MsgA(10)
    assertEquals(resultCF(a.ask(MsgA(21), ActorPath.Remote("B", "onB"), "")), MsgA(21))
    close(onB)
    Try(close(a))
    Try(close(b))
  }

  test("behavior ids are honored across the bridge together with the conversion") {
    val (a, b) = convertedBridge()
    val onB = newActorOn(b, "onB",
      "default" -> { case (m, _) => Some(MsgB(m.y)) },
      "bump"    -> { case (MsgB(y), _) => Some(MsgB(y + 100)) })
    awaitRef(b, "onB")
    // MsgA(5) -> MsgB(10) -> behavior "bump" -> MsgB(110) -> MsgA(55)
    assertEquals(resultCF(a.ask(MsgA(5), ActorPath.Remote("B", "onB"), "bump")), MsgA(55))
    close(onB)
    Try(close(a))
    Try(close(b))
  }

  test("a ref from cross-system AskForRef over the bridge routes back through the sender system and converts") {
    val (a, b) = convertedBridge()
    val got = Signal(Seq.empty[MsgB])
    val onB = newActorOn(b, "onB", "default" -> { case (m, _) => got.mutate(_ :+ m); Some(MsgB(m.y + 1)) })
    awaitRef(b, "onB")
    tryResultCF(a ? AskForRef("onB", "B")) match {
      case Success(Ref(ref0)) =>
        val ref = ref0.asInstanceOf[ActorRef[MsgA]]
        assert(!ref.isLocal)
        assertEquals(ref.path, ActorPath.Remote("B", "onB"))
        // the ref is bound to A, so every send re-routes through A's registry and converts
        ref ! MsgA(7) // MsgA(7) -> MsgB(14)
        assertEquals(resultCF(ref ? MsgA(7)), MsgA(7)) // MsgB(14) -> MsgB(15) -> MsgA(7)
        waitFor(got, Seq(MsgB(14), MsgB(14)))
      case other => fail(s"Unexpected AskForRef response: $other")
    }
    close(onB)
    Try(close(a))
    Try(close(b))
  }

  test("mirrored registrations convert in the opposite direction on the peer") {
    val a = newSystem[MsgA]("A")
    val b = newSystem[MsgB]("B")
    awaitCF(a ? RegisterSystem(ConversionRemoteSystem[MsgA, MsgB](b)))
    awaitCF(b ? RegisterSystem(ConversionRemoteSystem[MsgB, MsgA](a)))
    val got = Signal(Seq.empty[MsgA])
    val onA = newActorOn(a, "onA", "default" -> { case (m, _) => got.mutate(_ :+ m); Some(MsgA(m.x + 1)) })
    awaitRef(a, "onA")
    // MsgB(50) -> MsgA(25) -> response MsgA(26) -> MsgB(52)
    assertEquals(resultCF(b.ask(MsgB(50), ActorPath.Remote("A", "onA"), "")), MsgB(52))
    waitFor(got, Seq(MsgA(25)))
    close(onA)
    Try(close(a))
    Try(close(b))
  }

  test("system messages pass through the bridge unchanged") {
    val (a, b) = convertedBridge()
    val convB = ConversionRemoteSystem[MsgA, MsgB](b)
    awaitCF(convB.ask(SystemMsg.Pause))
    waitFor(b.isPausedSignal, true)
    convB.tell(SystemMsg.Unpause)
    waitFor(b.isPausedSignal, false)
    Try(close(a))
    Try(close(b))
  }

  test("a ref from cross-system AskForRefAsync over the bridge routes back through the sender system and converts") {
    val (a, b) = convertedBridge()
    val got = Signal(Seq.empty[MsgB])
    val onB = newActorOn(b, "onB2", "default" -> { case (m, _) => got.mutate(_ :+ m); None })
    awaitRef(b, "onB2")
    val capturer = CapturingActorImpl(a)
    capturer.initialize()

    tryResultCF(a ? AskForRefAsync(capturer, "onB2", "B")) match {
      case Success(Done) =>
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5000 && capturer.receivedRef.isEmpty) Thread.sleep(50)
        capturer.receivedRef match {
          case Some(ref0) =>
            // like the ref from the sync AskForRef, the delivered ref is bound to A:
            // every send re-routes through A's registry and converts
            val ref = ref0.asInstanceOf[ActorRef[MsgA]]
            assert(!ref.isLocal, "the sender received a local ref to an actor behind a conversion bridge")
            assertEquals(ref.path, ActorPath.Remote("B", "onB2"))
            ref ! MsgA(7) // MsgA(7) -> MsgB(14)
            waitFor(got, Seq(MsgB(14)))
          case None => fail("sender actor never received the remote Ref")
        }
      case other => fail(s"Unexpected AskForRefAsync response: $other")
    }
    close(onB)
    Try(close(a))
    Try(close(b))
  }
}
