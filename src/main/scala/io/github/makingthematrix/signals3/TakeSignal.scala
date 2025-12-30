package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.ProxySignal.IndexedSignal

/**
 * A signal which closes after a given number of value changes.
 *
 * `TakeSignal` is a "proxy signal" - a special subclass of [[Signal]] with additional logic, usually hidden from the user.
 * * It is created by the `take` method on another signal, similar to how other proxy signals are created through `map`, `filter`, etc.
 * * But, contrary to other proxy signals, `TakeSignal` might be useful on its own, so I decided not to hide it.
 *
 * @param source The signal from which values are taken.
 * @param take The number of values to take.
 * @tparam V The type of the value held by the signal.
 */
final class TakeSignal[V](source: Signal[V], take: Int)
  extends IndexedSignal[V](source) with Finite[V] {
  override def isClosed: Boolean = super.isClosed || counter >= take

  source.value.foreach(takeOne)

  override protected def computeValue(current: Option[V]): Option[V] =
    if (!isClosed && source.value != current) {
      source.value.foreach(takeOne)
      source.value
    }
    else current

  private def takeOne(value: V): Unit =
    if (incAndGet() < take) publish(value)
    else {
      close()
      lastPromise match {
        case Some(promise) if !promise.isCompleted => promise.trySuccess(value)
        case _ =>
      }
    }

  /**
   * A signal of all value changes of this signal except the last one. See [[Finite.last]].
   */
  lazy val init: TakeSignal[V] = source.take(take - 1)
}
