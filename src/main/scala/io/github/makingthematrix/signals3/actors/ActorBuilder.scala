package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.actors.Actor.defBeat
import io.github.makingthematrix.signals3.{Behaviors, Closeable, DispatchQueue, IdGenerator, Pausable}
import io.github.makingthematrix.signals3.Behaviors.*

import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext

/**
  * A builder for creating and configuring Actor instances.
  *
  * ActorBuilder provides a way to construct actors by chaining configuration methods.
  * It supports setting the actor's ID, initial state, behaviorIds, heartbeat strategy, initialization
  * callbacks, dispatch mode, execution context, parent actor, and actor system.
  *
  * The builder pattern allows for optional and conditional configuration through methods like
  * `withIdIf`, `withStateIf`, etc., which apply settings based on boolean predicates.
  *
  * @tparam Msg   The type of incoming messages the actor will process
  * @tparam State The type of internal state maintained by the actor
  * @param id                The unique identifier for the actor (defaults to auto-generated)
  * @param state             The initial state of the actor (must be set before building)
  * @param behaviorIds         The list of behaviorIds that define how the actor processes messages
  * @param heartbeat         The heartbeat strategy controlling message processing intervals
  * @param onInitIds            Optional initialization callback invoked when the actor starts
  * @param useSerialDispatch Whether to use serial (single-threaded) dispatch queue
  * @param executionContext  Optional execution context for parallel dispatch
  * @param parent            Optional parent actor in the actor hierarchy
  * @param system            Optional actor system this actor belongs to
  */
