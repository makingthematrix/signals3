package io.github.makingthematrix.signals3.priv

import scala.concurrent.{ExecutionContext, Future}
import io.github.makingthematrix.signals3.{EventContext, Stream}

import scala.ref.WeakReference
import scala.util.Try

final private[signals3] class StreamSubscription[E](source: Stream[E],
                                                  f: E => Unit,
                                                  executionContext: Option[ExecutionContext] = None
                                                 )(using context: WeakReference[EventContext])
  extends BaseSubscription(context) with StreamSubscriber[E]{

  override def onEvent(event: E, currentContext: Option[ExecutionContext]): Unit =
    if (subscribed)
      executionContext match {
        case Some(ec) if !currentContext.contains(ec) => Future(if (subscribed) Try(f(event)))(using ec)
        case _ => f(event)
      }

  override protected[signals3] def onSubscribe(): Unit = monitor.synchronized {
    source.subscribe(this)
  }

  override protected[signals3] def onUnsubscribe(): Unit = monitor.synchronized {
    source.unsubscribe(this)
  }
}
