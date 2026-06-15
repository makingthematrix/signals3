package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Stream
import scala.concurrent.ExecutionContext

private[signals3] class CollectStream[E, V](source: Stream[E], pf: PartialFunction[E, V]) extends ProxyStream[E, V](source) {
  override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
    if (pf.isDefinedAt(event)) dispatch(pf(event), sourceContext)
}

