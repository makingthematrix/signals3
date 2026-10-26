package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.CloseableFuture
import io.github.makingthematrix.signals3.actors.Actor.*
import io.github.makingthematrix.signals3.actors.ActorSystem.invalidSystemId

import scala.Conversion.into
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.reflect.TypeTest
import scala.util.chaining.scalaUtilChainingOps
import scala.util.{Failure, Success}

import scala.collection.mutable

final class ActorSystem[Msg, State] private(
  override val id: String,
  state: State,
  override protected val heartbeat: HeartBeatStrategy
)(using ExecutionContext) extends ActorImpl[Msg, State](id, state, heartbeat) with RemoteSystem[Msg] {
	import ActorPath.*
	import SystemMsg.*

	private val actorRefs  = new mutable.HashMap[String, ActorRef[Msg]]()
	private val actorTags  = new mutable.HashMap[ActorTag, mutable.ArrayBuffer[ActorRef[Msg]]]()
	private val systems    = new mutable.HashMap[String, RemoteSystem[Msg]]
	private val systemTags = new mutable.HashMap[ActorTag, mutable.ArrayBuffer[RemoteSystem[Msg]]]()

	private def register(system: RemoteSystem[Msg], tags: List[ActorTag], p: Option[Promise[SystemMsg]]): Unit =
		if (!systems.contains(system.id)) {
			systems += (system.id -> system)
			tags.foreach { tag =>
				val buf = systemTags.getOrElseUpdate(tag, new mutable.ArrayBuffer[RemoteSystem[Msg]]())
				buf.addOne(system)
			}
			respond(p, Done)
		} else respond(p, InvalidId)

	private def register(actor: Actor[Msg, ?], tags: List[ActorTag], p: Option[Promise[SystemMsg]]): Unit =
		if (!actorRefs.contains(actor.id)) {
			val ref = new LocalActorRef(actor)
			actorRefs += (actor.id -> ref)
			tags.foreach { tag =>
				val buf = actorTags.getOrElseUpdate(tag, new mutable.ArrayBuffer[ActorRef[Msg]]())
				buf.addOne(ref)
			}
			respond(p, Ref(ref))
		} else respond(p, InvalidId)

	private def unregisterSystem(systemId: String, p: Option[Promise[SystemMsg]]): Unit = if (systems.contains(systemId)) {
		systems -= systemId
		systemTags.foreach { case (_, buf) =>
			val index = buf.indexWhere(_.id == systemId)
			if (index > -1) buf.remove(index)
		}
		respond(p, Done)
	}

	private def unregisterActor(actorId: String, p: Option[Promise[SystemMsg]]): Unit = {
		actorRefs -= actorId
		actorTags.foreach { case (_, buf) =>
			val index = buf.indexWhere(_.path.actorId == actorId)
			if (index > -1) buf.remove(index)
		}
		respond(p, Done)
	}

	// As in `ActorImpl`, `SystemMsg` cases with type parameters are matched through trusted `TypeTest`
	// instances created by `SystemMsg.trusted` - the type arguments are erased at runtime, so the tests
	// verify only the runtime class of the message and take the type arguments on trust.
	private given RegisterT: TypeTest[SystemMsg, Register[Msg, State]] = trusted[SystemMsg, Register[Msg, State]]
	private given RequeueT: TypeTest[SystemMsg, Requeue[Msg]] = trusted[SystemMsg, Requeue[Msg]]
	private given RegisterSystemT: TypeTest[SystemMsg, RegisterSystem[Msg]] = trusted[SystemMsg, RegisterSystem[Msg]]

	override protected def processSysEntry(msg: SysEntry): Unit = msg match {
		case (SystemClosed(systemId), p) =>
			unregisterSystem(systemId, p)
		case (UnregisterSystem(systemId), p) =>
			unregisterSystem(systemId, p)
		case (RegisterSystemT(reg), p) =>
			register(reg.system, reg.tags, p)
		case (RegisterT(reg), p) =>
			register(reg.actor, reg.tags, p)
		case (Unregister(actorId), p) =>
			unregisterActor(actorId, p)
		case (ActorClosed(actorId), p) =>
			unregisterActor(actorId, p)
			super.processSysEntry(msg)
		case (AskForRef(actorId, systemId, _), p) if systemId == "" || systemId == id =>
			actorRefs.get(actorId) match {
				case Some(ref) => respond(p, Ref(ref))
				case None => respond(p, invalidActorId(actorId).future)
			}
		case (AskForRefAsync(sender, actorId, systemId, _), p) if systemId == "" || systemId == id =>
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
		case (AskForRef(actorId, systemId, _), p) =>
			systems.get(systemId) match {
				case None => respond(p, invalidSystemId(systemId).future)
				case Some(system) =>
					respond(p, system.ask(AskForRef(actorId)).flatMap {
						case Ref(ref) =>
							CloseableFuture.successful(Ref(RemoteActorRef(ActorPath.Remote(systemId, actorId), this)))
						case _ => invalidActorId(actorId) // Handle unexpected responses
					}.future)
			}
		case (AskForRefAsync(sender, actorId, systemId, _), p) =>
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
			val child = LocalActorRef(b3.build())
			children = children + (child.id -> child)
			NewChild(child)
		}

	override def tell(msg: SystemMsg, path: ActorPath): Unit = if (!isClosed) path match {
		case Direct                => systemStream ! (msg, None)
		case Local(`id`)           => systemStream ! (msg, None)
		case Remote("", `id`)      => systemStream ! (msg, None)
		case Remote(`id`, `id`)    => systemStream ! (msg, None)
		case Local(actorId)        => if (actorRefs.contains(actorId)) actorRefs(actorId).tell(msg) else this ! SysRequeue(actorId, msg)
		case Remote("", actorId)   => if (actorRefs.contains(actorId)) actorRefs(actorId).tell(msg) else this ! SysRequeue(actorId, msg)
		case Remote(`id`, actorId) => if (actorRefs.contains(actorId)) actorRefs(actorId).tell(msg) else this ! SysRequeue(actorId, msg)
		case Remote(systemId, _) if systemId != id && systems.contains(systemId)  => systems(systemId).tell(msg, path)
		case _ => // invalid system or actor id
	}

	override def tell(msg: into[Msg], path: ActorPath, behId: String): Unit = if (!isClosed) path match {
		case Direct                => msgStream ! (msg, None, behId)
		case Local(`id`)           => msgStream ! (msg, None, behId)
		case Remote("", `id`)      => msgStream ! (msg, None, behId)
		case Remote(`id`, `id`)    => msgStream ! (msg, None, behId)
		case Local(actorId)        => if (actorRefs.contains(actorId)) actorRefs(actorId).tell(msg, behId) else this ! Requeue(actorId, msg, behId)
		case Remote("", actorId)   => if (actorRefs.contains(actorId)) actorRefs(actorId).tell(msg, behId) else this ! Requeue(actorId, msg, behId)
		case Remote(`id`, actorId) => if (actorRefs.contains(actorId)) actorRefs(actorId).tell(msg, behId) else this ! Requeue(actorId, msg, behId)
		case Remote(systemId, _) if systemId != id && systems.contains(systemId)  => systems(systemId).tell(msg, path, behId)
		case _ => // invalid system or actor id
	}

	override def ask(msg: SystemMsg, path: ActorPath): CloseableFuture[SystemMsg] = if (!isClosed) {
		inline def sendToStream() = CloseableFuture.from(Promise[SystemMsg]().tap { p => systemStream ! (msg, Some(p)) })
		path match {
			case Direct => sendToStream()
			case Local(`id`) => sendToStream()
			case Remote("", `id`) => sendToStream()
			case Remote(`id`, `id`) => sendToStream()
			case Local(actorId) => if (actorRefs.contains(actorId)) actorRefs(actorId).ask(msg) else sendToStream()
			case Remote("", actorId) => if (actorRefs.contains(actorId)) actorRefs(actorId).ask(msg) else sendToStream()
			case Remote(`id`, actorId) => if (actorRefs.contains(actorId)) actorRefs(actorId).ask(msg) else sendToStream()
			case Remote(systemId, _) => if (systemId != id && systems.contains(systemId)) systems(systemId).ask(msg, path) else invalidSystemId(systemId)
		}
	} else ActorIsClosed[SystemMsg]

	override def ask(msg: into[Msg], path: ActorPath, behId: String): CloseableFuture[Msg] = if (!isClosed) {
		inline def sendToStream() = CloseableFuture.from(Promise[Msg]().tap { p => msgStream ! (msg, Some(p), behId) })
		path match {
			case Direct                => sendToStream()
			case Local(`id`)           => sendToStream()
			case Remote("", `id`)      => sendToStream()
			case Remote(`id`, `id`)    => sendToStream()
			case Local(actorId)        => if (actorRefs.contains(actorId)) actorRefs(actorId).ask(msg, behId) else sendToStream()
			case Remote("", actorId)   => if (actorRefs.contains(actorId)) actorRefs(actorId).ask(msg, behId) else sendToStream()
			case Remote(`id`, actorId) => if (actorRefs.contains(actorId)) actorRefs(actorId).ask(msg, behId) else sendToStream()
			case Remote(systemId, _)   => if (systemId != id && systems.contains(systemId)) systems(systemId).ask(msg, path, behId) else invalidSystemId(systemId)
		}
	} else ActorIsClosed[Msg]

	override protected def tryInitialize(): Unit = {
		systems += (id -> this)
		actorRefs += (id -> LocalActorRef(this)) // register yourself as a valid actor
		super.tryInitialize()
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
