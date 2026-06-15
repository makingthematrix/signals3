package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Finite.FiniteStream
import io.github.makingthematrix.signals3.{Finite, Stream}

import scala.concurrent.ExecutionContext

final private[signals3] class ChainedFiniteStream[E, W <: E](first: FiniteStream[E], second: => FiniteStream[W])
  extends Stream[E] with Finite[E] with StreamSubscriber[E] {
  private inline def switchToSecond(): Unit = {
    second.subscribe(this)
    first.unsubscribe(this)
    lastPromise.foreach(_.completeWith(second.last))
    second.onClose {close()}
  }

  override protected[signals3] def onWire(): Unit =
    if (!first.isClosed) {
      first.subscribe(this)
      first.onClose {switchToSecond()}
    }
    else if (!second.isClosed) {
      switchToSecond()
    }

  override protected[signals3] def onUnwire(): Unit = {
    first.unsubscribe(this)
    second.unsubscribe(this)
  }

  override protected[signals3] def onEvent(event: E, currentContext: Option[ExecutionContext]): Unit =
    if (!isClosed) dispatch(event, currentContext)
}
