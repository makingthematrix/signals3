package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Finite.FiniteSignal
import io.github.makingthematrix.signals3.ProxySignal.IndexedSignal

final class TakeSignal[V](source: Signal[V], take: Int)
  extends IndexedSignal[V](source) with Finite[V]{
  override def isClosed: Boolean = super.isClosed || counter >= take

  source.value.foreach(takeOne)

  override protected def computeValue(current: Option[V]): Option[V] =
    if (!isClosed && source.value != current) {
      source.value.foreach(takeOne)
      source.value
    }
    else current

  private def takeOne(value: V): Unit = {
    inc()
    if (counter < take) publish(value)
    else {
      close()
      lastPromise match {
        case Some(promise) if !promise.isCompleted => promise.trySuccess(value)
        case _ =>
      }
    }
  }

  lazy val init: FiniteSignal[V] = source.take(take - 1)
}
