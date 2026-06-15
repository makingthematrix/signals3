package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.{Finite, Stream}

import scala.concurrent.ExecutionContext

final private[signals3] class TakeWhileStream[E](source: Stream[E], p: E => Boolean)
  extends ProxyStream[E, E](source) with Finite[E] {

  private var previousEvent: Option[E] = None

  override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit = if (!isClosed) {
    if (p(event)) {
      dispatch(event, sourceContext)
      previousEvent = Some(event)
    }
    else {
      close()
      (lastPromise, previousEvent) match {
        case (Some(p), Some(pe)) if !p.isCompleted => p.trySuccess(pe)
        case _ =>
      }
    }
  }
}
