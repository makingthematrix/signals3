package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.{Indexed, Signal}

private[signals3] class DropSignal[V](source: Signal[V], drop: Int) extends ProxySignal[V](source) with Indexed {
  require(drop > 0, "drop must be positive")
  value = None

  override protected def computeValue(current: Option[V]): Option[V] = {
    val c = if (source.value != current) incAndGet() else counter
    if (c > drop) source.value else current
  }
}

