package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Signal

private[signals3] final class FoldLeftSignal[V, Z](sources: Signal[V]*)(v: Z)(f: (Z, V) => Z) 
  extends ProxySignal[Z](sources *) {
  override protected def computeValue(current: Option[Z]): Option[Z] =
    sources.foldLeft(Option(v))((mv, signal) => for a <- mv; b <- signal.value yield f(a, b))
}
