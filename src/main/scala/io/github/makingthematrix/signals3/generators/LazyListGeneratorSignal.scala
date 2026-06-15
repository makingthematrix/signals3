package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.Indexed

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.util.chaining.scalaUtilChainingOps
import GeneratorSignal.VPausable

/**
  * A signal capable of generating new values in the given intervals of time, by iterating over a lazy list of values.
  * The interval can be given either as [[FiniteDuration]] or as a function that will return [[FiniteDuration]] every
  * time it's called.
  *
  * @note If you use the constant [[FiniteDuration]] as the interval (not the function), the generator will anyway try
  *       to adjust for inevitable delays caused by calling its own code.
  *       We can assume that the initialization will cause the first call to be executed with some delay, so the second
  *       call will be executed a bit earlier than `interval` to accomodate that. The next calls should be executed
  *       as planned, unless external causes will make another delay, after which the `repeat` method will again
  *       try to adjust by shortening the delay for the consecutive call.
  * .
  * @param interval Time to the next `update` call. Might be either a [[FiniteDuration]] or a function that returns
  *                 [[FiniteDuration]], based on the current value of the signal. In the second case, the function will
  *                 be called on initialization, and then after each `update` call.
  * @param values A [[LazyList]] of value the generator goes through Technically, a lazy last is infinite so the
  *               generator will always have the next value to publish.
  * @param paused   A function called before each `update` to check if the generator is paused. If it returns `true`,
  *                 the `update` function will not be called.
  * @param ec       The execution context in which the generator works.
  * @tparam V       The type of the signal's value.
  */
class LazyListGeneratorSignal[V](interval: FiniteDuration | (V => FiniteDuration),
                                 val values: LazyList[V],
                                 override val paused: V => Boolean)
                                (using ec: ExecutionContext)
  extends GeneratorSignal[V](values.head, interval) with Indexed with VPausable[V] {
  inc() // the first value in values becomes the initial value, so we already increase the counter to 1

  override protected def onBeat(): Unit = {
    super.onBeat()
    if (!currentValue.exists(paused)) {
      val v =  values(getAndInc())
      publish(v, ec)
    }
  }
}

object LazyListGeneratorSignal {
  /**
    * Creates a signal which goes through values obtained from a lazy list.
    *
    * @param values   A lazy list of values. The first value becomes the initial value of the signal.
    * @param interval Time to the next value change. See [[CloseableFuture.repeat]]
    *                 for explanation how Signals3 tries to ensure that intervals are constant.
    * @tparam V The type of the value.
    * @return A generator signal.
    */
  inline def apply[V](values: LazyList[V], interval: FiniteDuration | (V => FiniteDuration))
                     (using ExecutionContext): LazyListGeneratorSignal[V] =
    new LazyListGeneratorSignal[V](interval, values, (_: V) => false).tap(_.initialize())
}