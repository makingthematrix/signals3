package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.Indexed
import io.github.makingthematrix.signals3.generators.GeneratorStream.EPausable

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.util.chaining.scalaUtilChainingOps

/**
  * A stream capable of generating new events in the given intervals of time, by iterating over a lazy list of events.
  * The interval can be given either as [[FiniteDuration]] or as a function that will return [[FiniteDuration]] every
  * time it's called.
  *
  * @note If you use the constant `FiniteDuration` as the interval (not the function), the generator will anyway try
  *       to adjust for inevitable delays caused by calling its own code.
  *       We assume that the initialization will cause the first call to be executed with some delay, so the second
  *       call will be executed a bit earlier than `interval` to accomodate that. The next calls should be executed
  *       as planned, unless external causes will make another delay, after which the `repeat` method will again
  *       try to adjust by shortening the delay for the consecutive call.
  * @param interval Time to the next event generation (to the first event as well). Might be either a [[FiniteDuration]]
  *                 or a function that returns [[FiniteDuration]]. In the second case, the function will be
  *                 called on initialization, and then after each generated event.
  * @param events   A [[LazyList]] of events to generate. Technically, a lazy last is infinite so the generator will
  *                 always have the next event to emit.
  * @param paused   A function called on each event to check if the generator is paused. If it returns `true`,
  *                 the generator will pause going through the events collection.
  * @tparam E       The type of the generated event.
  */
class LazyListGeneratorStream[E](interval: FiniteDuration | (() => FiniteDuration),
                                 val events: LazyList[E],
                                 override val paused : () => Boolean)
                                (using ExecutionContext)
  extends GeneratorStream[E](interval) with Indexed with EPausable {

  override protected def onBeat(): Unit = {
    super.onBeat()
    if (!paused()) {
      val event = events(getAndInc())
      publish(event)
    }
  }
}

object LazyListGeneratorStream {
  /**
    * Creates a stream which generates events from the given lazy list.
    *
    * @param events   A lazy list of events.
    * @param interval Time to the next event generation (and to the first event as well). See [[CloseableFuture.repeat]]
    *                 for explanation how Signals3 tries to ensure that intervals are constant.
    * @tparam E       The type of the generated event.
    * @return         A generator stream.
    */
  inline def apply[E](events: LazyList[E], interval: FiniteDuration | (() => FiniteDuration))
                     (using ExecutionContext): LazyListGeneratorStream[E] =
    new LazyListGeneratorStream[E](interval, events, () => false).tap(_.initialize())
}
