package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Signal

private[signals3] class CombineSignal[V, Z, Y](vSignal: Signal[V], zSignal: Signal[Z], f: (V, Z) => Y)
  extends ProxySignal[Y](vSignal, zSignal){
  override protected def computeValue(current: Option[Y]): Option[Y] =
    for {
      v <- vSignal.value
      z <- zSignal.value
    }
    yield f(v, z)
}