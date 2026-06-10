package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Signal.SignalSubscriber
import io.github.makingthematrix.signals3.Stream.EventSubscriber

import scala.annotation.{static, targetName}
import scala.concurrent.{ExecutionContext, Future, Promise}
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
    inline def ::(next: => Stream[E]): Stream[E] = ChainedStream[E](stream, next)

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
    inline def :::(next: => FiniteStream[E]): FiniteStream[E] = ChainedFiniteStream[E](stream, next)
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

  @static final private[signals3] class ChainedSignal[V, W <: V](first: FiniteSignal[V], second: => Signal[W])
    extends Signal[V] with SignalSubscriber {
    override protected[signals3] def onWire(): Unit =
      if (!first.isClosed) {
        first.subscribe(this)
        first.onClose {
          second.subscribe(this)
          first.unsubscribe(this)
        }
      } else {
        second.subscribe(this)
      }

    override protected[signals3] def onUnwire(): Unit = {
      first.unsubscribe(this)
      second.unsubscribe(this)
    }

    private def computeValue(current: Option[V]): Option[V] =
      if (!first.isClosed && current != first.value) first.value
      else if (current != second.value) second.value
      else current

    override protected[signals3] def changed(currentContext: Option[ExecutionContext]): Unit =
      update(computeValue, currentContext)
  }

  @static final private[signals3] class ChainedStream[E](first: FiniteStream[E], second: => Stream[E])
    extends Stream[E] with EventSubscriber[E] {
    override protected[signals3] def onWire(): Unit =
      if (!first.isClosed) {
        first.subscribe(this)
        first.onClose {
          second.subscribe(this)
          first.unsubscribe(this)
        }
      } else {
        second.subscribe(this)
      }

    override protected[signals3] def onUnwire(): Unit = {
      first.unsubscribe(this)
      second.unsubscribe(this)
    }

    override protected[signals3] def onEvent[W <: E](event: W, currentContext: Option[ExecutionContext]): Unit =
      dispatch(event, currentContext)
  }

  @static final private[signals3] class ChainedFiniteSignal[V, W <: V](first: FiniteSignal[V], second: => FiniteSignal[W])
    extends Signal[V] with Finite[V] with SignalSubscriber {

    private inline def switchToSecond(): Unit = {
      second.subscribe(this)
      first.unsubscribe(this)
      lastPromise.foreach(_.completeWith(second.last))
      second.onClose { close() }
    }

    override protected[signals3] def onWire(): Unit =
      if (!first.isClosed) {
        first.subscribe(this)
        first.onClose { switchToSecond() }
      } else if (!second.isClosed) {
        switchToSecond()
      }

    override protected[signals3] def onUnwire(): Unit =
      if (!first.isClosed) first.unsubscribe(this) else if (!second.isClosed) second.unsubscribe(this)

    private def computeValue(current: Option[V]): Option[V] =
      if (!first.isClosed && current != first.value) first.value
      else if (current != second.value) second.value
      else current

    override protected[signals3] def changed(currentContext: Option[ExecutionContext]): Unit =
      if (!isClosed) update(computeValue, currentContext)
  }

  @static final private[signals3] class ChainedFiniteStream[E](first: FiniteStream[E], second: => FiniteStream[E])
    extends Stream[E] with Finite[E] with EventSubscriber[E]{
    private inline def switchToSecond(): Unit = {
      second.subscribe(this)
      first.unsubscribe(this)
      lastPromise.foreach(_.completeWith(second.last))
      second.onClose { close() }
    }

    override protected[signals3] def onWire(): Unit =
      if (!first.isClosed) {
        first.subscribe(this)
        first.onClose { switchToSecond() }
      } else if (!second.isClosed) {
        switchToSecond()
      }

    override protected[signals3] def onUnwire(): Unit = {
      first.unsubscribe(this)
      second.unsubscribe(this)
    }

    override protected[signals3] def onEvent[W <: E](event: W, currentContext: Option[ExecutionContext]): Unit =
      if (!isClosed) dispatch(event, currentContext)
  }
}
