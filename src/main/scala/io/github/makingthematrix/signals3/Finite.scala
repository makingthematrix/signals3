package io.github.makingthematrix.signals3

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
}
