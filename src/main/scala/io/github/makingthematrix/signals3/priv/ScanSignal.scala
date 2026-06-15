package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Signal

private[signals3] class ScanSignal[V, Z](source: Signal[V], zero: Z, f: (Z, V) => Z) extends ProxySignal[Z](source){
  value = Some(zero)

  override protected def computeValue(current: Option[Z]): Option[Z] =
    source.value.map { v => f(current.getOrElse(zero), v) }.orElse(current)
}

