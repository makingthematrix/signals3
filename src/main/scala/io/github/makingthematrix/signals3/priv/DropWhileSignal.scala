package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Signal

private[signals3] class DropWhileSignal[V](source: Signal[V], p: V => Boolean) extends ProxySignal[V](source) {
  @volatile private var dropping = true

  override protected def computeValue(current: Option[V]): Option[V] = {
    if (dropping && source.value != current) dropping = source.value.exists(p)
    if (!dropping) source.value
    else current
  }
}
