package io.github.makingthematrix.signals3.priv

import scala.concurrent.ExecutionContext

private[signals3] trait SignalSubscriber {
  // 'currentContext' is the context this method IS run in, NOT the context any subsequent methods SHOULD run in
  protected[signals3] def changed(currentContext: Option[ExecutionContext]): Unit
}
