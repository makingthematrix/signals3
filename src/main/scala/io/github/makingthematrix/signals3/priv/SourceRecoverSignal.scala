package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.SourceSignal

import scala.concurrent.ExecutionContext

private[signals3] final class SourceRecoverSignal[V](source: SourceSignal[V], recover: Throwable => Option[V])
  extends SourceSignal[V](source.value) with SignalSubscriber{
  override def onWire(): Unit = {
    source.subscribe(this)
    this.value = source.value
  }

  override def onUnwire(): Unit = source.unsubscribe(this)

  override protected[signals3] def changed(ec: Option[ExecutionContext]): Unit = updateWith(source.value, ec)

  private inline def tryMe[T](doIt: => T, rec: Throwable => T): T =
    try doIt catch {
      case t: Throwable => rec(t)
    }

  override protected[signals3] def update(f: Option[V] => Option[V], ec: Option[ExecutionContext]): Boolean =
    tryMe(super.update(f, ec), t => super.updateWith(recover(t), ec))

  override protected[signals3] def updateWith(v: Option[V], ec: Option[ExecutionContext]): Boolean =
    tryMe(super.updateWith(v, ec), t => super.updateWith(recover(t), ec))

  override def publish(value: V, ec: ExecutionContext): Unit =
    tryMe(super.publish(value, ec), t => recover(t).foreach(v => super.publish(v, ec)))

  override def publish(value: V): Unit =
    tryMe(super.publish(value), t => recover(t).foreach(super.publish))
}
