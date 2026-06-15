package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Signal

private[signals3] class MapSignal[V, Z](source: Signal[V], f: V => Z) extends ProxySignal[Z](source) {
  override protected def computeValue(current: Option[Z]): Option[Z] = source.value.map(f)
}
