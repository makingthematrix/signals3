package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.EventSource.NoAutowiring
import io.github.makingthematrix.signals3.Finite.FiniteStream
import io.github.makingthematrix.signals3.{Closeable, CloseableFuture, Finite, Indexed, Stream}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.util.chaining.scalaUtilChainingOps

trait EPausable {
  val paused: () => Boolean
}

/**
  * A stream capable of generating new events in the given intervals of time, by repeatedly calling a function
  * that returns a new event. The interval can be given either as `FiniteDuration` or as a function that will return
  * the number of milliseconds in `Long` every time it's called. The difference in returned types is there to avoid
  * repeated wrapping and unwrapping of milliseconds in `FiniteDuration` but it also means that it is now on the user
  * to ensure that the interval describes time in ms. If the interval is 0 or negative, the event will be generated
  * immediately. The first event will be generated after the first interval.
  *
  * @note The type of `interval` may change in the future releases either way between `FiniteDuration` and `Long`.
  *
  * @note If you use the constant `FiniteDuration` as the interval (not the function), the generator will anyway try
  *       to adjust for inevitable delays caused by calling its own code.
  *       We assume that the initialization will cause the first call to be executed with some delay, so the second
  *       call will be executed a bit earlier than `interval` to accomodate that. The next calls should be executed
  *       as planned, unless external causes will make another delay, after which the `repeat` method will again
  *       try to adjust by shortening the delay for the consecutive call.
  *
  * @param generate A function that creates a new event `E` every time it's called. The event will be resealed in
  *                 the stream. If the function throws an exception, no event will be generated, but the generator
  *                 will call the `generate` function again, after `interval`. The exception will be ignored.
  * @param interval Time to the next event generation (to the first event as well). Might be either a `FiniteDuration`
  *                 or a function that returns the number of milliseconds. In the second case, the function will be
  *                 called on initialization, and then after each generated event.
  * @param paused   A function called on each event to check if the generator is paused. If it returns `true`,
  *                 the `generate` function will not be called.
  * @param ec       The execution context in which the generator works.
  * @tparam E       The type of the generated event.
  */
abstract class GeneratorStream[E](interval: FiniteDuration | (() => Long))(using ec: ExecutionContext)
  extends Stream[E] with NoAutowiring {

  protected lazy val beat: CloseableFuture[Unit] =
    (interval match {
       case intv: FiniteDuration => CloseableFuture.repeat(intv)
       case intv: (() => Long)   => CloseableFuture.repeatVariant(intv)
    }) {
      onBeat()
    }

  private var isInitialized = false

  protected def onBeat(): Unit =
    if (!isInitialized) {
      beat
      isInitialized = true
    }

  protected[signals3] final def initialize(): Unit = {
    beat
    isInitialized = true
  }
}

class CloseableGeneratorStream[E](interval: FiniteDuration | (() => Long),
                                  generate: () => E,
                                  override val paused: () => Boolean)
                                 (using ec: ExecutionContext)
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

  override inline def onClose(body: => Unit): Unit = beat.onClose(body)
}

class FiniteGeneratorStream[E](interval: FiniteDuration | (() => Long),
                               val events: Iterable[E],
                               override val paused : () => Boolean)
                              (using ec: ExecutionContext)
  extends GeneratorStream[E](interval) with Finite[E] with Indexed with EPausable {
  private val it = events.iterator
  override def isClosed: Boolean = super.isClosed || it.isEmpty

  override protected def onBeat(): Unit = {
    super.onBeat()
    if (!isClosed && !paused()) {
      val event = it.next()
      inc()
      publish(event)
      if (isClosed) {
        beat.close()
        lastPromise.foreach {
          case p if !p.isCompleted => p.trySuccess(event)
          case _ =>
        }
      }
    }
  }

  lazy val init: FiniteStream[E] = this.take(events.size - 1)
}

class LazyListGeneratorStream[E](interval: FiniteDuration | (() => Long),
                                 val events: LazyList[E],
                                 override val paused : () => Boolean)
                                (using ec: ExecutionContext)
  extends GeneratorStream[E](interval) with Indexed with EPausable {

  override protected def onBeat(): Unit = {
    super.onBeat()
    if (!paused()) {
      val event = events(counter)
      inc()
      publish(event)
    }
  }
}

