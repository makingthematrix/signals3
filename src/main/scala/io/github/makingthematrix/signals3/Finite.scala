package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Signal.SignalSubscriber
import io.github.makingthematrix.signals3.Stream.EventSubscriber

import scala.annotation.static
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
    def >>(next: => Stream[E]): Stream[E] = ChainedStream[E](stream, next)
    def >>>(next: => FiniteStream[E]): FiniteStream[E] = ChainedFiniteStream[E](stream, next)
  }

  extension [V](signal: FiniteSignal[V]) {
    def >>[W <: V](next: => Signal[W]): Signal[V] = ChainedSignal(signal, next)
    def >>>[W <: V](next: => FiniteSignal[W]): FiniteSignal[V] = ChainedFiniteSignal(signal, next)
  }

  @static final private[signals3] class ChainedSignal[V, W <: V](first: FiniteSignal[V], second: => Signal[W])
    extends Signal[V] with SignalSubscriber {
    override protected[signals3] def onWire(): Unit =
      if (!first.isClosed) {
        first.subscribe(this)
        first.onClose {
          second.subscribe(this)
        }
      } else {
        second.subscribe(this)
      }

    override protected[signals3] def onUnwire(): Unit =
      if (!first.isClosed) first.unsubscribe(this)
      else second.unsubscribe(this)

    private def computeValue(current: Option[V]): Option[V] = {
      if (!first.isClosed && current != first.value) first.value
      else if (current != second.value) second.value
      else current
    }

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
        }
      }
      else {
        second.subscribe(this)
      }

    override protected[signals3] def onUnwire(): Unit =
      if (!first.isClosed) first.unsubscribe(this)
      else second.unsubscribe(this)

    override protected[signals3] def onEvent(event: E, currentContext: Option[ExecutionContext]): Unit =
      dispatch(event, currentContext)
  }

  @static final private[signals3] class ChainedFiniteSignal[V, W <: V](first: FiniteSignal[V], second: => FiniteSignal[W])
    extends Signal[V] with Finite[V] with SignalSubscriber {

    override protected[signals3] def onWire(): Unit =
      if (!first.isClosed) {
        first.subscribe(this)
        first.onClose {
          second.subscribe(this)
          lastPromise.foreach(_.completeWith(second.last))
          second.onClose { close() }
        }
      } else if (!second.isClosed) {
        second.subscribe(this)
        lastPromise.foreach(_.completeWith(second.last))
        second.onClose {close()}
      }

    override protected[signals3] def onUnwire(): Unit =
      if (!first.isClosed) first.unsubscribe(this)
      else if (!second.isClosed) second.unsubscribe(this)

    private def computeValue(current: Option[V]): Option[V] = {
      if (!first.isClosed && current != first.value) first.value
      else if (current != second.value) second.value
      else current
    }

    override protected[signals3] def changed(currentContext: Option[ExecutionContext]): Unit =
      if (!isClosed) update(computeValue, currentContext)
  }

  @static final private[signals3] class ChainedFiniteStream[E](first: FiniteStream[E], second: => FiniteStream[E])
    extends Stream[E] with Finite[E] with EventSubscriber[E]{
    override protected[signals3] def onWire(): Unit =
      if (!first.isClosed) {
        first.subscribe(this)
        first.onClose {
          second.subscribe(this)
          lastPromise.foreach(_.completeWith(second.last))
          second.onClose {close()}
        }
      } else if (!second.isClosed) {
        second.subscribe(this)
        lastPromise.foreach(_.completeWith(second.last))
        second.onClose {close()}
      }

    override protected[signals3] def onUnwire(): Unit =
      if (!first.isClosed) first.unsubscribe(this)
      else if (!second.isClosed) second.unsubscribe(this)

    override protected[signals3] def onEvent(event: E, currentContext: Option[ExecutionContext]): Unit =
      if (!isClosed) dispatch(event, currentContext)
  }
}
