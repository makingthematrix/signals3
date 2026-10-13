package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture
import io.github.makingthematrix.signals3.actors.Actor.{HeartBeatStrategy, invalidActorId, unhandledMsg}
import io.github.makingthematrix.signals3.actors.ActorSystem.invalidSystemId

import scala.Conversion.into
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.reflect.TypeTest
import scala.util.{Failure, Success}
import scala.util.chaining.scalaUtilChainingOps

final class ActorSystem[Msg, State] private(
  override val id: String,
  state: State,
  override protected val heartbeat: HeartBeatStrategy
)(using ExecutionContext) extends ActorImpl[Msg, State](id, state, heartbeat) with RemoteSystem[Msg] {
	import ActorPath.*
	import SystemMsg.*

	@volatile private var actorRefs: Map[String, ActorRef[Msg]] = Map.empty
	@volatile private var systems: Map[String, RemoteSystem[Msg]] = Map.empty

	inline private def unregister(systemId: String) = {
		systems -= systemId
		CloseableFuture.successful(Done)
	}

	// As in `ActorImpl`, `SystemMsg` cases with type parameters are matched through trusted `TypeTest`
	// instances created by `SystemMsg.trusted` - the type arguments are erased at runtime, so the tests
	// verify only the runtime class of the message and take the type arguments on trust.
	private given RegisterT: TypeTest[SystemMsg, Register[Msg, State]] = trusted[SystemMsg, Register[Msg, State]]
	private given RequeueT: TypeTest[SystemMsg, Requeue[Msg]] = trusted[SystemMsg, Requeue[Msg]]

	override protected def processSysEntry(msg: SysEntry): Unit = msg match {
		case (SystemClosed(systemId), p) =>
			systems -= systemId
			respond(p, Done)
		case (UnregisterSystem(systemId), p) =>
			systems -= systemId
			respond(p, Done)
		case (RegisterSystem(system), p) =>
			systems += (system.id -> system.asInstanceOf[RemoteSystem[Msg]])
			respond(p, Done)
		case (RegisterT(reg), p) =>
			val ref = LocalActorRef(reg.actor)
			actorRefs += (reg.actor.id -> ref)
			respond(p, Ref(ref))
		case (Unregister(actorId), p) =>
			actorRefs -= actorId
			respond(p, Done)
		case (ActorClosed(actorId), _) =>
			actorRefs -= actorId
			super.processSysEntry(msg)
		case (AskForRef(actorId, systemId), p) if systemId == "" || systemId == id =>
			actorRefs.get(actorId) match {
				case Some(ref) => respond(p, Ref(ref))
				case None => respond(p, invalidActorId(actorId).future)
			}
		case (AskForRefAsync(sender, actorId, systemId), p) if systemId == "" || systemId == id =>
			actorRefs.get(actorId) match {
				case Some(ref) =>
					sender ! Ref(ref)
					respond(p, Done)
				case None =>
					// Don't send InvalidId to sender - just fail the promise consistently
					respond(p, invalidActorId(actorId).future)
			}
		case (RequeueT(rq), p) =>
			actorRefs.get(rq.actorId) match {
				case Some(ref)                => ref ! (rq.msg, rq.behId)
				case None if rq.tryNumber < 2 => this ! Requeue(rq.actorId, rq.msg, rq.behId, rq.tryNumber + 1) // requeue
				case None                     => respond(p, invalidActorId(rq.actorId).future)
			}
		case (AskForRef(actorId, systemId), p) =>
			systems.get(systemId) match {
				case None => respond(p, invalidSystemId(systemId).future)
				case Some(system) =>
					respond(p, system.ask(AskForRef(actorId)).flatMap {
						case Ref(ref) =>
							CloseableFuture.successful(Ref(RemoteActorRef(ActorPath.Remote(systemId, actorId), this)))
						case _ => invalidActorId(actorId) // Handle unexpected responses
					}.future)
			}
		case (AskForRefAsync(sender, actorId, systemId), p) =>
			systems.get(systemId) match {
				case None =>
					respond(p, invalidSystemId(systemId).future)
				case Some(system) =>
					system.ask(AskForRef(actorId)).onComplete {
						case Success(Ref(ref)) =>
							sender ! Ref(ref)
							respond(p, Done)
						case Success(msg) =>
							respond(p, unhandledMsg[SystemMsg](msg.toString).future) // the only successful response should be RemoteSystemMsg.Ref
						case Failure(_: Actor.InvalidIdException) =>
							respond(p, invalidActorId(actorId).future)
						case Failure(t) =>
							p.foreach(_.failure(t))
					}
			}
		case _ =>
			super.processSysEntry(msg)
	}

	override protected def spawn(data: Spawn[Msg, State]): SystemMsg =
		if (children.contains(data.actorId)) SystemMsg.InvalidId else {
			val b1 = ActorBuilder[Msg, State](data.state.getOrElse(this.state))
				.withIdIf(data.actorId.nonEmpty, data.actorId)
				.withBehaviorsIf(data.behaviors.nonEmpty, data.behaviors, this.behaviors)
				.withHeartbeat(data.heartbeat.getOrElse(this.heartbeat))
				.withParent(this)
				.withSystem(this)
				.withOnInitIf(data.onInit.nonEmpty, data.onInit.get)
			val b2 = data.executionContext.fold(b1)(b1.withParallelDispatch)
			val b3 = if (data.useSerialDispatch) b2.withSerialDispatch() else b2
			val child = b3.build()
			children = children + (child.id -> child)
			SystemMsg.NewChild(child)
		}

	override def tell(msg: into[Msg], path: ActorPath, behId: String): Unit = path match {
		case Direct                => msgStream ! (msg, None, behId)
		case Local(`id`)           => msgStream ! (msg, None, behId)
		case Remote("", `id`)      => msgStream ! (msg, None, behId)
		case Remote(`id`, `id`)    => msgStream ! (msg, None, behId)
		case Local(actorId)        => if (actorRefs.contains(actorId)) actorRefs(actorId) ! (msg, behId) else this ! Requeue(actorId, msg, behId)
		case Remote("", actorId)   => if (actorRefs.contains(actorId)) actorRefs(actorId) ! (msg, behId) else this ! Requeue(actorId, msg, behId)
		case Remote(`id`, actorId) => if (actorRefs.contains(actorId)) actorRefs(actorId) ! (msg, behId) else this ! Requeue(actorId, msg, behId)
		case Remote(systemId, _) if systemId != id && systems.contains(systemId)  => systems(systemId).tell(msg, path, behId)
		case _ => // invalid system or actor id
	}

	override def ask(msg: into[Msg], path: ActorPath, behId: String): CloseableFuture[Msg] = {
		inline def sendToStream() = CloseableFuture.from(Promise[Msg]().tap { p => msgStream ! (msg, Some(p), behId) })
		path match {
			case Direct                => sendToStream()
			case Local(`id`)           => sendToStream()
			case Remote("", `id`)      => sendToStream()
			case Remote(`id`, `id`)    => sendToStream()
			case Local(actorId)        => if (actorRefs.contains(actorId)) actorRefs(actorId) ? (msg, behId) else sendToStream()
			case Remote("", actorId)   => if (actorRefs.contains(actorId)) actorRefs(actorId) ? (msg, behId) else sendToStream()
			case Remote(`id`, actorId) => if (actorRefs.contains(actorId)) actorRefs(actorId) ? (msg, behId) else sendToStream()
			case Remote(systemId, _)   => if (systemId != id && systems.contains(systemId)) systems(systemId).ask(msg, path, behId) else invalidSystemId(systemId)
		}
	}

	override protected[actors] def initialize(): Unit = if (!isInitialized) {
		systems += (id -> this)
		actorRefs += (id -> LocalActorRef(this)) // register yourself as a valid actor
		super.initialize()
	}

	override protected def shutdown(): Future[SystemMsg] = {
		systems.foreach { (systemId, sys) => if (systemId != id) sys.tell(SystemClosed(id)) }
		super.shutdown()
	}
}

object ActorSystem {
	final case class InvalidSystemIdException(systemId: String) extends IllegalArgumentException(s"Invalid system id: $systemId")

	inline def invalidSystemId[Msg](systemId: String)(using ExecutionContext): CloseableFuture[Msg] =
		CloseableFuture.failed(InvalidSystemIdException(systemId))

	def apply[Msg, State](id: String, state: State, heartbeat: HeartBeatStrategy)(using ExecutionContext): ActorSystem[Msg, State] = {
		assert(id != "")
		new ActorSystem(id, state, heartbeat).tap { _.initialize() }
	}

	inline def apply[Msg, State](state: State, heartbeat: HeartBeatStrategy)(using ExecutionContext): ActorSystem[Msg, State] =
		apply(IdGenerator.generate("system"), state, heartbeat)
}
