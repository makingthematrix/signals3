package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.actors.Actor.defBeat
import io.github.makingthematrix.signals3.*
import io.github.makingthematrix.signals3.Behaviors.{PF, TF}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

/**
  * A builder for creating and configuring Actor instances.
  *
  * ActorBuilder provides a way to construct actors by chaining configuration methods.
  * It supports setting the actor's ID, initial state, pbehIds, heartbeat strategy, initialization
  * callbacks, dispatch mode, execution context, parent actor, and actor system.
  *
  * The builder pattern allows for optional and conditional configuration through methods like
  * `withIdIf`, `withStateIf`, etc., which apply settings based on boolean predicates.
  *
  * All behaviors are configured ''by id'': the builder holds only the ids of functions registered
  * beforehand in the [[Behaviors]] library. The sugar methods register their functions in `Behaviors`
  * under generated ids and then work on those ids - the generated ids are readable afterwards through
  * the id fields (`pbehIds`, `onInitIds`, `onBeatIds`, `onPauseIds`, ...), so the user can unregister
  * them manually when the actor is no longer needed.
  *
  * The sugar naming encodes the type of the registered function. The `F`/`Fs` variants
  * (`addOnInitF`/`Fs`, `addOnPauseF`/`Fs`, `addOnUnpauseF`/`Fs`, `addOnCloseF`/`Fs`) register
  * [[Behaviors.Beh]]-like functions of the type `() => Any`, which run on the lifecycle event alone.
  * The `TF`/`TFs` variants (`addOnBeatTF`/`TFs`, and `withBehaviorPF`/`PFs` for the message
  * behaviors) register functions which ALSO receive the actor.
  *
  * In accordance with the convention of the whole behavior system, the most recently registered
  * behavior runs first and can take over the processing of a message from the earlier behaviors.
  * The id fields are therefore kept newest-first, and the ids are attached to the actor at build
  * time in a way which preserves that order.
  *
  * @tparam Msg   The type of incoming messages the actor will process
  * @tparam State The type of internal state maintained by the actor
  */
