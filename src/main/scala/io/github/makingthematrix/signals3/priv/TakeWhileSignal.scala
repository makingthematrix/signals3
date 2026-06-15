package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.{Finite, Signal}

private[signals3] final class TakeWhileSignal[V](source: Signal[V], p: V => Boolean) extends ProxySignal[V](source) with Finite[V] {
  computeValue(source.value)

  override protected def computeValue(current: Option[V]): Option[V] =
    if (isClosed || source.value == current) current
    else if (!source.value.exists(p)) {
      (lastPromise, current) match {
        case (Some(promise), Some(v)) if !promise.isCompleted => promise.trySuccess(v)
        case _ =>
      }
      close()
      current
    }
    else source.value
}
