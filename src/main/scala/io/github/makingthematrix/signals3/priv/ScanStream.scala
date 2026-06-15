package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Stream
import scala.concurrent.ExecutionContext

private[signals3] class ScanStream[E, V](source: Stream[E], zero: V, f: (V, E) => V) extends ProxyStream[E, V](source){
  @volatile private var value = zero

  override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit = {
    value = f(value, event)
    dispatch(value, sourceContext)
  }
}
