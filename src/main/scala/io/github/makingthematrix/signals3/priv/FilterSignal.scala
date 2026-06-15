package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Signal

private[signals3] class FilterSignal[V](source: Signal[V], predicate: V => Boolean) extends ProxySignal[V](source) {
  override protected def computeValue(current: Option[V]): Option[V] = source.value.filter(predicate)
}