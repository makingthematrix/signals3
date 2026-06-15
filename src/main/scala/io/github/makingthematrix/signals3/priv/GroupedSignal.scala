package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Signal

private[signals3] class GroupedSignal[V](source: Signal[V], n: Int) extends ProxySignal[Seq[V]](source){
  require(n > 0, "n must be positive")
  private val buffer = scala.collection.mutable.ArrayBuffer.empty[V]

  override protected def computeValue(current: Option[Seq[V]]): Option[Seq[V]] = {
    source.value.foreach(buffer.addOne)
    if (buffer.size == n) {
      val res = buffer.toSeq
      buffer.clear()
      Some(res)
    }
    else current
  }
}