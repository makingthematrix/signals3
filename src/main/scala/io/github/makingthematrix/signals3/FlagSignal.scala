package io.github.makingthematrix.signals3

import scala.concurrent.ExecutionContext

/**
 * A source signal holding a boolean value.
 * It provides a few utility methods for setting and clearing the value.
 * Be careful not to confuse it with a regular `Signal[Boolean]` which also has access to utility methods such as `ifTrue`
 * and `ifFalse`, and most often is a result of applying a `map` or `flatMap` transformation to a regular signal.
 * Such a signal cannot be modified by the user but will only react to the changes upstream. This one, on the other hand,
 * is a source signal that has no upstream and relies on the user to set and modify its value.
 * @param initialValue The initial value of the signal.
 */
final class FlagSignal(initialValue: Boolean) extends SourceSignal(Some(initialValue)) {
  /** Retrieves the current value of the signal */
  inline def state: Boolean = value.contains(true)

  /**  Sets the value of the signal to `true` */
  inline def set()(using ec: ExecutionContext): Unit = updateWith(Some(true), Some(ec))
  /** A utility method for setting the value of the signal to `true` only if the given predicate evaluates to `true` */
  inline def setIf(p: Boolean)(using ec: ExecutionContext): Unit = if (p) set()
  /** Registers a block of code that will be called when the signal's value changes to `true` */
  inline def onSet(body : => Unit)(using ec: ExecutionContext): Unit = foreach(_ => if (state) body)

  /** Sets the value of the signal to `false` */
  inline def clear()(using ec: ExecutionContext): Unit = updateWith(Some(false), Some(ec))
  /** A utility method for setting the value of the signal to `false` only if the given predicate evaluates to `true` */
  inline def clearIf(p: Boolean)(using ec: ExecutionContext): Unit = if (p) clear()
  /** Registers a block of code that will be called when the signal's value changes to `false` */
  inline def onClear(body : => Unit)(using ec: ExecutionContext): Unit = foreach(_ => if (!state) body)

  /** Toggles the value of the signal between `true` and `false` */
  inline def toggle()(using ec: ExecutionContext): Unit = updateWith(Some(!state), Some(ec))
  /** A utility method for toggling the value of the signal only if the given predicate evaluates to `true` */
  inline def toggleIf(p: Boolean)(using ec: ExecutionContext): Unit = if (p) toggle()
  /** Registers a block of code that will be called when the signal's value changes to the opposite of its current value */
  inline def onToggle(body : => Unit)(using ec: ExecutionContext): Unit = foreach(_ => body)
}

object FlagSignal {
  /** Creates a new flag signal with the initial value set to `false` */
  def apply(): FlagSignal = new FlagSignal(false)
}