package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Signal
import scala.concurrent.ExecutionContext

private[signals3] final class PartialUpdateSignal[V, Z](source: Signal[V])(select: V => Z) extends ProxySignal[V](source) {
  private object updateMonitor

  override protected[signals3] def update(f: Option[V] => Option[V], currentContext: Option[ExecutionContext]): Boolean = {
    val changed = updateMonitor.synchronized {
      val next = f(value)
      if (value.map(select) != next.map(select)) {
        value = next
        true
      }
      else false
    }
    if (changed) notifySubscribers(currentContext)
    changed
  }

  override protected def computeValue(current: Option[V]): Option[V] = source.value
}
