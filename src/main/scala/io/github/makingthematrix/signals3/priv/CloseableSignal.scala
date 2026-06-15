package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.{Closeable, Signal}

private[signals3] final class CloseableSignal[V](source: Signal[V]) extends ProxySignal[V](source) with Closeable {
  override protected def computeValue(current: Option[V]): Option[V] = 
    if (!isClosed) source.value else current
}

