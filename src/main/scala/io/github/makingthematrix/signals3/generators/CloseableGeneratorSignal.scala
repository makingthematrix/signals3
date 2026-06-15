package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.Closeable

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import GeneratorSignal.VPausable

/**
  * A signal capable of generating new values in the given intervals of time. The interval can be given either as
  * [[FiniteDuration]] or as a function that will return [[FiniteDuration]] every time it's called.
  *
  * @note If you use the constant [[FiniteDuration]] as the interval (not the function), the generator will anyway try
  *       to adjust for inevitable delays caused by calling its own code.
  *       We can assume that the initialization will cause the first call to be executed with some delay, so the second
  *       call will be executed a bit earlier than `interval` to accomodate that. The next calls should be executed
  *       as planned, unless external causes will make another delay, after which the `repeat` method will again
  *       try to adjust by shortening the delay for the consecutive call.
  * .
  * @param init     The initial value of the generator signal.
  * @param update   A function that takes the current value of the signal and creates a new value every time it's called.
  *                 If the new value is different from the old one, it will be published in the signal. If the function
  *                 throws an exception, the value won't change, but the generator will call the `update` function
  *                 again, after `interval`. The exception will be ignored.
  * @param interval Time to the next `update` call. Might be either a [[FiniteDuration]] or a function that returns
  *                 [[FiniteDuration]], based on the current value of the signal. In the second case, the function will
  *                 be called on initialization, and then after each `update` call.
  * @param paused   A function called before each `update` to check if the generator is paused. If it returns `true`,
  *                 the `update` function will not be called.
  * @param ec       The execution context in which the generator works.
  * @tparam V       The type of the signal's value.
  */
class CloseableGeneratorSignal[V](init: V,
                                  update: V => V,
                                  interval: FiniteDuration | (V => FiniteDuration),
                                  override val paused: V => Boolean)
                                 (using ec: ExecutionContext)
  extends GeneratorSignal[V](init, interval) with Closeable with VPausable[V] {
  override protected def onBeat(): Unit = {
    super.onBeat()
    if (!currentValue.exists(paused) && !isClosed) currentValue.foreach(v => publish(update(v), ec))
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
}
