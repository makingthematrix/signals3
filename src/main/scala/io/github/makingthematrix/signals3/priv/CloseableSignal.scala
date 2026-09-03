package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.{Closeable, Signal}

import scala.concurrent.ExecutionContext

private[signals3] final class CloseableSignal[V](source: Signal[V]) extends ProxySignal[V](source) with Closeable {
  override protected def computeValue(current: Option[V]): Option[V] = 
    if (!isClosed) source.value else current

  override def publish(value: V, ec: ExecutionContext): Unit =
    if (!isClosed) super.publish(value, ec)

  override def publish(value: V): Unit =
    if (!isClosed) super.publish(value)

  override protected[signals3] def update(f: Option[V] => Option[V], currentContext: Option[ExecutionContext]): Boolean =
    if (!isClosed) super.update(f, currentContext) else false

  override protected[signals3] def updateWith(v: Option[V], currentContext: Option[ExecutionContext]): Boolean =
    if (!isClosed) super.updateWith(v, currentContext) else false
}

