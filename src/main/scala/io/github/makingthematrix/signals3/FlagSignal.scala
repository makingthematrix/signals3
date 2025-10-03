package io.github.makingthematrix.signals3

import scala.concurrent.ExecutionContext

final class FlagSignal extends Signal(Some(false)) {
  inline def doneIf(p: Boolean)(using ec: ExecutionContext = Threading.defaultContext): Unit = if (p) done()
  inline def done()(using ec: ExecutionContext = Threading.defaultContext): Unit = set(Some(true), Some(ec))
  inline def state: Boolean = value.contains(true)
  inline def onDone(body : => Unit)(using ec: ExecutionContext = Threading.defaultContext): Unit = foreach(_ => body)
}
