package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.DispatchQueue

import java.util.UUID
import scala.concurrent.ExecutionContext

final class ActorBuilder[Msg, Rsp, State] (
  private val state: State,
  private val behaviors: List[Actor.Beh[Msg, Rsp, State]],
  private val heartbeat: Actor.HeartBeatStrategy,
  private val onInit: Option[MutableActor[Msg, Rsp, State] => Unit],
  private val useSerialDispatch: Boolean
) {

  /**
   * Sets the initial state of the actor.
   *
   * @param newState The new initial state
   * @return A new builder with the updated state
   */
  def withState(newState: State): ActorBuilder[Msg, Rsp, State] =
    new ActorBuilder(newState, behaviors, heartbeat, onInit, useSerialDispatch)

  /**
   * Adds a behavior with an explicit ID.
   *
   * @param id   The unique identifier for this behavior
   * @param pf   The partial function defining the behavior
   * @return A new builder with the added behavior
   */
  def withBehavior(id: String, pf: Actor.PF[Msg, Rsp, State]): ActorBuilder[Msg, Rsp, State] =
    withBehavior(id -> pf)

  /**
   * Adds a behavior with an auto-generated UUID.
   *
   * @param pf The partial function defining the behavior
   * @return A new builder with the added behavior
   */
  def withBehavior(pf: Actor.PF[Msg, Rsp, State]): ActorBuilder[Msg, Rsp, State] =
    withBehavior(ActorBuilder.generateId(), pf)

  /**
   * Adds a behavior as a Beh tuple (id, pf).
   *
   * @param behavior The behavior tuple (id, partial function)
   * @return A new builder with the added behavior
   */
  def withBehavior(behavior: Actor.Beh[Msg, Rsp, State]): ActorBuilder[Msg, Rsp, State] =
    new ActorBuilder(state, behavior :: behaviors, heartbeat, onInit, useSerialDispatch)

  /**
   * Adds multiple behaviors with explicit IDs.
   *
   * @param newBehaviors A collection of behavior tuples to add
   * @return A new builder with the added behaviors
   */
  def withBehaviors(newBehaviors: Iterable[Actor.Beh[Msg, Rsp, State]]): ActorBuilder[Msg, Rsp, State] =
    new ActorBuilder(state, newBehaviors.toList ::: behaviors, heartbeat, onInit, useSerialDispatch)

  /**
   * Adds multiple behaviors with auto-generated IDs.
   *
   * @param newBehaviors A collection of partial functions to add
   * @return A new builder with the added behaviors
   */
  def withBehaviorPFs(newBehaviors: Iterable[Actor.PF[Msg, Rsp, State]]): ActorBuilder[Msg, Rsp, State] =
    withBehaviors(newBehaviors.map(pf => ActorBuilder.generateId() -> pf))

  /**
   * Sets the heartbeat strategy.
   *
   * @param newHeartbeat The heartbeat strategy to use
   * @return A new builder with the updated heartbeat strategy
   */
  def withHeartbeat(newHeartbeat: Actor.HeartBeatStrategy): ActorBuilder[Msg, Rsp, State] =
    new ActorBuilder(state, behaviors, newHeartbeat, onInit, useSerialDispatch)

  /**
   * Sets a linear heartbeat strategy with the specified interval.
   *
   * @param ms The interval in milliseconds
   * @return A new builder with the linear heartbeat strategy
   */
  def withLinearHeartbeat(ms: Long): ActorBuilder[Msg, Rsp, State] =
    withHeartbeat(Actor.HeartBeatStrategy.Linear(ms))

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
  def withAgitatedHeartbeat(minMs: Long, coeff: Double, maxMs: Long): ActorBuilder[Msg, Rsp, State] =
    withHeartbeat(Actor.HeartBeatStrategy.Agitated(minMs, coeff, maxMs))

  /**
   * Sets a reactive heartbeat strategy.
   *
   * Triggers processing when either maxMs time elapses OR maxMsgs messages are queued.
   *
   * @param maxMs    The maximum time interval in milliseconds
   * @param maxMsgs  The maximum number of messages to queue before triggering
   * @return A new builder with the reactive heartbeat strategy
   */
  def withReactiveHeartbeat(maxMs: Long, maxMsgs: Int): ActorBuilder[Msg, Rsp, State] =
    withHeartbeat(Actor.HeartBeatStrategy.Reactive(maxMs, maxMsgs))

  /**
   * Sets the initialization callback.
   *
   * The callback is invoked exactly once when the actor is initialized,
   * before message processing begins.
   *
   * @param callback The function to call on initialization
   * @return A new builder with the initialization callback
   */
  def withOnInit(callback: MutableActor[Msg, Rsp, State] => Unit): ActorBuilder[Msg, Rsp, State] =
    new ActorBuilder(state, behaviors, heartbeat, Some(callback), useSerialDispatch)

  /**
   * Configures the actor to use a serial dispatch queue.
   *
   * Serial dispatch ensures that messages are processed one at a time in the order they are received, with reduced overhead.
   *
   * @return A new builder configured for serial dispatch
   */
  def withSerialDispatch(): ActorBuilder[Msg, Rsp, State] =
    new ActorBuilder(state, behaviors, heartbeat, onInit, useSerialDispatch = true)

  def build()(using ec: ExecutionContext): Actor[Msg, Rsp, State] = 
    if (useSerialDispatch) buildSerial() else buildParallel(ec)

  /**
   * Builds an actor using serial dispatch.
   *
   * Note: Serial actors always use ExecutionContext.global internally so the custom execution context is ignored for serial dispatch.
   */
  private def buildSerial(): Actor[Msg, Rsp, State] = (behaviors, onInit) match {
    case (beh :: Nil, None)       => Actor.serial(state, beh, heartbeat) // Single behavior, no onInit
    case (beh :: Nil, Some(init)) => Actor.serial(state, beh, heartbeat, init) // Single behavior, with onInit
    case (_, None)                => Actor.serial(state, extractPFs(behaviors), heartbeat) // Multiple behaviors, no onInit
    case (_, Some(init))          => Actor.serial(state, extractPFs(behaviors), heartbeat, init) // Multiple behaviors, with onInit
  }

  /**
   * Builds an actor using parallel (non-serial) dispatch.
   */
  private def buildParallel(ec: ExecutionContext): Actor[Msg, Rsp, State] = (behaviors, onInit) match {
    case (beh :: Nil, None)       => Actor(state, beh, heartbeat)(using ec) // Single behavior, no onInit
    case (beh :: Nil, Some(init)) => Actor(state, beh, heartbeat, init)(using ec) // Single behavior, with onInit
    case (_, None)                => Actor(state, extractPFs(behaviors), heartbeat)(using ec) // Multiple behaviors, no onInit
    case (_, Some(init))          => Actor(state, extractPFs(behaviors), heartbeat, init)(using ec) // Multiple behaviors, with onInit
  }

  /**
   * Extracts the PF from a list of Beh tuples.
   */
  private def extractPFs(behaviors: List[Actor.Beh[Msg, Rsp, State]]): List[Actor.PF[Msg, Rsp, State]] =
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
   * @param state  The initial state of the actor
   * @return A new ActorBuilder instance
   */
  def apply[Msg, Rsp, State](state: State): ActorBuilder[Msg, Rsp, State] =
    new ActorBuilder(state, Nil, Actor.defBeat, None, useSerialDispatch = false)

  /**
   * Generates a unique ID for behaviors.
   *
   * @return A unique UUID string
   */
  private def generateId(): String = UUID.randomUUID().toString

  // Pre-defined heartbeat strategies for convenience

  /**
   * Linear heartbeat with 100ms interval.
   */
  val Linear100ms: Actor.HeartBeatStrategy = Actor.HeartBeatStrategy.Linear(100L)

  /**
   * Linear heartbeat with 500ms interval.
   */
  val Linear500ms: Actor.HeartBeatStrategy = Actor.HeartBeatStrategy.Linear(500L)

  /**
   * Linear heartbeat with 1 second interval.
   */
  val Linear1s: Actor.HeartBeatStrategy = Actor.HeartBeatStrategy.Linear(1000L)

  /**
   * Reactive heartbeat with 100ms max interval and 10 message threshold.
   */
  val Reactive100ms10: Actor.HeartBeatStrategy = Actor.HeartBeatStrategy.Reactive(100L, 10)

  /**
   * Reactive heartbeat with 50ms max interval and 5 message threshold.
   */
  val Reactive50ms5: Actor.HeartBeatStrategy = Actor.HeartBeatStrategy.Reactive(50L, 5)

  /**
   * Agitated heartbeat with 50ms min, 1.5x growth, 500ms max.
   */
  val Agitated50to500: Actor.HeartBeatStrategy = Actor.HeartBeatStrategy.Agitated(50L, 1.5, 500L)

  /**
   * Agitated heartbeat with 100ms min, 2x growth, 1000ms max.
   */
  val Agitated100to1000: Actor.HeartBeatStrategy = Actor.HeartBeatStrategy.Agitated(100L, 2.0, 1000L)
}
