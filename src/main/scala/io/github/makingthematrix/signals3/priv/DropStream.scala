package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Stream
import scala.concurrent.ExecutionContext

private[signals3] class DropStream[E](source: Stream[E], drop: Int) extends IndexedStream[E](source) {
  override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
    if (counter < drop) inc() else dispatch(event, sourceContext)
}
