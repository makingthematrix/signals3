package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.Closeable
import io.github.makingthematrix.signals3.generators.GeneratorStream.EPausable

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
  * A [[Closeable]] stream capable of generating new events in the given intervals of time, by repeatedly calling
  * a function that returns a new event. The interval can be given either as [[FiniteDuration]] or as a function that
  * will return [[FiniteDuration]] every time it's called.
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
  * @param generate A function that creates a new event `E` every time it's called. The event will be resealed in
  *                 the stream. If the function throws an exception, no event will be generated, but the generator
  *                 will call the `generate` function again, after `interval`. The exception will be ignored.
  * @param paused   A function called on each event to check if the generator is paused. If it returns `true`,
  *                 the `generate` function will not be called.
  * @tparam E       The type of the generated event.
  */
class CloseableGeneratorStream[E] protected[signals3] (interval: FiniteDuration | (() => FiniteDuration),
                                                       generate: () => E,
                                                       override val paused: () => Boolean)
                                                      (using ExecutionContext)
  extends GeneratorStream[E](interval) with Closeable with EPausable {

  override protected def onBeat(): Unit = {
    super.onBeat()
    if (!paused()) publish(generate())
  }

  /**
    * Closes the generator permanently. There will be no further calls to `generate`, `interval`, and `paused`.
    */
  override inline def closeAndCheck(): Boolean = beat.closeAndCheck()

  /**
    * Checks if the generator is closed.
    *
    * @return `true` if the generator was closed
    */
  override inline def isClosed: Boolean = beat.isClosed

  /**
    * Registers a snippet of code to execute when the generator is closed.
    * @param body Logic that is going to be executed when the generator is closed.
    */
  override inline def onClose(body: => Unit): Unit = beat.onClose(body)
}
