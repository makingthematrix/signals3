package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.{EventContext, Signal, Stream}

private[signals3] class StreamSignal[V](source: Stream[V], v: Option[V] = None) extends Signal[V](v){
  private lazy val subscription = source.onCurrent(publish)(using EventContext.Global)

  override protected def onWire(): Unit = subscription.enable()

  override protected def onUnwire(): Unit = subscription.disable()
}
