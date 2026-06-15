package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Stream

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext

private[signals3] class GroupByStream[E](source: Stream[E], groupBy: E => Boolean) extends ProxyStream[E, Seq[E]](source){
  private val buffer = ArrayBuffer.empty[E]

  override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit = {
    if (groupBy(event)) {
      val res = buffer.toSeq
      buffer.clear()
      if (res.nonEmpty) dispatch(res, sourceContext)
    }
    buffer.addOne(event)
  }
}
