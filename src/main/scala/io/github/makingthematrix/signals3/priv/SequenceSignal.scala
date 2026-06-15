package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Signal

private[signals3]  class SequenceSignal[V](sources: Signal[V]*) extends ProxySignal[Seq[V]](sources *) {
  override protected def computeValue(current: Option[Seq[V]]): Option[Seq[V]] = {
    val res = sources.map(_.value)
    if (res.exists(_.isEmpty)) None
    else Some(res.flatten)
  }
}
