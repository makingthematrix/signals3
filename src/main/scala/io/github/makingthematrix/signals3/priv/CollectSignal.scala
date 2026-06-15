package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Signal

private[signals3] class CollectSignal[V, Z](source: Signal[V], pf: PartialFunction[V, Z]) extends ProxySignal[Z](source) {
  override protected def computeValue(current: Option[Z]): Option[Z] =
    source.value.flatMap { v =>
      pf.andThen(Option(_)).applyOrElse(v, { (_: V) => Option.empty[Z] })
    }
}

