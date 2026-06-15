package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Stream
import scala.concurrent.ExecutionContext

private[signals3] class RecoverWithStream[E](source: Stream[E], recoverWith: PartialFunction[Throwable, Option[E]])
  extends ProxyStream[E, E](source) {
  override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
    try dispatch(event, sourceContext) catch {
      case t: Throwable if recoverWith.isDefinedAt(t) =>
        recoverWith(t).foreach(dispatch(_, sourceContext))
    }
}
