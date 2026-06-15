package io.github.makingthematrix.signals3.priv

import scala.concurrent.ExecutionContext

private[signals3] final class SourceRecoverStream[E](source: SourceStream[E], recover: Throwable => Option[E])
  extends SourceStream[E] with StreamSubscriber[E]{
  override protected[signals3] def onWire(): Unit = source.subscribe(this)

  override protected[signals3] def onUnwire(): Unit = source.unsubscribe(this)

  override def dispatch(event: E, sourceContext: Option[ExecutionContext]): Unit =
    tryDispatch(event, sourceContext, recover)

  override def publish(event: E): Unit =
    tryDispatch(event, None, recover)

  override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
    tryDispatch(event, sourceContext, recover)

  private def tryDispatch(event: E, sourceContext: Option[ExecutionContext], recover: Throwable => Option[E]): Unit =
    try super.dispatch(event, sourceContext) catch {
      case t: Throwable => recover(t).foreach(super.dispatch(_, sourceContext))
    }
}

