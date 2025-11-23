package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Finite.Source

import scala.concurrent.{Future, Promise}
import scala.util.chaining.scalaUtilChainingOps

/**
 * A common supertrait for streams and signals that can be closed by internal logic (NOT by the user).
 * A typical case is the `.take` method on a stream - it creates a [[TakeStream]] that closes by itself
 * after a given number of events.
 * @tparam T The type of the stream's event or the signal's value
 * @tparam M The type of the source that will be closed eventually
 */
protected[signals3] trait Finite[T, M <: Source[T]](constructor: () => M) extends CanBeClosed {
  protected final inline def close(): Unit = closeAndCheck()

  protected var lastPromise: Option[Promise[T]] = None
  lazy val last: Future[T] = Promise[T]().tap { p => lastPromise = Some(p) }.future

  protected var initSource: Option[M] = None
  lazy val init: M = constructor().tap { s => initSource = Some(s) }
}
  
object Finite {
  type Source[T] = SourceStream[T] | SourceSignal[T]
  type FiniteStream[E] = Stream[E] & Finite[E, SourceStream[E]]
  type FiniteSignal[V] = Signal[V] & Finite[V, SourceSignal[V]]
}
