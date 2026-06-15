package io.github.makingthematrix.signals3.priv

import scala.concurrent.ExecutionContext

private[signals3] final class SourceRecoverWithStream[E](source: SourceStream[E], recoverWith: PartialFunction[Throwable, Option[E]])
  extends SourceStream[E] with StreamSubscriber[E]{
  override protected[signals3] def onWire(): Unit = source.subscribe(this)

  override protected[signals3] def onUnwire(): Unit = source.unsubscribe(this)

  override def dispatch(event: E, sourceContext: Option[ExecutionContext]): Unit =
    tryDispatchWith(event, sourceContext, recoverWith)

  override def publish(event: E): Unit = tryDispatchWith(event, None, recoverWith)

  override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
    tryDispatchWith(event, sourceContext, recoverWith)

  private def tryDispatchWith(event: E, sourceContext: Option[ExecutionContext], recoverWith: PartialFunction[Throwable, Option[E]]): Unit =
    try super.dispatch(event, sourceContext) catch {
      case t: Throwable if recoverWith.isDefinedAt(t) =>
        recoverWith(t).foreach(super.dispatch(_, sourceContext))
    }
}
