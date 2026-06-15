package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Finite.FiniteSignal
import io.github.makingthematrix.signals3.Signal

import scala.concurrent.ExecutionContext

final private[signals3] class ChainedSignal[V, W <: V](first: FiniteSignal[V], second: => Signal[W])
  extends Signal[V] with SignalSubscriber{
  override protected[signals3] def onWire(): Unit =
    if (!first.isClosed) {
      first.subscribe(this)
      first.onClose {
        second.subscribe(this)
        first.unsubscribe(this)
      }
    }
    else {
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
