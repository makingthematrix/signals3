package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Finite.FiniteSignal
import io.github.makingthematrix.signals3.{Finite, Signal}

import scala.concurrent.ExecutionContext

final private[signals3] class ChainedFiniteSignal[V, W <: V](first: FiniteSignal[V], second: => FiniteSignal[W])
  extends Signal[V] with Finite[V] with SignalSubscriber {

  private inline def switchToSecond(): Unit = {
    second.subscribe(this)
    first.unsubscribe(this)
    lastPromise.foreach(_.completeWith(second.last))
    second.onClose {close()}
  }

  override protected[signals3] def onWire(): Unit =
    if (!first.isClosed) {
      first.subscribe(this)
      first.onClose {switchToSecond()}
    }
    else if (!second.isClosed) {
      switchToSecond()
    }

  override protected[signals3] def onUnwire(): Unit =
    if (!first.isClosed) first.unsubscribe(this)
    else if (!second.isClosed) second.unsubscribe(this)

  private def computeValue(current: Option[V]): Option[V] =
    if (!first.isClosed && current != first.value) first.value
    else if (current != second.value) second.value
    else current

  override protected[signals3] def changed(currentContext: Option[ExecutionContext]): Unit =
    if (!isClosed) update(computeValue, currentContext)
}
