package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Finite.FiniteStream
import io.github.makingthematrix.signals3.Stream

import scala.concurrent.ExecutionContext

final private[signals3] class ChainedStream[E, W <: E](first: FiniteStream[E], second: => Stream[W])
  extends Stream[E] with StreamSubscriber[E] {
  override protected[signals3] def onWire(): Unit =
    if (!first.isClosed) {
      first.subscribe(this)
      first.onClose {
        second.subscribe(this)
        first.unsubscribe(this)
      }
    }
    else {
      second.subscribe(this)
    }

  override protected[signals3] def onUnwire(): Unit = {
    first.unsubscribe(this)
    second.unsubscribe(this)
  }

  override protected[signals3] def onEvent(event: E, currentContext: Option[ExecutionContext]): Unit =
    dispatch(event, currentContext)
}

