package io.github.makingthematrix.signals3.priv

import scala.concurrent.ExecutionContext
import io.github.makingthematrix.signals3.Stream

private[signals3] class MapStream[E, V](source: Stream[E], f: E => V) extends ProxyStream[E, V](source) {
  override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
    dispatch(f(event), sourceContext)
}