final class ActorBuilder[Msg, State] (private val id: String,
                                      private val state: State,
                                      private val behaviorIds: List[String] = Nil,
                                      private val heartbeat: HeartBeatStrategy = defBeat,
                                      private val onInitIds: List[String] = Nil,
	                                    private val useSerialDispatch: Boolean = false,
	                                    private val executionContext: Option[ExecutionContext] = None,
	                                    private val tags: Set[ActorTag] = Set.empty,
	                                    private val onBeatIds: List[String] = Nil,
	                                    private val parent: Option[ActorRef[Msg]] = None,
	                                    private val system: Option[ActorSystem[Msg, ?]] = None,
	                                    private val onPauseIds: List[String] = Nil,
	                                    private val onUnpauseIds: List[String] = Nil,
	                                    private val onCloseIds: List[String] = Nil
                                     ) {

  /**
    * Sets the id of the actor.
    * 
    * @param newId The new id
    * @return A new builder with the new id
    */
  inline def withId(newId: String): ActorBuilder[Msg, State] =
    new ActorBuilder(newId, state, behaviorIds, heartbeat, onInitIds, useSerialDispatch, executionContext, tags, onBeatIds, parent, system)
    
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
  inline def withState(newState: State): ActorBuilder[Msg, State] =
    new ActorBuilder(id, newState, behaviorIds, heartbeat, onInitIds, useSerialDispatch, executionContext, tags, onBeatIds, parent, system)

  inline def withStateIf(p: => Boolean, newState: => State): ActorBuilder[Msg, State] =
    if (p) withState(newState) else this

  inline def withStateIf(p: => Boolean, newState: => State, orElse: => State): ActorBuilder[Msg, State] =
    withState(if (p) newState else orElse)
	
  /**
   * Adds a behavior as a PBeh tuple (id, pf).
   *
   * @param behaviorId The behavior id - the 
   * @return A new builder with the added behavior
   */
  inline def addBehavior(behaviorId: String): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, behaviorId :: behaviorIds, heartbeat, onInitIds, useSerialDispatch, executionContext, tags, onBeatIds, parent, system)

  inline def addBehaviorIf(p: => Boolean, behaviorId: => String): ActorBuilder[Msg, State] =
    if (p) addBehavior(behaviorId) else this

  inline def addBehaviorIf(p: => Boolean, behaviorId: => String, orElse: => String): ActorBuilder[Msg, State] =
    addBehavior(if (p) behaviorId else orElse)

  /**
   * Adds multiple behaviorIds with explicit IDs.
   *
   * @param newBehaviors A collection of behavior tuples to add
   * @return A new builder with the added behaviorIds
   */
  inline def withBehaviors(newBehaviors: Iterable[String]): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, newBehaviors.toList ::: behaviorIds, heartbeat, onInitIds, useSerialDispatch, executionContext, tags, onBeatIds, parent, system)

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
  inline def withHeartbeat(newHeartbeat: HeartBeatStrategy): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, behaviorIds, newHeartbeat, onInitIds, useSerialDispatch, executionContext, tags, onBeatIds, parent, system)

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
   * Sets a reactived heartbeat strategy.
   *
   * @param shortMs  The interval between a new message coming and the processing
   * @param longMs  The interval between processings when the messages queues are empty
   * @return A new builder with the agitated heartbeat strategy
   */
  inline def withReactiveHeartbeat(shortMs: Long, longMs: Long): ActorBuilder[Msg, State] =
    withHeartbeat(HeartBeatStrategy.Reactive(shortMs.millis, longMs.millis))
  
  /**
   * Sets the initialization callback.
   *
   * The callback is invoked exactly once when the actor is initialized,
   * before message processing begins.
   *
   * @param newInitIds The new behavior ids to call on initialization
   * @return A new builder with the initialization callback
   */
  inline def withOnInit(newInitIds: Iterable[String]): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, behaviorIds, heartbeat, newInitIds.toList ::: onInitIds , useSerialDispatch, executionContext, tags, onBeatIds, parent, system)

  inline def withOnInitIf(p: => Boolean, newInitIds: Iterable[String]): ActorBuilder[Msg, State] =
    if (p) withOnInit(newInitIds) else this

  inline def withOnInitIf(p: => Boolean, newInitIds: Iterable[String], orElse: => Iterable[String]): ActorBuilder[Msg, State] =
    withOnInit(if (p) newInitIds else orElse)

  inline def addOnInit(tbehId: String): ActorBuilder[Msg, State] = withOnInit(List(tbehId))
	inline def addOnInitIf(p: => Boolean, tbehId: String): ActorBuilder[Msg, State] = withOnInitIf(p, List(tbehId))
	inline def addOnInitIf(p: => Boolean, tbehId: String, orElse: => String): ActorBuilder[Msg, State] = withOnInitIf(p, List(tbehId), List(orElse))

	inline def withNoInit(): ActorBuilder[Msg, State] =
		new ActorBuilder(id, state, behaviorIds, heartbeat, Nil, useSerialDispatch, executionContext, tags, onBeatIds, parent, system)

	inline def withNoInit(p: => Boolean): ActorBuilder[Msg, State] =
		if (p) withNoInit() else this
		
	/**
   * Configures the actor to use a serial dispatch queue.
   *
   * Serial dispatch ensures that messages are processed one at a time in the order they are received, with reduced overhead.
   *
   * @return A new builder configured for serial dispatch
   */
  inline def withSerialDispatch(): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, behaviorIds, heartbeat, onInitIds, useSerialDispatch = true, executionContext = None, tags, onBeatIds, parent, system)

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
    new ActorBuilder(id, state, behaviorIds, heartbeat, onInitIds, useSerialDispatch = false, executionContext = Some(ec), tags, onBeatIds, parent, system)

  inline def withParallelDispatchIf(p: => Boolean, ec: ExecutionContext): ActorBuilder[Msg, State] =
    if (p) withParallelDispatch(ec) else this

  inline def withParallelDispatchIf(p: => Boolean, ec: ExecutionContext, orElse: => ExecutionContext): ActorBuilder[Msg, State] =
    withParallelDispatch(if (p) ec else orElse)

  inline def withTags(tags: Set[ActorTag]): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, behaviorIds, heartbeat, onInitIds, useSerialDispatch, executionContext, tags, onBeatIds, parent, system)

  inline def withTagsIf(p: => Boolean, tags: => Set[ActorTag]): ActorBuilder[Msg, State] =
    if (p) withTags(tags) else this

  inline def withTagsIf(p: => Boolean, tags: => Set[ActorTag], orElse: => Set[ActorTag]): ActorBuilder[Msg, State] =
    withTags(if (p) tags else orElse)
    
  inline def withNoTags(): ActorBuilder[Msg, State] = withTags(Set.empty)

  inline def withNoTagsIf(p: => Boolean): ActorBuilder[Msg, State] = if (p) withNoTags() else this

  inline def addTag(tag: ActorTag): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, behaviorIds, heartbeat, onInitIds, useSerialDispatch, executionContext, tags + tag, onBeatIds, parent, system)

  inline def removeTag(tag: ActorTag): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, behaviorIds, heartbeat, onInitIds, useSerialDispatch, executionContext, tags - tag, onBeatIds, parent, system)

  inline def withOnBeat(newBeatIds: Iterable[String]): ActorBuilder[Msg, State] =
	  new ActorBuilder(id, state, behaviorIds, heartbeat, onInitIds, useSerialDispatch, executionContext, tags, newBeatIds.toList ::: onBeatIds, parent, system)

	inline def withOnBeatIf(p: => Boolean, newBeatIds: Iterable[String]): ActorBuilder[Msg, State] =
		if (p) withOnBeat(newBeatIds) else this

	inline def withOnBeatIf(p: => Boolean, newBeatIds: => Iterable[String], orElse: => Iterable[String]): ActorBuilder[Msg, State] =
		withOnBeat(if (p) newBeatIds else orElse)

	inline def withNoBeat(): ActorBuilder[Msg, State] =
		new ActorBuilder(id, state, behaviorIds, heartbeat, onInitIds, useSerialDispatch, executionContext, tags, Nil, parent, system)

	inline def withNoBeatIf(p: => Boolean): ActorBuilder[Msg, State] = if (p) withNoBeat() else this

	inline def withParent(parent: Actor[Msg, State]): ActorBuilder[Msg, State] =
    withParent(LocalActorRef[Msg](parent))

  inline def withParent(parent: ActorRef[Msg]): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, behaviorIds, heartbeat, onInitIds, useSerialDispatch, executionContext, tags, onBeatIds, Some(parent), system)

  inline def withParentIf(p: => Boolean, parent: => ActorRef[Msg]): ActorBuilder[Msg, State] =
    if (p) withParent(parent) else this

  inline def withParentIf(p: => Boolean, parent: => ActorRef[Msg], orElse: => ActorRef[Msg]): ActorBuilder[Msg, State] =
    withParent(if (p) parent else orElse)

  inline def withNoParent(): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, behaviorIds, heartbeat, onInitIds, useSerialDispatch, executionContext, tags, onBeatIds, parent = None, system)

  inline def withNoParentIf(p: => Boolean): ActorBuilder[Msg, State] =
    if (p) withNoParent() else this

  inline def withSystem(system: ActorSystem[Msg, ?]): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, behaviorIds, heartbeat, onInitIds, useSerialDispatch, executionContext, tags, onBeatIds, parent, Some(system))

  inline def withSystemIf(p: => Boolean, system: => ActorSystem[Msg, ?]): ActorBuilder[Msg, State] =
    if (p) withSystem(system) else this

  inline def withSystemIf(p: => Boolean, system: => ActorSystem[Msg, ?], orElse: => ActorSystem[Msg, ?]): ActorBuilder[Msg, State] =
    withSystem(if (p) system else orElse)

  inline def withNoSystem(): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, behaviorIds, heartbeat, onInitIds, useSerialDispatch, executionContext, tags, onBeatIds, parent, None)

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
    onInitIds.foreach(actor.onInitById)
	  val behaviors = behaviorIds.flatMap(Behaviors.getPBeh[Msg, State])
	  val invalidIds = behaviorIds.toSet -- behaviors.map(_.id).toSet
	  if (invalidIds.nonEmpty) {
		  println(s"Invalid behavior ids found when building Actor ${id}: $invalidIds")
	  }
    actor.addBehaviors(behaviors)
	  onBeatIds.foreach(actor.onBeatById)
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
