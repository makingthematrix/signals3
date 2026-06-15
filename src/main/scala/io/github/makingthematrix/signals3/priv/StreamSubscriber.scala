package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.priv.EventSource.Subscriber

import scala.concurrent.ExecutionContext

private[signals3] trait StreamSubscriber[E] extends Subscriber {
  // 'currentContext' is the context this method IS run in, NOT the context any subsequent methods SHOULD run in
  protected[signals3] def onEvent(event: E, currentContext: Option[ExecutionContext]): Unit
}

