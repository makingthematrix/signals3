package io.github.makingthematrix.signals3

import scala.concurrent.ExecutionContext

/**
 * A source signal holding a boolean value.
 * It provides a few utility methods for setting and clearing the value.
 * Be careful not to mix it up with a regular `Signal[Boolean]` which also has access to utility methods such as `ifTrue`
 * and `ifFalse`, and most often is a result of applying a `map` or `flatMap` transformation to a regular signal.
 * Such a signal cannot be modified by the user but will only react to the changes upstream. This one, on the other hand,
 * is a source signal that has no upstream and relies on the user to set and modify its value.
 * @param initialValue The initial value of the signal.
 */
final class FlagSignal(initialValue: Boolean) extends SourceSignal(Some(initialValue)) {
  inline def state: Boolean = value.contains(true)

  inline def setIf(p: Boolean)(using ec: ExecutionContext = Threading.defaultContext): Unit = if (p) set()
  inline def set()(using ec: ExecutionContext = Threading.defaultContext): Unit = setValue(Some(true), Some(ec))
  inline def onSet(body : => Unit)(using ec: ExecutionContext = Threading.defaultContext): Unit = foreach(_ => if (state) body)

  inline def clearIf(p: Boolean)(using ec: ExecutionContext = Threading.defaultContext): Unit = if (p) clear()
  inline def clear()(using ec: ExecutionContext = Threading.defaultContext): Unit = setValue(Some(false), Some(ec))
  inline def onClear(body : => Unit)(using ec: ExecutionContext = Threading.defaultContext): Unit = foreach(_ => if (!state) body)

  inline def toggleIf(p: Boolean)(using ec: ExecutionContext = Threading.defaultContext): Unit = if (p) toggle()
  inline def toggle()(using ec: ExecutionContext = Threading.defaultContext): Unit = setValue(Some(!state), Some(ec))
  inline def onToggle(body : => Unit)(using ec: ExecutionContext = Threading.defaultContext): Unit = foreach(_ => body)
}

object FlagSignal {
  def apply(): FlagSignal = new FlagSignal(false)
}