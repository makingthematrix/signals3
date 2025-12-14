package io.github.makingthematrix.signals3

import scala.concurrent.{Future, Promise}
import scala.util.chaining.scalaUtilChainingOps

/**
 * A common supertrait for streams and signals that can be closed by internal logic (NOT by the user).
 * A typical case is the `.take` method on a stream - it creates a [[TakeStream]] that closes by itself
 * after a given number of events.
 * @tparam T The type of the stream's event or the signal's value
 */
protected[signals3] trait Finite[T] extends CanBeClosed {
  protected final inline def close(): Unit = closeAndCheck()

  protected var lastPromise: Option[Promise[T]] = None
  final lazy val last: Future[T] = Promise[T]().tap { p => lastPromise = Some(p) }.future
}
  
object Finite {
  type FiniteStream[E] = Stream[E] & Finite[E]
  type FiniteSignal[V] = Signal[V] & Finite[V]
}
