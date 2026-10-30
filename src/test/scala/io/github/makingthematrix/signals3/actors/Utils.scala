package io.github.makingthematrix.signals3.actors

import Actor.{HeartBeatStrategy, InvalidIdException, PF}
import SystemMsg.*
import io.github.makingthematrix.signals3.{Closeable, Pausable}
import io.github.makingthematrix.signals3.Utils.*

import scala.concurrent.{ExecutionContext, TimeoutException}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}
import munit.Assertions.*

object Utils {
	enum MyMsg {
		case MyInt(n: Int)
		case MyStr(str: String)
	}

	object MyMsg {
		def apply(n: Int): MyMsg = MyInt(n)
		def apply(str: String): MyMsg = MyStr(str)
	}

	/** A user-defined tag - verifies that `ActorTag` is genuinely extensible. */
	case object Custom extends ActorTag("custom")

	/** An actor that captures the Ref delivered by AskForRefAsync in a system message. */
	class CapturingActor[Msg](sys: ActorSystem[Msg, Int])(using ExecutionContext)
		extends ActorImpl[Msg, Int]("capturing", 0, Actor.defBeat, Set.empty, None, Some(sys)){
		@volatile var receivedRef: Option[ActorRef[Msg]] = None
		@volatile var receivedCount: Int = 0

		override protected def processSysEntry(msg: SysEntry): Unit = msg match {
			case (Ref(ref), p) =>
				receivedRef = Some(ref.asInstanceOf[ActorRef[Msg]])
				receivedCount += 1
				respond(p, Done)
			case other =>
				super.processSysEntry(other)
		}
	}
	
	def close(actor: Actor[?, ?] & Closeable)(using ec: ExecutionContext, timeout: FiniteDuration = DefaultTimeout): Unit = {
		actor.close()
		waitFor(actor.isClosedSignal, true)
	}

	def create[Msg](pf: PF[Msg, Unit])(using ExecutionContext): Actor[Msg, Unit] & Closeable & Pausable =
		ActorBuilder[Msg]()
			.withBehaviorPF(pf)
			.build()

	def create[Msg, State](state: State, pf: PF[Msg, State])
	                      (using ExecutionContext): Actor[Msg, State] & Closeable & Pausable =
		ActorBuilder[Msg, State](state)
			.withBehaviorPF(pf)
			.build()

	def create[Msg](pf: PF[Msg, Unit], hbs: HeartBeatStrategy)
	               (using ExecutionContext): Actor[Msg, Unit] & Closeable & Pausable =
		ActorBuilder[Msg, Unit](())
			.withBehaviorPF(pf)
			.withHeartbeat(hbs)
			.build()

	def create[Msg, State](state: State, pf: PF[Msg, State], onInit: MutableActor[Msg, State] => Unit)
	                      (using ExecutionContext): Actor[Msg, State] & Closeable & Pausable =
		ActorBuilder[Msg, State](state)
			.withBehaviorPF(pf)
			.withOnInit(onInit)
			.build()

	def create[Msg](pf: PF[Msg, Unit], onInit: MutableActor[Msg, Unit] => Unit)
	               (using ExecutionContext): Actor[Msg, Unit] & Closeable & Pausable =
		ActorBuilder[Msg, Unit](())
			.withBehaviorPF(pf)
			.withOnInit(onInit)
			.build()

	/** Builds an actor with named behaviors; the first behavior in the list is the first one tried. */
	def createWithBehaviors[Msg](behaviors: (String, PF[Msg, Unit])*)
	                            (using ExecutionContext): Actor[Msg, Unit] & Closeable & Pausable =
		behaviors.foldRight(ActorBuilder[Msg]())((beh, b) => b.withBehavior(beh._1, beh._2)).build()

	def newSystem[Msg](id: String)(using ExecutionContext): ActorSystem[Msg, Int] & Closeable & Pausable =
		ActorSystem[Msg, Int](id, 0, Actor.defBeat)
	
	def close(actorRef: ActorRef[?]): Unit =
		tryResultCF(actorRef ? Close) match {
			case Success(Done) =>
			case Success(msg) => fail(s"closing ${actorRef.id} failed: $msg")
			case Failure(ex) => fail(ex.getMessage)
		}

	def newActor[Msg, State](sys: ActorSystem[Msg, State], id: String, defState: State, pf: PF[Msg, State])
	            (using ExecutionContext): Actor[Msg, State] & Closeable & Pausable =
		ActorBuilder[Msg, State](defState).withId(id).withBehavior("default", pf).withSystem(sys).build()

	def newActor(sys: ActorSystem[String, Int], id: String, pf: PF[String, Int])
	            (using ExecutionContext): Actor[String, Int] & Closeable & Pausable =
		ActorBuilder[String, Int](0).withId(id).withBehavior("default", pf).withSystem(sys).build()
		
	def awaitRef[Msg](sys: ActorSystem[Msg, ?], id: String): ActorRef[Msg] = {
		val start = System.currentTimeMillis()
		while (System.currentTimeMillis() - start < 5000) {
			try {
				tryResultCF(sys ? AskForRef(id)) match {
					case Success(Ref(ref)) => return ref.asInstanceOf[ActorRef[Msg]]
					case Failure(InvalidIdException(`id`)) => Thread.sleep(50)
					case other => fail(s"Unexpected response: $other")
				}
			} catch {
				case _: TimeoutException => Thread.sleep(50)
			}
		}
		fail(s"Actor '$id' was not registered within 5 seconds")
	}

