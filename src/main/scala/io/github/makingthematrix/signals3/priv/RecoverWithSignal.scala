package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Signal
import scala.concurrent.ExecutionContext

private[signals3] class RecoverWithSignal[V](source: Signal[V], recoverWith: PartialFunction[Throwable, Option[V]])
  extends ProxySignal[V](source){

  override protected def computeValue(current: Option[V]): Option[V] = source.value

  override def changed(ec: Option[ExecutionContext]): Unit =
    try update(computeValue, ec) catch {
      case t: Throwable if recoverWith.isDefinedAt(t) => updateWith(recoverWith(t), ec)
    }
}
