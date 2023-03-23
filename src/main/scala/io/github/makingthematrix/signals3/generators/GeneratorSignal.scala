package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.*

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
  * A signal capable of generating new values in the given intervals of time, by repeatedly calling a function
  * that takes the current value and returns a new one. The interval can be given either as `FiniteDuration` or as
  * a function that will return the number of milliseconds in `Long` every time it's called.
  *
  * @see [[GeneratorStream]] for details.
  * .
  * @param init     The initial value of the generator signal.
  * @param update   A function that takes the current value of the signal and creates a new value every time it's called.
  *                 If the new value is different from the old one, it will be published in the signal. If the function
  *                 throws an exception, the value won't change, but the generator will call the `update` function
  *                 again, after `interval`. The exception will be ignored.
  * @param interval Time to the next `update` call. Might be either a `FiniteDuration` or a function that returns
  *                 the number of milliseconds. In the second case, the function will be called on initialization, and
  *                 then after each `update` call.
  * @param paused   A function called before each `update` to check if the generator is paused. If it returns `true`,
  *                 the `update` function will not be called.
  * @param ec       The execution context in which the generator works.
  * @tparam V       The type of the signal's value.
  */

final class GeneratorSignal[V](init    : V,
                               update  : V => V,
                               interval: Either[FiniteDuration, V => Long],
                               paused  : () => Boolean)
                              (using ec: ExecutionContext)
  extends Signal[V](Some(init)) with NoAutowiring:

  private var closed = false
  private val beat =
    (interval match
       case Left(intv)          => CancellableFuture.repeat(intv)
       case Right(calcInterval) => CancellableFuture.repeatWithMod(() => calcInterval(currentValue.getOrElse(init)))
    ) {
      if !paused() then currentValue.foreach(v => publish(update(v), ec))
    }.onCancel {
      closed = true
    }

  /**
    * Closes the generator permanently. There will be no further calls to `update`, `interval`, and `paused`.
    */
  inline def close(): Unit = beat.cancel()

  /**
    * Checks if the generator is closed.
    *
    * @return `true` if the generator was closed
    */
  inline def isClosed: Boolean = closed

object GeneratorSignal:
  /**
    * Creates a signal which updates its value every `interval` by calling the `update` function which takes the current
    * and returns a new one.
    * .
    * @param init     The initial value of the generator signal.
    * @param update   A function that takes the current value of the signal and creates a new value every time
    *                 it's called. If the new value is different from the old one, it will be published in the signal.
    *                 If the function throws an exception, the value won't change, but the generator will call
    *                 the `update` function again, after `interval`. The exception will be ignored.
    * @param interval Time to the next update.
    * @param paused   A function called on each event to check if the generator is paused. If it returns `true`,
    *                 the `generate` function will not be called. Optional. By default the generator is never paused.
    * @param ec       The execution context in which the generator works. Optional.
    *                 By default it's `Threading.defaultContext`.
    * @tparam V       The type of the signal's value.
    * @return         A new generator signal.
    */
  def apply[V](init    : V,
               update  : V => V,
               interval: FiniteDuration,
               paused  : () => Boolean = () => false)
              (using ec: ExecutionContext = Threading.defaultContext): GeneratorSignal[V] =
    new GeneratorSignal[V](init, update, Left(interval), paused)

  /**
    * A utility method for easier creation of a generator sginal of "unfolding" values. The user provides the initial
    * value of the signal and a block of code which transforms the current value into a new one. The signal will use
    * that code to "unfold" from the initial value to the new ones with each call made every `interval`.
    * The name is supposed to hint at the `.fold` method from Scala collections, which takes a sequence and folds it
    * into one value.
    *
    * @param init     The initial value of the generator signal.
    * @param interval Time to the next update.
    * @param body     A block of code that, every time it's called, takes the current value of the signal and returns
    *                 a new value. If the new value is different from the old one, it will be published in the stream.
    *                 If the code throws an exception, no event will be generated, but the generator will call it again,
    *                 with the same current value, after `interval`. The exception will be ignored.
    * @param ec       The execution context in which the generator works. Optional.
    *                 By default it's `Threading.defaultContext`.
    * @tparam V       The type of the signal's value.
    * @return         A new generator signal.
    */
  inline def unfold[V](init: V, interval: FiniteDuration)(body: V => V)
                      (using ec: ExecutionContext = Threading.defaultContext): GeneratorSignal[V] =
    new GeneratorSignal[V](init, body, Left(interval), () => false)

  /**
    * A utility method for easier creation of a generator sginal of "unfolding" values. The user provides the initial
    * value of the signal and a block of code which transforms the current value into a new one. The signal will use
    * that code to "unfold" from the initial value to the new ones with each call made every `interval`.
    * In contrast to the simpler `unfold` method, `unfoldWithMod` allows t provide a function which will determine
    * the interval.
    *
    * @param init     The initial value of the generator signal.
    * @param interval A function that returns the number of milliseconds to the next event generation (and to the first
    *                 event as well).
    * @param body     A block of code that, every time it's called, takes the current value of the signal and returns
    *                 a new value. If the new value is different from the old one, it will be published in the stream.
    *                 If the code throws an exception, no event will be generated, but the generator will call it again,
    *                 with the same current value, after `interval`. The exception will be ignored.
    * @param ec       The execution context in which the generator works. Optional.
    *                 By default it's `Threading.defaultContext`.
    * @tparam V       The type of the signal's value.
    * @return         A new generator signal.
    */
  inline def unfoldWithMod[V](init: V, interval: V => Long)(body: V => V)
                             (using ec: ExecutionContext = Threading.defaultContext): GeneratorSignal[V] =
    new GeneratorSignal[V](init, body, Right(interval), () => false)
