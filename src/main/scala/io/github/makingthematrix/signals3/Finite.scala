package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Finite.Source
import scala.concurrent.Future

/**
 * A common supertrait for streams and signals that can be closed by internal logic (NOT by the user).
 * A typical case is the `.take` method on a stream - it creates a [[TakeStream]] that closes by itself
 * after a given number of events.
 * @tparam T The type of the stream's event or the signal's value
 * @tparam M The type of the source that will be closed eventually
 */
trait Finite[T, M <: Source[T]] extends Closeable {
  def last: Future[T]
  def init: M
}
  
object Finite {
  type Source[T] = Stream[T] | Signal[T]
  type FiniteStream[E] = Stream[E] & Finite[E, Stream[E]]
  type FiniteSignal[V] = Signal[V] & Finite[V, Signal[V]]
}

