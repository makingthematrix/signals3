package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Stream

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext

private[signals3] class GroupedStream[E](source: Stream[E], n: Int) extends ProxyStream[E, Seq[E]](source){
  require(n > 0, "n must be positive")
  private val buffer = ArrayBuffer.empty[E]

  override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit = {
    buffer.addOne(event)
    if (buffer.size == n) {
      val res = buffer.toSeq
      buffer.clear()
      dispatch(res, sourceContext)
    }
  }
}
