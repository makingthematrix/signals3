package io.github.makingthematrix.signals3

import scala.concurrent.ExecutionContext

/** A sort-of-but-not-quite source signal holding a boolean value, that starts with `false` and can be then changed to
 * `true` exaclt once. Only for internal use, e.g. as a `isClosedSignal` in [[CanBeClosed]]. For this reason it can't
 * implement [[CanBeClosed]] itself as that would lead to circular compilation.
 */
private[signals3] final class DoneSignal extends Signal(Some(false)) {
  inline def done()(using ec: ExecutionContext): Unit = updateWith(Some(true), Some(ec))
  inline def doneIf(p: Boolean)(using ExecutionContext): Unit = if (p) done()
  inline def state: Boolean = value.contains(true)
  inline def onDone(body : => Unit)(using ExecutionContext): Unit = foreach(_ => body)
}
