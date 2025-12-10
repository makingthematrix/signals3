package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Finite.FiniteStream
import io.github.makingthematrix.signals3.ProxyStream.IndexedStream

import scala.concurrent.ExecutionContext

final class TakeStream[E](source: Stream[E], take: Int)
  extends IndexedStream[E](source) with Finite[E]{
  override def isClosed: Boolean = super.isClosed || counter >= take

  override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit = {
    if (!isClosed) {
      inc()
      dispatch(event, sourceContext)
    }
    if (isClosed) lastPromise.foreach {
      case p if !p.isCompleted => p.trySuccess(event)
      case _ =>
    }
  }

  lazy val init: FiniteStream[E] = source.take(take - 1)
}
