package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.{Closeable, CloseableFuture, Signal, Threading}
import io.github.makingthematrix.signals3.Closeable.CloseableSignal
import io.github.makingthematrix.signals3.EventSource.NoAutowiring

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
                               interval: FiniteDuration | (V => Long),
                               paused  : V => Boolean)
                              (using ec: ExecutionContext)
  extends Signal[V](Some(init)) with Closeable with NoAutowiring:

  private val beat =
    (interval match
       case intv: FiniteDuration => CloseableFuture.repeat(intv)
       case intv: (V => Long)    => CloseableFuture.repeatVariant(() => intv(currentValue.getOrElse(init)))
    ) {
      if !currentValue.exists(paused) then currentValue.foreach(v => publish(update(v), ec))
    }

  /**
    * Closes the generator permanently. There will be no further calls to `update`, `interval`, and `paused`.
    */
  override inline def closeAndCheck(): Boolean = beat.closeAndCheck()

  /**
    * Checks if the generator is closed.
    *
    * @return `true` if the generator was closed
    */
  override inline def isClosed: Boolean = beat.isClosed

  override inline def onClose(body: => Unit): Unit = beat.onClose(body)

object GeneratorSignal:
  /**
    * Creates a signal which updates its value every `interval` by calling the `update` function which takes the current
    * and returns a new one.
    * .
    * @param init     The initial value of the generator signal.
    * @param update   A function that takes the current value of the signal and creates a new (or the same) value every
    *                 time it's called. If the new value is different from the old one, it will be published in
    *                 the signal. If the function throws an exception, the value won't change, but the generator will
    *                 call the `update` function again, after `interval`. The exception will be ignored.
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
               paused  : V => Boolean = (_: V) => false)
              (using ec: ExecutionContext = Threading.defaultContext): GeneratorSignal[V] =
    new GeneratorSignal[V](init, update, interval, paused)

  /**
    * A utility method for easier creation of a generator signal. The user provides the initial value of the signal,
    * and the interval between updates - and then the update method in a separate argument list for better readability.
    *
    * @param init     The initial value of the generator signal.
    * @param interval Time to the next update.
    * @param update   A function that, every time it's called, takes the current value of the signal and returns
    *                 a new (or the same) value.
    *                 If the code throws an exception, no event will be generated, but the generator will call it again,
    *                 with the same current value, after `interval`. The exception will be ignored.
    * @param ec       The execution context in which the generator works. Optional.
    *                 By default it's `Threading.defaultContext`.
    * @tparam V       The type of the signal's value.
    * @return         A new generator signal.
    */
  inline def generate[V](init: V, interval: FiniteDuration)(update: V => V)
                        (using ec: ExecutionContext = Threading.defaultContext): GeneratorSignal[V] =
    new GeneratorSignal[V](init, update, interval, (_: V) => false)

  /**
    * A utility method for easier creation of a generator signal. The user provides the initial
    * value of the signal, and a function that will return the interval between updates - and then the update method
    * in a separate argument list for better readability.
    *
    * @param init     The initial value of the generator signal.
    * @param interval A function that returns the number of milliseconds to the next update.
    * @param update   A function that, every time it's called, takes the current value of the signal and returns a new
    *                 (or the same) value. If the new value is different from the old one, it will be published in
    *                 the signal. If the code throws an exception, the value will not be updated, but the generator will
    *                 call the `updatez function` again, with the same current value, after `interval`.
    *                 The exception will be ignored.
    * @param ec       The execution context in which the generator works. Optional.
    *                 By default it's `Threading.defaultContext`.
    * @tparam V       The type of the generator's value.
    * @return         A new generator signal.
    */
  inline def generateVariant[V](init: V, interval: V => Long)(update: V => V)
                               (using ec: ExecutionContext = Threading.defaultContext): GeneratorSignal[V] =
    new GeneratorSignal[V](init, update, interval, (_: V) => false)

  /**
    * A utility method which works in a way that can be imagined as an inversion of `.fold` methods in Scala collections
    * (or rather `.foldLeft`, but there is no `.unfoldRight` in this case).
    * Given the initial value and the `update` method which creates a tuple from that value, `unfold` will repeatedly
    * change the internal value of the generator signal to the tuple, but publish only its second element. Then, after
    * the given `interval`, it will again call `update` on that new internal value to produce a tuple, and the process
    * will continue.
    *
    * Note that in contrast to other methods creating generator signals, `unfold` calls the `update` method for
    * the first time already at initialization, to produce the first tuple (the usual case is to call `update` for
    * the first time only after the first interval).
    *
    * An example use case - a generator signal publishing consecutive numbers in the Fibonacci sequence every second:
    * ```scala
    * GeneratorSignal.unfold((0, 1), 1.second) { case (a, b) => (b, a + b) -> b }
    * ```
    *
    * @param init     The initial, internal value of the generator signal
    * @param interval Time to the next update.
    * @param update   A function that, every time it's called, takes the current, internal value of the signal and
    *                 returns a tuple where the first element is the internal value, and the second element is
    *                 published if it is different from the old one. If the `update` methods throws an exception,
    *                 the value will not be updated, but the generator will call the `update` function again, with
    *                 the same current value, after `interval`. The exception will be ignored.
    * @param ec       The execution context in which the generator works. Optional.
    *                 By default it's `Threading.defaultContext`.
    * @tparam V       The type of the generator's internal value.
    * @tparam Z       The type of the generator's published value.
    * @return         A new generator signal.
    */
  inline def unfold[V, Z](init: V, interval: FiniteDuration)(update: V => (V, Z))
                         (using ec: ExecutionContext = Threading.defaultContext): CloseableSignal[Z] =
    Transformers.map[(V, Z), Z](
      new GeneratorSignal[(V, Z)](update(init), { case (v, _) => update(v) }, interval, _ => false)
    )(_._2)

  /**
    * A utility method which works in a way that can be imagined as an inversion of `.fold` methods in Scala collections
    * (or rather `.foldLeft`, but there is no `.unfoldRight` in this case).
    * Given the initial value and the `update` method which creates a tuple from that value, `unfold` will repeatedly
    * change the internal value of the generator signal to the tuple, but publish only its second element. Then, after
    * the given interval - generated by a function `interval` called initially and after each update -  it will again
    * call `update` on that new internal value to produce a tuple, and the process will continue.
    *
    * Note that in contrast to other methods creating generator signals, `unfold` calls the `update` method for
    * the first time already at initialization, to produce the first tuple (the usual case is to call `update` for
    * the first time only after the first interval).
    *
    * @param init     The initial, internal value of the generator signal
    * @param interval A function that returns the number of milliseconds to the next update.
    * @param update   A function that, every time it's called, takes the current, internal value of the signal and
    *                 returns a tuple where the first element is the internal value, and the second element is
    *                 published if it is different from the old one. If the `update` methods throws an exception,
    *                 the value will not be updated, but the generator will call the `update` function again, with
    *                 the same current value, after `interval`. The exception will be ignored.
    * @param ec       The execution context in which the generator works. Optional.
    *                 By default it's `Threading.defaultContext`.
    * @tparam V       The type of the generator's internal value.
    * @tparam Z       The type of the generator's published value.
    * @return         A new generator signal.
    */
  inline def unfoldVariant[V, Z](init: V, interval: V => Long)(update: V => (V, Z))
                                (using ec: ExecutionContext = Threading.defaultContext): CloseableSignal[Z] =
    Transformers.map[(V, Z), Z](
      new GeneratorSignal[(V, Z)](
        update(init),
        { case (v, _) => update(v) },
        { case (v, _) => interval(v) },
        _ => false
      )
    )(_._2)

  /**
    * A utility method that works as a counter. The counter starts at zero and every given interval` it's incremented
    * by one.
    *
    * @param interval Time to the next update.
    * @param ec       The execution context in which the generator works. Optional.
    *                 By default it's `Threading.defaultContext`.
    * @return         A new generator signal of integers.
    */
  inline def counter(interval: FiniteDuration)
                    (using ec: ExecutionContext = Threading.defaultContext): GeneratorSignal[Int] =
    generate(0, interval)(_ + 1)