object GeneratorStream {
  /**
    * Creates a stream which generates a new event every `interval` by calling the `generate` function which
    * returns an event and publishing it.
    *
    * @param generate A function that creates a new event `E` every time it's called. The event will be resealed
    *                 in the stream. If the function throws an exception, no event will be generated, but
    *                 the generator will call the `generate` function again, after `interval`. The exception
    *                 will be ignored.
    * @param interval Time to the next event generation (to the first event as well). 
    * @param paused   A function called on each event to check if the generator is paused. If it returns `true`,
    *                 the `generate` function will not be called. Optional. By default the generator is never paused.
    * @param ec       The execution context in which the generator works. Optional. 
    *                 By default it's `Threading.defaultContext`.
    * @tparam E       The type of the generated event.
    * @return         A new generator stream.
    */
  def apply[E](generate: () => E,
               interval: FiniteDuration,
               paused  : () => Boolean = () => false)
              (using ec: ExecutionContext): CloseableGeneratorStream[E] =
    new CloseableGeneratorStream[E](interval, generate, paused).tap(_.initialize())

  /**
    * Creates a stream which generates a new event every `interval` by calling the `generate` function which
    * returns an event and publishing it.
    *
    * @param interval Time to the next event generation (and to the first event as well). See [[CloseableFuture.repeat]]
    *                 for explanation how Signals3 tries to ensure that intervals are constant.
    * @param body     A block of code that creates a new event `E` every time it's called. The event will be published
    *                 in the stream. If the code throws an exception, no event will be generated, but the generator 
    *                 will call it again, after `interval`. The exception will be ignored.
    * @param ec       The execution context in which the generator works. Optional.
    *                 By default it's `Threading.defaultContext`.
    * @tparam E       The type of the generated event.
    * @return         A generator stream.
    */
  inline def generate[E](interval: FiniteDuration | (() => Long))(body: => E)
                        (using ec: ExecutionContext): CloseableGeneratorStream[E] =
    new CloseableGeneratorStream[E](interval, () => body, () => false).tap(_.initialize())

  /**
    * Creates a stream which publishes the same event every `interval`.
    *
    * @param event    The event which will be published in the stream every `interval`
    * @param interval Time to the next event generation (and to the first event as well). See [[CloseableFuture.repeat]]
    *                 for explanation how Signals3 tries to ensure that intervals are constant.
    * @param ec       The execution context in which the generator works. Optional.
    *                 By default it's `Threading.defaultContext`.
    * @tparam E       The type of the generated event.
    * @return         A generator stream.
    */
  inline def repeat[E](event: E, interval: FiniteDuration | (() => Long))
                      (using ec: ExecutionContext): CloseableGeneratorStream[E] =
    new CloseableGeneratorStream[E](interval, () => event, () => false).tap(_.initialize())

  /**
    * A utility method that creates a stream which publishes `Unit` every given `interval`.
    *
    * @param interval Time to the next event generation (and to the first event as well). See [[CloseableFuture.repeat]]
    *                 for explanation how Signals3 tries to ensure that intervals are constant.
    * @param ec       The execution context in which the generator works. Optional.
    *                 By default it's `Threading.defaultContext`.
    * @return A generator stream.
    */
  inline def heartbeat(interval: FiniteDuration | (() => Long))
                      (using ec: ExecutionContext): CloseableGeneratorStream[Unit] =
    repeat((), interval).tap(_.initialize())

  inline def from[E](events: Iterable[E], interval: FiniteDuration | (() => Long))
                    (using ec: ExecutionContext): FiniteGeneratorStream[E] =
    FiniteGeneratorStream[E](events, interval)

  inline def from[E](generate: () => Option[E], interval: FiniteDuration | (() => Long))
                    (using ec: ExecutionContext): FiniteGeneratorStream[E] =
    FiniteGeneratorStream[E](generate, interval)

  inline def from[E](events: LazyList[E], interval: FiniteDuration | (() => Long))
                    (using ec: ExecutionContext): LazyListGeneratorStream[E] =
    LazyListGeneratorStream[E](events, interval)
}

object FiniteGeneratorStream {
  inline def apply[E](events: Iterable[E], interval: FiniteDuration | (() => Long))
                     (using ec: ExecutionContext): FiniteGeneratorStream[E] =
    new FiniteGeneratorStream[E](interval, events, () => false).tap(_.initialize())

  def apply[E](generate: () => Option[E], interval: FiniteDuration | (() => Long))
              (using ec: ExecutionContext): FiniteGeneratorStream[E] = {
    val it = Iterable.from(new Iterator[E]{
      private var _next = generate()
      override def hasNext: Boolean = _next.isDefined
      override def next(): E = _next.get.tap { _ => _next = generate() }
    })
    new FiniteGeneratorStream[E](interval, it, () => false).tap(_.initialize())
  }
}

object LazyListGeneratorStream {
  inline def apply[E](events: LazyList[E], interval: FiniteDuration | (() => Long))
                     (using ec: ExecutionContext): LazyListGeneratorStream[E] =
    new LazyListGeneratorStream[E](interval, events, () => false).tap(_.initialize())
}