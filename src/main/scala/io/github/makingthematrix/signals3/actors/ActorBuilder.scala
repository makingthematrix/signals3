package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.{Closeable, DispatchQueue, Pausable}
import io.github.makingthematrix.signals3.actors.Actor.{Beh, HeartBeatStrategy, PF, defBeat}

import scala.concurrent.ExecutionContext

/**
  * A builder for creating and configuring Actor instances.
  *
  * ActorBuilder provides a way to construct actors by chaining configuration methods.
  * It supports setting the actor's ID, initial state, behaviors, heartbeat strategy, initialization
  * callbacks, dispatch mode, execution context, parent actor, and actor system.
  *
  * The builder pattern allows for optional and conditional configuration through methods like
  * `withIdIf`, `withStateIf`, etc., which apply settings based on boolean predicates.
  *
  * @tparam Msg   The type of incoming messages the actor will process
  * @tparam State The type of internal state maintained by the actor
  * @param id                The unique identifier for the actor (defaults to auto-generated)
  * @param state             The initial state of the actor (must be set before building)
  * @param behaviors         The list of behaviors that define how the actor processes messages
  * @param heartbeat         The heartbeat strategy controlling message processing intervals
  * @param onInit            Optional initialization callback invoked when the actor starts
  * @param useSerialDispatch Whether to use serial (single-threaded) dispatch queue
  * @param executionContext  Optional execution context for parallel dispatch
  * @param parent            Optional parent actor in the actor hierarchy
  * @param system            Optional actor system this actor belongs to
  */
