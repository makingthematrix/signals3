package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.DispatchQueue
import io.github.makingthematrix.signals3.actors.Actor.{Beh, HeartBeatStrategy, PF, defBeat, serial}

import scala.concurrent.ExecutionContext

final class ActorBuilder[Msg, Rsp, State] (
  private val id: String = "",
  private val state: Option[State] = None,
  private val behaviors: List[Beh[Msg, Rsp, State]] = Nil,
  private val heartbeat: HeartBeatStrategy = defBeat,
  private val onInit: Option[MutableActor[Msg, Rsp, State] => Unit] = None,
  private val useSerialDispatch: Boolean = false,
  private val executionContext: Option[ExecutionContext] = None,
  private val parent: Option[Actor[Msg, Rsp, State]] = None                                          
) {

  /**
    * Sets the id of the actor.
    * 
    * @param newId The new id
    * @return A new builder with the new id
    */
  inline def withId(newId: String): ActorBuilder[Msg, Rsp, State] =
    new ActorBuilder(newId, state, behaviors, heartbeat, onInit, useSerialDispatch, executionContext, parent)
    
  inline def withIdIf(p: => Boolean, newId: String): ActorBuilder[Msg, Rsp, State] =
    if (p) withId(newId) else this

  inline def withIdIf(p: => Boolean, newId: String, orElse: String): ActorBuilder[Msg, Rsp, State] =
    withId(if (p) newId else orElse)
  /**
   * Sets the initial state of the actor.
   *
   * @param newState The new initial state
   * @return A new builder with the updated state
   */
  inline def withState(newState: State): ActorBuilder[Msg, Rsp, State] =
    new ActorBuilder(id, Option(newState), behaviors, heartbeat, onInit, useSerialDispatch, executionContext, parent)

  inline def withStateIf(p: => Boolean, newState: State): ActorBuilder[Msg, Rsp, State] =
    if (p) withState(newState) else this

  inline def withStateIf(p: => Boolean, newState: State, orElse: State): ActorBuilder[Msg, Rsp, State] =
    withState(if (p) newState else orElse)  
  /**
   * Adds a behavior with an explicit ID.
   *
   * @param id   The unique identifier for this behavior
   * @param pf   The partial function defining the behavior
   * @return A new builder with the added behavior
   */
  inline def withBehavior(id: String, pf: PF[Msg, Rsp, State]): ActorBuilder[Msg, Rsp, State] =
    withBehavior(id -> pf)

  inline def withBehaviorIf(p: => Boolean, id: String, pf: PF[Msg, Rsp, State]): ActorBuilder[Msg, Rsp, State] =
    if (p) withBehavior(id, pf) else this

  inline def withBehaviorIf(p: => Boolean, id: String, pf: PF[Msg, Rsp, State], orElse: PF[Msg, Rsp, State]): ActorBuilder[Msg, Rsp, State] =
    withBehavior(id, if (p) pf else orElse) 

  /**
   * Adds a behavior with an auto-generated UUID.
   *
   * @param pf The partial function defining the behavior
   * @return A new builder with the added behavior
   */
  inline def withBehavior(pf: PF[Msg, Rsp, State]): ActorBuilder[Msg, Rsp, State] =
    withBehavior(IdGenerator.generate("beh:"), pf)

  inline def withBehaviorIf(p: => Boolean, pf: PF[Msg, Rsp, State], orElse: PF[Msg, Rsp, State]): ActorBuilder[Msg, Rsp, State] =
    withBehavior(if (p) pf else orElse)

  /**
   * Adds a behavior as a Beh tuple (id, pf).
   *
   * @param behavior The behavior tuple (id, partial function)
   * @return A new builder with the added behavior
   */
  inline def withBehavior(behavior: Beh[Msg, Rsp, State]): ActorBuilder[Msg, Rsp, State] =
    new ActorBuilder(id, state, behavior :: behaviors, heartbeat, onInit, useSerialDispatch, executionContext, parent)

  inline def withBehaviorIf(p: => Boolean, behavior: Beh[Msg, Rsp, State]): ActorBuilder[Msg, Rsp, State] =
    if (p) withBehavior(behavior) else this

  inline def withBehaviorIf(p: => Boolean, behavior: Beh[Msg, Rsp, State], orElse: Beh[Msg, Rsp, State]): ActorBuilder[Msg, Rsp, State] =
    withBehavior(if (p) behavior else orElse)

  /**
   * Adds multiple behaviors with explicit IDs.
   *
   * @param newBehaviors A collection of behavior tuples to add
   * @return A new builder with the added behaviors
   */
  inline def withBehaviors(newBehaviors: Iterable[Beh[Msg, Rsp, State]]): ActorBuilder[Msg, Rsp, State] =
    new ActorBuilder(id, state, newBehaviors.toList ::: behaviors, heartbeat, onInit, useSerialDispatch, executionContext, parent)

  inline def withBehaviorsIf(p: => Boolean, newBehaviors: Iterable[Beh[Msg, Rsp, State]]): ActorBuilder[Msg, Rsp, State] =
    if (p) withBehaviors(newBehaviors) else this

  inline def withBehaviorsIf(p: => Boolean, newBehaviors: Iterable[Beh[Msg, Rsp, State]], orElse: Iterable[Beh[Msg, Rsp, State]]): ActorBuilder[Msg, Rsp, State] =
    withBehaviors(if (p) newBehaviors else orElse)

  /**
   * Adds multiple behaviors with auto-generated IDs.
   *
   * @param newBehaviors A collection of partial functions to add
   * @return A new builder with the added behaviors
   */
  inline def withBehaviorPFs(newBehaviors: Iterable[PF[Msg, Rsp, State]]): ActorBuilder[Msg, Rsp, State] =
    withBehaviors(newBehaviors.map(pf => IdGenerator.generate("beh:") -> pf))

  inline def withBehaviorPFsIf(p: => Boolean, newBehaviors: Iterable[PF[Msg, Rsp, State]]): ActorBuilder[Msg, Rsp, State] =
    if (p) withBehaviorPFs(newBehaviors) else this

  inline def withBehaviorPFsIf(p: => Boolean, newBehaviors: Iterable[PF[Msg, Rsp, State]], orElse: Iterable[PF[Msg, Rsp, State]]): ActorBuilder[Msg, Rsp, State] =
    withBehaviorPFs(if (p) newBehaviors else orElse)

  /**
   * Sets the heartbeat strategy.
   *
   * @param newHeartbeat The heartbeat strategy to use
   * @return A new builder with the updated heartbeat strategy
   */
  inline def withHeartbeat(newHeartbeat: HeartBeatStrategy): ActorBuilder[Msg, Rsp, State] =
    new ActorBuilder(id, state, behaviors, newHeartbeat, onInit, useSerialDispatch, executionContext, parent)

  inline def withHeartbeatIf(p: => Boolean, newHeartbeat: HeartBeatStrategy): ActorBuilder[Msg, Rsp, State] =
    if (p) withHeartbeat(newHeartbeat) else this

  inline def withHeartbeatIf(p: => Boolean, newHeartbeat: HeartBeatStrategy, orElse: HeartBeatStrategy): ActorBuilder[Msg, Rsp, State] =
    withHeartbeat(if (p) newHeartbeat else orElse)

  /**
   * Sets a linear heartbeat strategy with the specified interval.
   *
   * @param ms The interval in milliseconds
   * @return A new builder with the linear heartbeat strategy
   */
  inline def withLinearHeartbeat(ms: Long): ActorBuilder[Msg, Rsp, State] =
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
  inline def withAgitatedHeartbeat(minMs: Long, coeff: Double, maxMs: Long): ActorBuilder[Msg, Rsp, State] =
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
  inline def withReactiveHeartbeat(maxMs: Long, maxMsgs: Int): ActorBuilder[Msg, Rsp, State] =
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
  inline def withOnInit(callback: MutableActor[Msg, Rsp, State] => Unit): ActorBuilder[Msg, Rsp, State] =
    new ActorBuilder(id, state, behaviors, heartbeat, Some(callback), useSerialDispatch, executionContext, parent)

  inline def withOnInitIf(p: => Boolean, callback: MutableActor[Msg, Rsp, State] => Unit): ActorBuilder[Msg, Rsp, State] =
    if (p) withOnInit(callback) else this

  inline def withOnInitIf(p: => Boolean, callback: MutableActor[Msg, Rsp, State] => Unit, orElse: MutableActor[Msg, Rsp, State] => Unit): ActorBuilder[Msg, Rsp, State] =
    withOnInit(if (p) callback else orElse)
    
  /**
   * Configures the actor to use a serial dispatch queue.
   *
   * Serial dispatch ensures that messages are processed one at a time in the order they are received, with reduced overhead.
   *
   * @return A new builder configured for serial dispatch
   */
  inline def withSerialDispatch(): ActorBuilder[Msg, Rsp, State] =
    new ActorBuilder(id, state, behaviors, heartbeat, onInit, useSerialDispatch = true, executionContext = None, parent)

  inline def withSerialDispatchIf(p: => Boolean): ActorBuilder[Msg, Rsp, State] =
    if (p) withSerialDispatch() else this
  
  inline def withParallelDispatch(ec: ExecutionContext): ActorBuilder[Msg, Rsp, State] =
    new ActorBuilder(id, state, behaviors, heartbeat, onInit, useSerialDispatch = false, executionContext = Some(ec), parent)

  inline def withParallelDispatchIf(p: => Boolean, ec: ExecutionContext): ActorBuilder[Msg, Rsp, State] =
    if (p) withParallelDispatch(ec) else this

  inline def withParallelDispatchIf(p: => Boolean, ec: ExecutionContext, orElse: ExecutionContext): ActorBuilder[Msg, Rsp, State] =
    withParallelDispatch(if (p) ec else orElse)

  inline def withParent(parent: Actor[Msg, Rsp, State]): ActorBuilder[Msg, Rsp, State] =
    new ActorBuilder(id, state, behaviors, heartbeat, onInit, useSerialDispatch, executionContext, Some(parent))

  inline def withParentIf(p: => Boolean, parent: Actor[Msg, Rsp, State]): ActorBuilder[Msg, Rsp, State] =
    if (p) withParent(parent) else this

  inline def withParentIf(p: => Boolean, parent: Actor[Msg, Rsp, State], orElse: Actor[Msg, Rsp, State]): ActorBuilder[Msg, Rsp, State] =
    withParent(if (p) parent else orElse)

  inline def withNoParent(): ActorBuilder[Msg, Rsp, State] =
    new ActorBuilder(id, state, behaviors, heartbeat, onInit, useSerialDispatch, executionContext, None)

  inline def withNoParentIf(p: => Boolean): ActorBuilder[Msg, Rsp, State] = 
    if (p) withNoParent() else this
    
  def build()(using ec: ExecutionContext): Actor[Msg, Rsp, State] = { 
    assert(state.nonEmpty)
    if (useSerialDispatch) buildActor(state.get, DispatchQueue(DispatchQueue.Serial, ExecutionContext.global))
    else buildActor(state.get, executionContext.getOrElse(ec))
  }

  private def buildActor(state: State, ec: ExecutionContext): Actor[Msg, Rsp, State] = {
    val actor = new ActorImpl[Msg, Rsp, State](id, state, heartbeat, parent)(using ec)
    onInit.foreach(actor.onInit)
    behaviors match {
      case Nil         => () // no behaviors to add
      case beh :: Nil  => actor.addBehavior(beh)
      case _           => actor.addBehaviors(extractPFs(behaviors))
    }
    actor.initialize()
    actor
  }

  /**
   * Extracts the PF from a list of Beh tuples.
   */
  private def extractPFs(behaviors: List[Beh[Msg, Rsp, State]]): List[PF[Msg, Rsp, State]] =
    behaviors.map { case (_, pf) => pf }
}

/**
 * Companion object for ActorBuilder with factory methods and pre-defined strategies.
 */
object ActorBuilder {

  /**
   * Creates a new ActorBuilder with the specified initial state.
   *
   * @tparam Msg   The type of incoming messages
   * @tparam Rsp   The type of responses
   * @tparam State The type of internal state
   * @return A new ActorBuilder instance
   */
  def apply[Msg, Rsp, State](): ActorBuilder[Msg, Rsp, State] =
    new ActorBuilder(IdGenerator.generate(), None, Nil, defBeat, None, false)
  
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
