package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.{Closeable, Stream}
import scala.concurrent.ExecutionContext

final private[signals3] class CloseableStream[E](source: Stream[E])
  extends ProxyStream[E, E](source) with Closeable {
  override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
    if (!isClosed) dispatch(event, sourceContext)
}