	def awaitInvalid[Msg](sys: ActorSystem[String, ?], id: String): Unit = {
		val start = System.currentTimeMillis()
		while (System.currentTimeMillis() - start < 5000) {
			try {
				tryResultCF[SystemMsg](sys ? AskForRef(id)) match {
					case Failure(InvalidIdException(`id`)) => return
					case Success(Ref(_))                   => Thread.sleep(50)
					case other                             => throw new AssertionError(s"Unexpected response: $other")
				}
			} catch {
				case _: TimeoutException => Thread.sleep(50)
			}
		}
		fail(s"Actor '$id' was still registered after 5 seconds")
	}
	/** Two cross-registered systems: each one holds the other in its `systems` map. */
	def crossRegistered(systemAId: String = "A", systemBId: String = "B")(using ExecutionContext):
	(ActorSystem[String, Int] & Closeable & Pausable, ActorSystem[String, Int] & Closeable & Pausable) = {
		val a = newSystem[String](systemAId)
		val b = newSystem[String](systemBId)
		awaitCF(a ? RegisterSystem(b))
		awaitCF(b ? RegisterSystem(a))
		(a, b)
	}

	/** Looks up an actor on a peer system and returns the (remote) ref. */
	def remoteRef(sys: ActorSystem[String, Int], actorId: String, systemId: String): ActorRef[String] =
		tryResultCF(sys ? AskForRef(actorId, systemId)) match {
			case Success(Ref(ref)) => ref.asInstanceOf[ActorRef[String]]
			case other             => fail(s"Unexpected AskForRef response: $other")
		}
	
	def spawn[Msg, State](parent: Actor[Msg, ?])(data: SystemMsg.Spawn[Msg, State]): ActorRef[Msg] =
		tryResultCF(parent ? data) match {
			case Success(NewChild(child))                              => child.asInstanceOf[ActorRef[Msg]]
			case Failure(InvalidIdException(id)) if id == data.actorId => fail(s"Spawn with id $id was rejected as invalid")
			case other                                                 => fail(s"Unexpected spawn response: $other")
		}

	def spawn[Msg, State](parent: ActorRef[Msg])(data: SystemMsg.Spawn[Msg, State]): ActorRef[Msg] =
		tryResultCF(parent ? data) match {
			case Success(NewChild(child))                              => child.asInstanceOf[ActorRef[Msg]]
			case Failure(InvalidIdException(id)) if id == data.actorId => fail(s"Spawn with id $id was rejected as invalid")
			case other                                                 => fail(s"Unexpected spawn response: $other")
		}

	/** An actor built without a system and registered manually with the given tags. */
	def newTaggedActor(sys: ActorSystem[String, Int], id: String, tags: ActorTag*)
	                  (using ExecutionContext): Actor[String, Int] & Closeable & Pausable = {
		val a = ActorBuilder[String, Int](0)
			.withId(id)
			.withBehavior("default", { case (msg, _) => Some(s"$id: $msg") })
			.build()
		resultCF(sys ? Register(a, tags.toSet)) match {
			case Ref(_) => a
			case other => fail(s"registering '$id' with tags ${tags.map(_.name).mkString(",")} failed: $other")
		}
	}

	/** Looks up an actor by tags - all tags in the query must match. */
	def refByTags(sys: ActorSystem[String, Int], tags: ActorTag*): ActorRef[String] =
		resultCF(sys ? AskForRef(tags = tags.toSet)) match {
			case Ref(ref) => ref.asInstanceOf[ActorRef[String]]
			case other => fail(s"Expected a Ref for tags ${tags.map(_.name).mkString(",")}, got $other")
		}

	/** Polls a tag query until an actor registered with the given tags is found -
		* registration is asynchronous with respect to actor creation. */
	def awaitRefByTags(sys: ActorSystem[String, Int], tags: ActorTag*): ActorRef[String] = {
		val start = System.currentTimeMillis()
		while (System.currentTimeMillis() - start < 5000) {
			tryResultCF(sys ? AskForRef(tags = tags.toSet)) match {
				case Success(Ref(ref)) => return ref.asInstanceOf[ActorRef[String]]
				case Failure(_)        => Thread.sleep(50) // not registered (yet)
				case other             => fail(s"Unexpected AskForRef response: $other")
			}
		}
		fail(s"No actor with tags ${tags.map(_.name).mkString(",")} was registered within 5 seconds")
	}

	/** Looks up a system by id or by tags. */
	def systemRef(sys: ActorSystem[String, Int], msg: SystemMsg): ActorRef[String] =
		resultCF(sys ? msg) match {
			case Ref(ref) => ref.asInstanceOf[ActorRef[String]]
			case other    => fail(s"Expected a Ref of a system, got $other")
		}

	/** Waits until the capturing actor receives a Ref. */
	def awaitCaptured[Msg](capturer: CapturingActor[Msg]): ActorRef[Msg] = {
		val start = System.currentTimeMillis()
		while (System.currentTimeMillis() - start < 5000) {
			capturer.receivedRef match {
				case Some(ref) => return ref
				case None      => Thread.sleep(50)
			}
		}
		fail("the sender actor did not receive the Ref within 5 seconds")
	}
}