final class ActorBuilder[Msg, State] (val id: String,
                                      state: State,
                                      val pbehIds: List[String] = Nil,
                                      private val heartbeat: HeartBeatStrategy = defBeat,
                                      val onInitIds: List[String] = Nil,
                                      private val useSerialDispatch: Boolean = false,
                                      private val executionContext: Option[ExecutionContext] = None,
                                      private val tags: Set[ActorTag] = Set.empty,
                                      val onBeatIds: List[String] = Nil,
                                      private val parent: Option[ActorRef[Msg]] = None,
                                      private val system: Option[ActorSystem[Msg, ?]] = None,
                                      val onPauseIds: List[String] = Nil,
                                      val onUnpauseIds: List[String] = Nil,
                                      val onCloseIds: List[String] = Nil
                                     ) {

  // The single point of copying all the fields - every `with*` method goes through it,
  // so no field can be accidentally dropped on the way.
  private def copy(id: String = id,
                   state: State = state,
                   pbehIds: List[String] = pbehIds,
                   heartbeat: HeartBeatStrategy = heartbeat,
                   onInitIds: List[String] = onInitIds,
                   useSerialDispatch: Boolean = useSerialDispatch,
                   executionContext: Option[ExecutionContext] = executionContext,
                   tags: Set[ActorTag] = tags,
                   onBeatIds: List[String] = onBeatIds,
                   parent: Option[ActorRef[Msg]] = parent,
                   system: Option[ActorSystem[Msg, ?]] = system,
                   onPauseIds: List[String] = onPauseIds,
                   onUnpauseIds: List[String] = onUnpauseIds,
                   onCloseIds: List[String] = onCloseIds
                  ): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, pbehIds, heartbeat, onInitIds, useSerialDispatch, executionContext,
      tags, onBeatIds, parent, system, onPauseIds, onUnpauseIds, onCloseIds)

  /**
    * Sets the id of the actor.
    *
    * @param newId The new id
    * @return A new builder with the new id
    */
  inline def withId(newId: String): ActorBuilder[Msg, State] = copy(id = newId)

  inline def withIdIf(p: => Boolean, newId: => String): ActorBuilder[Msg, State] =
    if (p) withId(newId) else this

  inline def withIdIf(p: => Boolean, newId: => String, orElse: => String): ActorBuilder[Msg, State] =
    withId(if (p) newId else orElse)

  /**
   * Sets the initial state of the actor.
   *
   * @param newState The new initial state
   * @return A new builder with the updated state
   */
  inline def withState(newState: State): ActorBuilder[Msg, State] = copy(state = newState)

  inline def withStateIf(p: => Boolean, newState: => State): ActorBuilder[Msg, State] =
    if (p) withState(newState) else this

  inline def withStateIf(p: => Boolean, newState: => State, orElse: => State): ActorBuilder[Msg, State] =
    withState(if (p) newState else orElse)

  /**
   * Adds a behavior by the id of a partial behavior registered in [[Behaviors]].
   * The behavior is prepended, so it will be tried before the behaviors added earlier.
   *
   * @param behaviorId The id of the behavior.
   * @return A new builder with the added behavior
   */
  inline def addBehavior(behaviorId: String): ActorBuilder[Msg, State] = copy(pbehIds = behaviorId :: pbehIds)

  inline def addBehaviorIf(p: => Boolean, behaviorId: => String): ActorBuilder[Msg, State] =
    if (p) addBehavior(behaviorId) else this

  inline def addBehaviorIf(p: => Boolean, behaviorId: => String, orElse: => String): ActorBuilder[Msg, State] =
    addBehavior(if (p) behaviorId else orElse)

  /**
   * Registers a partial behavior in [[Behaviors]] under a generated id and adds that id to the builder.
   * The generated id is readable afterwards through `pbehIds`.
   *
   * @param pf The partial function which tries to process an incoming message.
   * @return A new builder with the added behavior
   */
  inline def withBehaviorPF(pf: PF[Msg, State]): ActorBuilder[Msg, State] =
    copy(pbehIds = Behaviors.registerPF(pf) :: pbehIds)

  /**
   * Registers all the given partial behaviors in [[Behaviors]] under generated ids and adds those
   * ids to the builder, preserving the order of the given collection (the first pf in the collection
   * is tried first among them).
   *
   * @param pfs The partial functions to register and add.
   * @return A new builder with the added behaviors
   */
  inline def withBehaviorPFs(pfs: Iterable[PF[Msg, State]]): ActorBuilder[Msg, State] =
    copy(pbehIds = pfs.map(Behaviors.registerPF(_)).toList ::: pbehIds)

  /**
   * Adds multiple behavior ids.
   *
   * @param newBehaviors The ids of the behaviors to add
   * @return A new builder with the added behavior ids
   */
  inline def withBehaviors(newBehaviors: Iterable[String]): ActorBuilder[Msg, State] =
    copy(pbehIds = newBehaviors.toList ::: pbehIds)

  inline def withBehaviorsIf(p: => Boolean, newBehaviors: => Iterable[String]): ActorBuilder[Msg, State] =
    if (p) withBehaviors(newBehaviors) else this

  inline def withBehaviorsIf(p: => Boolean, newBehaviors: => Iterable[String], orElse: => Iterable[String]): ActorBuilder[Msg, State] =
    withBehaviors(if (p) newBehaviors else orElse)

  /**
   * Sets the heartbeat strategy.
   *
   * @param newHeartbeat The heartbeat strategy to use
   * @return A new builder with the updated heartbeat strategy
   */
  inline def withHeartbeat(newHeartbeat: HeartBeatStrategy): ActorBuilder[Msg, State] = copy(heartbeat = newHeartbeat)

  inline def withHeartbeatIf(p: => Boolean, newHeartbeat: => HeartBeatStrategy): ActorBuilder[Msg, State] =
    if (p) withHeartbeat(newHeartbeat) else this

  inline def withHeartbeatIf(p: => Boolean, newHeartbeat: => HeartBeatStrategy, orElse: => HeartBeatStrategy): ActorBuilder[Msg, State] =
    withHeartbeat(if (p) newHeartbeat else orElse)

  /**
   * Sets a linear heartbeat strategy with the specified interval.
   *
   * @param ms The interval in milliseconds
   * @return A new builder with the linear heartbeat strategy
   */
  inline def withLinearHeartbeat(ms: Long): ActorBuilder[Msg, State] =
    withHeartbeat(HeartBeatStrategy.Linear(ms.millis))

  /**
   * Sets a reactive heartbeat strategy.
   *
   * @param shortMs  The interval between a new message coming and the processing
   * @param longMs  The interval between processings when the messages queues are empty
   * @return A new builder with the reactive heartbeat strategy
   */
  inline def withReactiveHeartbeat(shortMs: Long, longMs: Long): ActorBuilder[Msg, State] =
    withHeartbeat(HeartBeatStrategy.Reactive(shortMs.millis, longMs.millis))

  /**
   * Sets the ids of the initialization behaviors - functions registered in [[Behaviors]]
   * which will run when the actor is initialized.
   *
   * @param newInitIds The ids of the behaviors to call on initialization
   * @return A new builder with the initialization behaviors
   */
  def addOnInits(newInitIds: Iterable[String]): ActorBuilder[Msg, State] = {
	  val uniqueIds = newInitIds.filterNot(onInitIds.contains)
	  copy(onInitIds = uniqueIds.toList ::: onInitIds)
  }

  /**
   * Registers an initialization function: it is registered in [[Behaviors]] under a generated id
   * and that id is added to the init ids (readable through `onInitIds`).
   *
   * @param f The function to run when the actor is initialized.
   * @return A new builder with the initialization function
   */
  inline def addOnInitF(f: () => Any): ActorBuilder[Msg, State] = addOnInit(Behaviors.registerF(f, "onInit"))

  /**
   * Registers a collection of initialization functions: each function is registered in [[Behaviors]]
   * under a generated id and the ids are added to the init ids, preserving the order of the collection
   * (the first function in the collection runs first among them). The ids are readable through
   * `onInitIds`.
   *
   * @param fs The functions to run when the actor is initialized.
   * @return A new builder with the initialization functions
   */
  inline def addOnInitFs(fs: Iterable[() => Any]): ActorBuilder[Msg, State] =
	  addOnInits(fs.map(Behaviors.registerF(_, "onInit")))

  inline def addOnInitsIf(p: => Boolean, newInitIds: Iterable[String]): ActorBuilder[Msg, State] =
    if (p) addOnInits(newInitIds) else this

  inline def addOnInitsIf(p: => Boolean, newInitIds: Iterable[String], orElse: => Iterable[String]): ActorBuilder[Msg, State] =
    addOnInits(if (p) newInitIds else orElse)

  inline def addOnInit(behId: String): ActorBuilder[Msg, State] = addOnInits(List(behId))
  inline def addOnInitIf(p: => Boolean, behId: String): ActorBuilder[Msg, State] = addOnInitsIf(p, List(behId))
  inline def addOnInitIf(p: => Boolean, behId: String, orElse: => String): ActorBuilder[Msg, State] = addOnInitsIf(p, List(behId), List(orElse))

  inline def withNoInit(): ActorBuilder[Msg, State] = copy(onInitIds = Nil)

  inline def withNoInit(p: => Boolean): ActorBuilder[Msg, State] = if (p) withNoInit() else this

  /**
   * Sets the ids of the pause behaviors - functions registered in [[Behaviors]]
   * which will run every time the actor is paused.
   *
   * @param newPauseIds The ids of the behaviors to call on pause
   * @return A new builder with the pause behaviors
   */
  def addOnPauses(newPauseIds: Iterable[String]): ActorBuilder[Msg, State] = {
	  val uniqueIds = newPauseIds.filterNot(onPauseIds.contains)
	  copy(onPauseIds = uniqueIds.toList ::: onPauseIds)
  }

  inline def addOnPausesIf(p: => Boolean, newPauseIds: Iterable[String]): ActorBuilder[Msg, State] =
    if (p) addOnPauses(newPauseIds) else this

  inline def addOnPausesIf(p: => Boolean, newPauseIds: Iterable[String], orElse: => Iterable[String]): ActorBuilder[Msg, State] =
    addOnPauses(if (p) newPauseIds else orElse)

  inline def addOnPause(pauseId: String): ActorBuilder[Msg, State] = addOnPauses(List(pauseId))
  inline def addOnPauseIf(p: => Boolean, pauseId: String): ActorBuilder[Msg, State] = addOnPausesIf(p, List(pauseId))
  inline def addOnPauseIf(p: => Boolean, pauseId: String, orElse: => String): ActorBuilder[Msg, State] =
    addOnPausesIf(p, List(pauseId), List(orElse))

  /**
   * Registers a pause behavior: the function is registered in [[Behaviors]] under a generated id
   * and that id is prepended to the pause ids (readable through `onPauseIds`).
   *
   * @param f The function to run every time the actor is paused.
   * @return A new builder with the pause behavior
   */
  inline def addOnPauseF(f: () => Any): ActorBuilder[Msg, State] = addOnPauseFs(List(f))

  /**
   * Registers a collection of pause behaviors: each function is registered in [[Behaviors]] under
   * a generated id and the ids are prepended to the pause ids, preserving the order of the
   * collection (the first function in the collection runs first among them). The ids are readable
   * through `onPauseIds`.
   *
   * @param fs The functions to run every time the actor is paused.
   * @return A new builder with the pause behaviors
   */
  inline def addOnPauseFs(fs: Iterable[() => Any]): ActorBuilder[Msg, State] =
    copy(onPauseIds = fs.map(Behaviors.registerF(_, "onPause")).toList ::: onPauseIds)

  inline def withNoPause(): ActorBuilder[Msg, State] = copy(onPauseIds = Nil)

  inline def withNoPause(p: => Boolean): ActorBuilder[Msg, State] =
    if (p) withNoPause() else this

  /**
   * Sets the ids of the unpause behaviors - functions registered in [[Behaviors]]
   * which will run every time the actor is unpaused.
   *
   * @param newUnpauseIds The ids of the behaviors to call on unpause
   * @return A new builder with the unpause behaviors
   */
  inline def addOnUnpauses(newUnpauseIds: Iterable[String]): ActorBuilder[Msg, State] = {
	  val uniqueIds = newUnpauseIds.filterNot(onUnpauseIds.contains)
	  copy(onUnpauseIds = uniqueIds.toList ::: onUnpauseIds)
  }

  inline def addOnUnpausesIf(p: => Boolean, newUnpauseIds: Iterable[String]): ActorBuilder[Msg, State] =
    if (p) addOnUnpauses(newUnpauseIds) else this

  inline def addOnUnpausesIf(p: => Boolean, newUnpauseIds: Iterable[String], orElse: => Iterable[String]): ActorBuilder[Msg, State] =
    addOnUnpauses(if (p) newUnpauseIds else orElse)

  inline def addOnUnpause(unpauseId: String): ActorBuilder[Msg, State] = addOnUnpauses(List(unpauseId))
  inline def addOnUnpauseIf(p: => Boolean, unpauseId: String): ActorBuilder[Msg, State] = addOnUnpausesIf(p, List(unpauseId))
  inline def addOnUnpauseIf(p: => Boolean, unpauseId: String, orElse: => String): ActorBuilder[Msg, State] =
    addOnUnpausesIf(p, List(unpauseId), List(orElse))

  /**
   * Registers an unpause behavior: the function is registered in [[Behaviors]] under a generated id
   * and that id is prepended to the unpause ids (readable through `onUnpauseIds`).
   *
   * @param f The function to run every time the actor is unpaused.
   * @return A new builder with the unpause behavior
   */
  inline def addOnUnpauseF(f: () => Any): ActorBuilder[Msg, State] = addOnUnpauseFs(List(f))

  /**
   * Registers a collection of unpause behaviors: each function is registered in [[Behaviors]] under
   * a generated id and the ids are prepended to the unpause ids, preserving the order of the
   * collection (the first function in the collection runs first among them). The ids are readable
   * through `onUnpauseIds`.
   *
   * @param fs The functions to run every time the actor is unpaused.
   * @return A new builder with the unpause behaviors
   */
  inline def addOnUnpauseFs(fs: Iterable[() => Any]): ActorBuilder[Msg, State] =
    copy(onUnpauseIds = fs.map(Behaviors.registerF(_, "onUnpause")).toList ::: onUnpauseIds)

  inline def withNoUnpause(): ActorBuilder[Msg, State] = copy(onUnpauseIds = Nil)

  inline def withNoUnpause(p: => Boolean): ActorBuilder[Msg, State] =
    if (p) withNoUnpause() else this

  /**
   * Sets the ids of the close behaviors - functions registered in [[Behaviors]]
   * which will run when the actor is closed.
   *
   * @param newCloseIds The ids of the behaviors to call on close
   * @return A new builder with the close behaviors
   */
  def addOnCloses(newCloseIds: Iterable[String]): ActorBuilder[Msg, State] = {
	  val uniqueIds = newCloseIds.filterNot(onCloseIds.contains)
	  copy(onCloseIds = uniqueIds.toList ::: onCloseIds)
  }

  inline def addOnClosesIf(p: => Boolean, newCloseIds: Iterable[String]): ActorBuilder[Msg, State] =
    if (p) addOnCloses(newCloseIds) else this

  inline def addOnClosesIf(p: => Boolean, newCloseIds: Iterable[String], orElse: => Iterable[String]): ActorBuilder[Msg, State] =
    addOnCloses(if (p) newCloseIds else orElse)

  inline def addOnClose(closeId: String): ActorBuilder[Msg, State] = addOnCloses(List(closeId))
  inline def addOnCloseIf(p: => Boolean, closeId: String): ActorBuilder[Msg, State] = addOnClosesIf(p, List(closeId))
  inline def addOnCloseIf(p: => Boolean, closeId: String, orElse: => String): ActorBuilder[Msg, State] =
    addOnClosesIf(p, List(closeId), List(orElse))

  /**
   * Registers a close behavior: the function is registered in [[Behaviors]] under a generated id
   * and that id is prepended to the close ids (readable through `onCloseIds`).
   *
   * @param f The function to run when the actor is closed.
   * @return A new builder with the close behavior
   */
  inline def addOnCloseF(f: () => Any): ActorBuilder[Msg, State] = addOnCloseFs(List(f))

  /**
   * Registers a collection of close behaviors: each function is registered in [[Behaviors]] under
   * a generated id and the ids are prepended to the close ids, preserving the order of the
   * collection (the first function in the collection runs first among them). The ids are readable
   * through `onCloseIds`.
   *
   * @param fs The functions to run when the actor is closed.
   * @return A new builder with the close behaviors
   */
  inline def addOnCloseFs(fs: Iterable[() => Any]): ActorBuilder[Msg, State] =
    copy(onCloseIds = fs.map(Behaviors.registerF(_, "onClose")).toList ::: onCloseIds)

  inline def withNoClose(): ActorBuilder[Msg, State] = copy(onCloseIds = Nil)

  inline def withNoClose(p: => Boolean): ActorBuilder[Msg, State] =
    if (p) withNoClose() else this

  /**
   * Configures the actor to use a serial dispatch queue.
   *
   * Serial dispatch ensures that messages are processed one at a time in the order they are received, with reduced overhead.
   *
   * @return A new builder configured for serial dispatch
   */
  inline def withSerialDispatch(): ActorBuilder[Msg, State] =
    copy(useSerialDispatch = true, executionContext = None)

  /**
   * Configures the actor to use a serial dispatch queue if a condition is met.
   *
   * Serial dispatch ensures that messages are processed one at a time in the order they are received, with reduced overhead.
   *
   * @param p The condition to check before configuring serial dispatch
   * @return A new builder configured for serial dispatch if the condition is true, otherwise the current builder
   */
  inline def withSerialDispatchIf(p: => Boolean): ActorBuilder[Msg, State] =
    if (p) withSerialDispatch() else this

  /**
    * Configures the actor to use a parallel dispatch queue with a specified execution context.
    *
    * Parallel dispatch allows messages to be processed concurrently, potentially improving performance but with increased overhead.
    *
    * @param ec The execution context to use for parallel dispatch
    * @return A new builder configured for parallel dispatch with the specified execution context
    */
  inline def withParallelDispatch(ec: ExecutionContext): ActorBuilder[Msg, State] =
    copy(useSerialDispatch = false, executionContext = Some(ec))

  inline def withParallelDispatchIf(p: => Boolean, ec: ExecutionContext): ActorBuilder[Msg, State] =
    if (p) withParallelDispatch(ec) else this

  inline def withParallelDispatchIf(p: => Boolean, ec: ExecutionContext, orElse: => ExecutionContext): ActorBuilder[Msg, State] =
    withParallelDispatch(if (p) ec else orElse)

  /**
   * Sets the tags of the actor.
   *
   * @param newTags The new tags
   * @return A new builder with the new tags
   */
  inline def withTags(newTags: Set[ActorTag]): ActorBuilder[Msg, State] = copy(tags = newTags)

  inline def withTagsIf(p: => Boolean, newTags: => Set[ActorTag]): ActorBuilder[Msg, State] =
    if (p) withTags(newTags) else this

  inline def withTagsIf(p: => Boolean, newTags: => Set[ActorTag], orElse: => Set[ActorTag]): ActorBuilder[Msg, State] =
    withTags(if (p) newTags else orElse)

  inline def withNoTags(): ActorBuilder[Msg, State] = withTags(Set.empty)

  inline def withNoTagsIf(p: => Boolean): ActorBuilder[Msg, State] = if (p) withNoTags() else this

  inline def addTag(tag: ActorTag): ActorBuilder[Msg, State] = copy(tags = tags + tag)

  inline def removeTag(tag: ActorTag): ActorBuilder[Msg, State] = copy(tags = tags - tag)

  /**
   * Sets the ids of the beat behaviors - total functions registered in [[Behaviors]]
   * which will run at every heartbeat of the actor.
   *
   * @param newBeatIds The ids of the behaviors to call at every heartbeat
   * @return A new builder with the beat behaviors
   */
  def addOnBeats(newBeatIds: Iterable[String]): ActorBuilder[Msg, State] = {
	  val uniqueIds = newBeatIds.filterNot(onBeatIds.contains)
	  copy(onBeatIds = uniqueIds.toList ::: onBeatIds)
  }

  /**
   * Registers a beat behavior: the total function is registered in [[Behaviors]] under a generated
   * id and that id is added to the builder. The generated id is readable afterwards through `onBeatIds`.
   *
   * @param tf The function to run at every heartbeat - it takes the actor.
   * @return A new builder with the beat behavior
   */
  inline def addOnBeatTF(tf: TF[Msg, State]): ActorBuilder[Msg, State] = addOnBeatTFs(List(tf))

  /**
   * Registers a collection of beat behaviors: each total function is registered in [[Behaviors]]
   * under a generated id and the ids are prepended to the beat ids, preserving the order of the
   * collection (the first function in the collection runs first among them). The ids are readable
   * through `onBeatIds`.
   *
   * @param tfs The functions to run at every heartbeat - each takes the actor.
   * @return A new builder with the beat behaviors
   */
  inline def addOnBeatTFs(tfs: Iterable[TF[Msg, State]]): ActorBuilder[Msg, State] =
	  copy(onBeatIds = tfs.toList.map(Behaviors.registerTF(_, "onBeat")) ::: onBeatIds)

	inline def addOnBeatsIf(p: => Boolean, newBeatIds: Iterable[String]): ActorBuilder[Msg, State] =
    if (p) addOnBeats(newBeatIds) else this

  inline def addOnBeatsIf(p: => Boolean, newBeatIds: => Iterable[String], orElse: => Iterable[String]): ActorBuilder[Msg, State] =
    addOnBeats(if (p) newBeatIds else orElse)

  inline def addOnBeat(beatId: String): ActorBuilder[Msg, State] = addOnBeats(List(beatId))
  inline def addOnBeatIf(p: => Boolean, beatId: String): ActorBuilder[Msg, State] = addOnBeatsIf(p, List(beatId))
  inline def addOnBeatIf(p: => Boolean, beatId: String, orElse: => String): ActorBuilder[Msg, State] =
    addOnBeatsIf(p, List(beatId), List(orElse))

  inline def withNoBeat(): ActorBuilder[Msg, State] = copy(onBeatIds = Nil)

  inline def withNoBeatIf(p: => Boolean): ActorBuilder[Msg, State] = if (p) withNoBeat() else this

  inline def withParent(parent: Actor[Msg, State]): ActorBuilder[Msg, State] =
    withParent(LocalActorRef[Msg](parent))

  inline def withParent(parent: ActorRef[Msg]): ActorBuilder[Msg, State] = copy(parent = Some(parent))

  inline def withParentIf(p: => Boolean, parent: => ActorRef[Msg]): ActorBuilder[Msg, State] =
    if (p) withParent(parent) else this

  inline def withParentIf(p: => Boolean, parent: => ActorRef[Msg], orElse: => ActorRef[Msg]): ActorBuilder[Msg, State] =
    withParent(if (p) parent else orElse)

  inline def withNoParent(): ActorBuilder[Msg, State] = copy(parent = None)

  inline def withNoParentIf(p: => Boolean): ActorBuilder[Msg, State] =
    if (p) withNoParent() else this

  inline def withSystem(system: ActorSystem[Msg, ?]): ActorBuilder[Msg, State] = copy(system = Some(system))

  inline def withSystemIf(p: => Boolean, system: => ActorSystem[Msg, ?]): ActorBuilder[Msg, State] =
    if (p) withSystem(system) else this

  inline def withSystemIf(p: => Boolean, system: => ActorSystem[Msg, ?], orElse: => ActorSystem[Msg, ?]): ActorBuilder[Msg, State] =
    withSystem(if (p) system else orElse)

  inline def withNoSystem(): ActorBuilder[Msg, State] = copy(system = None)

  inline def withNoSystemIf(p: => Boolean): ActorBuilder[Msg, State] =
    if (p) withNoSystem() else this

  def build()(using ec: ExecutionContext): Actor[Msg, State] & Closeable & Pausable = {
    if (useSerialDispatch)
      buildActor(state, DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))
    else
      buildActor(state, executionContext.getOrElse(ec))
  }

  private def buildActor(state: State, ec: ExecutionContext): Actor[Msg, State] & Closeable & Pausable = {
    val actor = new ActorImpl[Msg, State](id, state, heartbeat, tags, parent, system)(using ec)
    // Attaching an id prepends, so the ids are attached in reverse here - the most recently added
    // id ends up first and runs first, preserving the newest-first order of the builder's lists.
	  val (validInits, invalidInits) = onInitIds.partition(Behaviors.isRegistered)
	  if (invalidInits.nonEmpty) {
		  println(s"Invalid onInit ids found when building Actor ${id}: $invalidInits")
	  }
    validInits.reverse.foreach(actor.onInitId)

    val (validPBehs, invalidPbehs) = pbehIds.partition(Behaviors.isRegistered)
    if (invalidPbehs.nonEmpty) {
      println(s"Invalid behavior ids found when building Actor ${id}: $invalidPbehs")
    }
    actor.addBehaviors(validPBehs)

	  val (validBeats, invalidBeats) = onBeatIds.partition(Behaviors.isRegistered)
	  if (invalidBeats.nonEmpty) {
		  println(s"Invalid onBeat ids found when building Actor ${id}: $invalidBeats")
	  }
	  validBeats.reverse.foreach(actor.onBeatId)

    val (validPauses, invalidPauses) = onPauseIds.partition(Behaviors.isRegistered)
	  if (invalidPauses.nonEmpty) {
		  println(s"Invalid onPause ids found when building Actor ${id}: $invalidPauses")
	  }
	  validPauses.reverse.foreach(actor.onPauseId)

	  val (validUnpauses, invalidUnpauses) = onUnpauseIds.partition(Behaviors.isRegistered)
	  if (invalidUnpauses.nonEmpty) {
		  println(s"Invalid onUnpause ids found when building Actor ${id}: $invalidUnpauses")
	  }
	  validUnpauses.reverse.foreach(actor.onUnpauseId)

	  val (validCloses, invalidCloses) = onCloseIds.partition(Behaviors.isRegistered)
	  if (invalidCloses.nonEmpty) {
		  println(s"Invalid onClose ids found when building Actor ${id}: $invalidCloses")
	  }
	  validCloses.reverse.foreach(actor.onCloseId)

	  actor.initialize()
    actor
  }
}

/**
 * Companion object for ActorBuilder with factory methods and pre-defined strategies.
 */
object ActorBuilder {

  /**
    * Creates a new ActorBuilder with the specified initial state.
    *
    * @tparam Msg   The type of incoming messages
    * @tparam State The type of internal state
    * @return A new ActorBuilder instance
    */
  def apply[Msg, State](id: String, state: State): ActorBuilder[Msg, State] = new ActorBuilder(id = id, state = state)
  inline def apply[Msg, State](state: State): ActorBuilder[Msg, State] = apply(id = IdGenerator.generate(), state = state)
  inline def apply[Msg](): ActorBuilder[Msg, Unit] = ActorBuilder[Msg, Unit](state = ())
}
