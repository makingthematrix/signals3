package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Stream
import scala.concurrent.ExecutionContext

private[signals3] class FilterStream[E](source: Stream[E], predicate: E => Boolean) extends ProxyStream[E, E](source) {
  override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit = {
    if (predicate(event)) dispatch(event, sourceContext)
  }
}