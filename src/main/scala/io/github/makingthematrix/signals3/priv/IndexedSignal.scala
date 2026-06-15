package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.{Indexed, Signal}

private[signals3] class IndexedSignal[V](source: Signal[V]) extends ProxySignal[V](source) with Indexed {
  value = source.value

  override protected def computeValue(current: Option[V]): Option[V] = {
    if (source.value != current) inc()
    source.value
  }
}
