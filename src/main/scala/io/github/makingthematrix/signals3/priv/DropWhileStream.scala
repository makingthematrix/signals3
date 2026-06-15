package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Stream
import scala.concurrent.ExecutionContext

private[signals3] class DropWhileStream[E](source: Stream[E], p: E => Boolean) extends ProxyStream[E, E](source){
  @volatile private var dropping = true

  override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit = {
    if (dropping) dropping = p(event)
    if (!dropping) dispatch(event, sourceContext)
  }
}

