package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.{Indexed, Stream}
import scala.concurrent.ExecutionContext

private[signals3] class IndexedStream[E](source: Stream[E]) extends ProxyStream[E, E](source) with Indexed {
  override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit = {
    inc()
    dispatch(event, sourceContext)
  }
}