final class ActorBuilder[Msg, State] (
  private val id: String,
  private val state: State,
  private val behaviors: List[Beh[Msg, State]] = Nil,
  private val heartbeat: HeartBeatStrategy = defBeat,
  private val onInit: Option[MutableActor[Msg, State] => Unit] = None,
  private val useSerialDispatch: Boolean = false,
  private val executionContext: Option[ExecutionContext] = None,
  private val parent: Option[Actor[Msg, State]] = None,
  private val system: Option[ActorSystem[Msg, State]] = None
) {

  /**
    * Sets the id of the actor.
    * 
    * @param newId The new id
    * @return A new builder with the new id
    */
  inline def withId(newId: String): ActorBuilder[Msg, State] =
    new ActorBuilder(newId, state, behaviors, heartbeat, onInit, useSerialDispatch, executionContext, parent, system)
    
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
    new ActorBuilder(id, newState, behaviors, heartbeat, onInit, useSerialDispatch, executionContext, parent, system)

  inline def withStateIf(p: => Boolean, newState: => State): ActorBuilder[Msg, State] =
    if (p) withState(newState) else this

  inline def withStateIf(p: => Boolean, newState: => State, orElse: => State): ActorBuilder[Msg, State] =
    withState(if (p) newState else orElse)

  /**
   * Adds a behavior with an auto-generated UUID.
   *
   * @param pf The partial function defining the behavior
   * @return A new builder with the added behavior
   */
  inline def withBehaviorPF(pf: PF[Msg, State]): ActorBuilder[Msg, State] =
    withBehavior(IdGenerator.generate("beh:"), pf)

  inline def withBehaviorPFIf(p: => Boolean, pf: => PF[Msg, State]): ActorBuilder[Msg, State] =
    if (p) withBehaviorPF(pf) else this

  inline def withBehaviorPFIf(p: => Boolean, pf: => PF[Msg, State], orElse: => PF[Msg, State]): ActorBuilder[Msg, State] =
    withBehaviorPF(if (p) pf else orElse)

  /**
   * Adds a behavior as a Beh tuple (id, pf).
   *
   * @param behavior The behavior tuple (id, partial function)
   * @return A new builder with the added behavior
   */
  inline def withBehavior(behavior: Beh[Msg, State]): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, behavior :: behaviors, heartbeat, onInit, useSerialDispatch, executionContext, parent, system)

  inline def withBehaviorIf(p: => Boolean, behavior: => Beh[Msg, State]): ActorBuilder[Msg, State] =
    if (p) withBehavior(behavior) else this

  inline def withBehaviorIf(p: => Boolean, behavior: => Beh[Msg, State], orElse: => Beh[Msg, State]): ActorBuilder[Msg, State] =
    withBehavior(if (p) behavior else orElse)

  /**
   * Adds multiple behaviors with explicit IDs.
   *
   * @param newBehaviors A collection of behavior tuples to add
   * @return A new builder with the added behaviors
   */
  inline def withBehaviors(newBehaviors: Iterable[Beh[Msg, State]]): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, newBehaviors.toList ::: behaviors, heartbeat, onInit, useSerialDispatch, executionContext, parent, system)

  inline def withBehaviorsIf(p: => Boolean, newBehaviors: => Iterable[Beh[Msg, State]]): ActorBuilder[Msg, State] =
    if (p) withBehaviors(newBehaviors) else this

  inline def withBehaviorsIf(p: => Boolean, newBehaviors: => Iterable[Beh[Msg, State]], orElse: => Iterable[Beh[Msg, State]]): ActorBuilder[Msg, State] =
    withBehaviors(if (p) newBehaviors else orElse)

  /**
   * Adds multiple behaviors with auto-generated IDs.
   *
   * @param newBehaviors A collection of partial functions to add
   * @return A new builder with the added behaviors
   */
  inline def withBehaviorPFs(newBehaviors: Iterable[PF[Msg, State]]): ActorBuilder[Msg, State] =
    withBehaviors(newBehaviors.map(pf => IdGenerator.generate("beh:") -> pf))

  inline def withBehaviorPFsIf(p: => Boolean, newBehaviors: => Iterable[PF[Msg, State]]): ActorBuilder[Msg, State] =
    if (p) withBehaviorPFs(newBehaviors) else this

  inline def withBehaviorPFsIf(p: => Boolean, newBehaviors: => Iterable[PF[Msg, State]], orElse: => Iterable[PF[Msg, State]]): ActorBuilder[Msg, State] =
    withBehaviorPFs(if (p) newBehaviors else orElse)

  /**
   * Sets the heartbeat strategy.
   *
   * @param newHeartbeat The heartbeat strategy to use
   * @return A new builder with the updated heartbeat strategy
   */
  inline def withHeartbeat(newHeartbeat: HeartBeatStrategy): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, behaviors, newHeartbeat, onInit, useSerialDispatch, executionContext, parent, system)

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
    withHeartbeat(HeartBeatStrategy.Linear(ms))

  /**
   * Sets an agitated heartbeat strategy.
   *
   * The interval starts at minMs, grows by coeff when idle (up to maxMs),
   * and resets when messages arrive.
   *
   * @param minMs  The minimum interval in milliseconds
   * @param coeff  The growth coefficient (e.g., 1.5 means 50% increase)
   * @param maxMs  The maximum interval in milliseconds
   * @return A new builder with the agitated heartbeat strategy
   */
  inline def withAgitatedHeartbeat(minMs: Long, coeff: Double, maxMs: Long): ActorBuilder[Msg, State] =
    withHeartbeat(HeartBeatStrategy.Agitated(minMs, coeff, maxMs))
  
  /**
   * Sets a reactive heartbeat strategy.
   *
   * Triggers processing when either maxMs time elapses OR maxMsgs messages are queued.
   *
   * @param maxMs    The maximum time interval in milliseconds
   * @param maxMsgs  The maximum number of messages to queue before triggering
   * @return A new builder with the reactive heartbeat strategy
   */
  inline def withReactiveHeartbeat(maxMs: Long, maxMsgs: Int): ActorBuilder[Msg, State] =
    withHeartbeat(HeartBeatStrategy.Reactive(maxMs, maxMsgs))

  /**
   * Sets the initialization callback.
   *
   * The callback is invoked exactly once when the actor is initialized,
   * before message processing begins.
   *
   * @param callback The function to call on initialization
   * @return A new builder with the initialization callback
   */
  inline def withOnInit(callback: MutableActor[Msg, State] => Unit): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, behaviors, heartbeat, Some(callback), useSerialDispatch, executionContext, parent, system)

  inline def withOnInitIf(p: => Boolean, callback: => (MutableActor[Msg, State] => Unit)): ActorBuilder[Msg, State] =
    if (p) withOnInit(callback) else this

  inline def withOnInitIf(p: => Boolean, callback: => (MutableActor[Msg, State] => Unit), orElse: => (MutableActor[Msg, State] => Unit)): ActorBuilder[Msg, State] =
    withOnInit(if (p) callback else orElse)
    
  /**
   * Configures the actor to use a serial dispatch queue.
   *
   * Serial dispatch ensures that messages are processed one at a time in the order they are received, with reduced overhead.
   *
   * @return A new builder configured for serial dispatch
   */
  inline def withSerialDispatch(): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, behaviors, heartbeat, onInit, useSerialDispatch = true, executionContext = None, parent, system)

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
    new ActorBuilder(id, state, behaviors, heartbeat, onInit, useSerialDispatch = false, executionContext = Some(ec), parent, system)

  inline def withParallelDispatchIf(p: => Boolean, ec: ExecutionContext): ActorBuilder[Msg, State] =
    if (p) withParallelDispatch(ec) else this

  inline def withParallelDispatchIf(p: => Boolean, ec: ExecutionContext, orElse: => ExecutionContext): ActorBuilder[Msg, State] =
    withParallelDispatch(if (p) ec else orElse)

  inline def withParent(parent: Actor[Msg, State]): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, behaviors, heartbeat, onInit, useSerialDispatch, executionContext, Some(parent), system)

  inline def withParentIf(p: => Boolean, parent: => Actor[Msg, State]): ActorBuilder[Msg, State] =
    if (p) withParent(parent) else this

  inline def withParentIf(p: => Boolean, parent: => Actor[Msg, State], orElse: => Actor[Msg, State]): ActorBuilder[Msg, State] =
    withParent(if (p) parent else orElse)

  inline def withNoParent(): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, behaviors, heartbeat, onInit, useSerialDispatch, executionContext, None, system)

  inline def withNoParentIf(p: => Boolean): ActorBuilder[Msg, State] =
    if (p) withNoParent() else this

  inline def withSystem(system: ActorSystem[Msg, State]): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, behaviors, heartbeat, onInit, useSerialDispatch, executionContext, parent, Some(system))

  inline def withSystemIf(p: => Boolean, system: => ActorSystem[Msg, State]): ActorBuilder[Msg, State] =
    if (p) withSystem(system) else this

  inline def withSystemIf(p: => Boolean, system: => ActorSystem[Msg, State], orElse: => ActorSystem[Msg, State]): ActorBuilder[Msg, State] =
    withSystem(if (p) system else orElse)

  inline def withNoSystem(): ActorBuilder[Msg, State] =
    new ActorBuilder(id, state, behaviors, heartbeat, onInit, useSerialDispatch, executionContext, parent, None)

  inline def withNoSystemIf(p: => Boolean): ActorBuilder[Msg, State] =
    if (p) withNoSystem() else this
    
  def build()(using ec: ExecutionContext): Actor[Msg, State] & Closeable & Pausable = {
    if (useSerialDispatch)
      buildActor(state, DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))
    else
      buildActor(state, executionContext.getOrElse(ec))
  }

  private def buildActor(state: State, ec: ExecutionContext): Actor[Msg, State] & Closeable & Pausable = {
    val actor = new ActorImpl[Msg, State](id, state, heartbeat, parent, system)(using ec)
    onInit.foreach(actor.onInit)
    actor.addBehaviors(behaviors)
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

  // Pre-defined heartbeat strategies for convenience

  /**
   * Linear heartbeat with 100ms interval.
   */
  val Linear100ms: HeartBeatStrategy = HeartBeatStrategy.Linear(100L)

  /**
   * Linear heartbeat with 500ms interval.
   */
  val Linear500ms: HeartBeatStrategy = HeartBeatStrategy.Linear(500L)

  /**
   * Linear heartbeat with 1 second interval.
   */
  val Linear1s: HeartBeatStrategy = HeartBeatStrategy.Linear(1000L)

  /**
   * Reactive heartbeat with 100ms max interval and 10 message threshold.
   */
  val Reactive100ms10: HeartBeatStrategy = HeartBeatStrategy.Reactive(100L, 10)

  /**
   * Reactive heartbeat with 50ms max interval and 5 message threshold.
   */
  val Reactive50ms5: HeartBeatStrategy = HeartBeatStrategy.Reactive(50L, 5)

  /**
   * Agitated heartbeat with 50ms min, 1.5x growth, 500ms max.
   */
  val Agitated50to500: HeartBeatStrategy = HeartBeatStrategy.Agitated(50L, 1.5, 500L)

  /**
   * Agitated heartbeat with 100ms min, 2x growth, 1000ms max.
   */
  val Agitated100to1000: HeartBeatStrategy = HeartBeatStrategy.Agitated(100L, 2.0, 1000L)
}
