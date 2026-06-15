package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.priv.SignalSubscriber
import io.github.makingthematrix.signals3.Signal

import scala.concurrent.ExecutionContext

abstract private[signals3] class ProxySignal[V](sources: Signal[?]*) extends Signal[V] with SignalSubscriber {
  override def onWire(): Unit = {
    sources.foreach(_.subscribe(this))
    value = computeValue(value)
  }

  override def onUnwire(): Unit = sources.foreach(_.unsubscribe(this))

  override def changed(ec: Option[ExecutionContext]): Unit = update(computeValue, ec)

  protected def computeValue(current: Option[V]): Option[V] // this method needs to be overriden in subclasses
}
