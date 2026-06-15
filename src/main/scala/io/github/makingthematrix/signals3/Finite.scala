package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.priv.{ChainedFiniteSignal, ChainedSignal, ChainedStream, ChainedFiniteStream}

import scala.annotation.targetName
import scala.concurrent.{Future, Promise}
import scala.util.chaining.scalaUtilChainingOps

/**
 * A common supertrait for streams and signals that can be closed by internal logic (NOT by the user).
 * A typical case is the `.take` method on a stream - it creates a [[TakeStream]] that closes by itself
 * after a given number of events.
 * @tparam T The type of the stream's event or the signal's value
 */
trait Finite[T] extends CanBeClosed {
  protected[signals3] final inline def close(): Unit = closeAndCheck()

  protected var lastPromise: Option[Promise[T]] = None

  /**
   * A future that will be completed with the last event published to the stream or signal.
   *
   * Take note that `Finite` does not declare `init`, i.e. a stream of all events except the last one.
   * This is because while we can be sure what was the last event before closing the stream/signal, in most implementations
   * we get to know it only after the stream/signal is closed. To be able to reliably push events into `init`, we would need
   * to know it beforehand.
   * See [[TakeStream]] and [[TakeSignal]] for examples of classes that implement `init`.
   */
  final lazy val last: Future[T] = Promise[T]().tap { p => lastPromise = Some(p) }.future
}
  
object Finite {
  type FiniteStream[E] = Stream[E] & Finite[E]
  type FiniteSignal[V] = Signal[V] & Finite[V]

  // todo: Allow for chaining streams of subtypes
  extension [E](stream: FiniteStream[E]) {
    /**
      * Chains two streams of the same event type where the first one is finite.
      * The new stream will emit events from the first (finite) stream until it closes and then switch to the second one.
      * If the second stream is created or initialized in the chain, the creation/initialization will be postponed until
      * the first stream closes.
      * @param next the second stream
      * @return A new chained stream
      */
    @targetName("chain")
    inline def ::[W <: E](next: => Stream[W]): Stream[E] = ChainedStream[E, W](stream, next)

    /**
      * Chains two streams of the same event type where both are finite.
      * The new stream will emit events from the first (finite) stream until it closes and then switch to the second one.
      * If the second stream is created or initialized in the chain, the creation/initialization will be postponed until
      * the first stream closes.
      * Since the second stream is finite as well, it's possible to create longer chains, e.g. `s1 >>> s2 >>> s3 >> s4`.
      *
      * @param next the second of finite streams
      * @return A new chained finite stream
      */
    @targetName("chainf")
    inline def :::[W <: E](next: => FiniteStream[W]): FiniteStream[E] = ChainedFiniteStream[E, W](stream, next)
  }

  extension [V](signal: FiniteSignal[V]) {
    /**
      * Chains two signals where the first one is finite.
      * The new signal will take values from the first (finite) signal until it closes and then switch to the second one.
      * If the second signal is created or initialized in the chain, the creation/initialization will be postponed until
      * the first signal closes.
      * @param next The second signal
      * @tparam W The value type of the second signal - it can be the same as the first signal's value type or its subtype
      * @return A new chained signal
      */
    @targetName("chain")
    inline def ::[W <: V](next: => Signal[W]): Signal[V] = ChainedSignal(signal, next)

    /**
      * Chains two signals where both are finite.
      * The new signal will take values from the first (finite) signal until it closes and then switch to the second one.
      * If the second signal is created or initialized in the chain, the creation/initialization will be postponed until
      * the first signal closes.
      * Since the second stream is finite as well, it's possible to create longer chains, e.g. `s1 >>> s2 >>> s3 >> s4`.
      * @param next The second of finite signals
      * @tparam W The value type of the second signal - it can be the same as the first signal's value type or its subtype
      * @return A new chained finite signal
      */
    @targetName("chainf")
    inline def :::[W <: V](next: => FiniteSignal[W]): FiniteSignal[V] = ChainedFiniteSignal(signal, next)
  }
}
