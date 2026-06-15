package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.{EventContext, Signal}

import scala.concurrent.{ExecutionContext, Future}
import scala.ref.WeakReference
import scala.util.Try

final private[signals3] class SignalSubscription[V](source: Signal[V],
                                                    f: V => Unit,
                                                    executionContext: Option[ExecutionContext] = None
                                                 )(using context: WeakReference[EventContext])
  extends BaseSubscription(context) with SignalSubscriber{

  override def changed(currentContext: Option[ExecutionContext]): Unit = synchronized {
    source.value.foreach { event =>
      if (subscribed) executionContext match {
        case Some(ec) if !currentContext.contains(ec) => Future(if (subscribed) Try(f(event)))(using ec)
        case _ => f(event)
      }
    }
  }

  override protected[signals3] def onSubscribe(): Unit = monitor.synchronized {
    source.subscribe(this)
    changed(None) // refresh the subscriber with current value
  }

  override protected[signals3] def onUnsubscribe(): Unit = monitor.synchronized {
    source.unsubscribe(this)
  }
}
