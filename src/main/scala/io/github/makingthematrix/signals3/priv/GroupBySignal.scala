package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Signal

private[signals3] class GroupBySignal[V](source: Signal[V], groupBy: V => Boolean) extends ProxySignal[Seq[V]](source){
  private val buffer = scala.collection.mutable.ArrayBuffer.empty[V]

  override protected def computeValue(current: Option[Seq[V]]): Option[Seq[V]] = {
    val res =
      if (buffer.nonEmpty && source.value.exists(groupBy)) {
        val seq = buffer.toSeq
        buffer.clear()
        Some(seq)
      } else {
        current
      }
    source.value.foreach(buffer.addOne)
    res
  }
}