package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Signal
import scala.concurrent.ExecutionContext

private[signals3] class RecoverSignal[V](source: Signal[V], recover: Throwable => Option[V])
  extends ProxySignal[V](source){

  override protected def computeValue(current: Option[V]): Option[V] = source.value

  override def changed(ec: Option[ExecutionContext]): Unit =
    try update(computeValue, ec) catch {
      case t: Throwable => updateWith(recover(t), ec)
    }
}
