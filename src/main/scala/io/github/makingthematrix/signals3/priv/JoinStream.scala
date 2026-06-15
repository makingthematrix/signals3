package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Stream
import scala.concurrent.ExecutionContext

private[signals3] class JoinStream[E](sources: Stream[E]*) extends ProxyStream[E, E](sources*) {
  override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
    dispatch(event, sourceContext)
}
