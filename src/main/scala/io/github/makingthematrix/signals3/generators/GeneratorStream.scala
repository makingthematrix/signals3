package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.EventSource.NoAutowiring
import io.github.makingthematrix.signals3.{Closeable, CloseableFuture, Finite, Indexed, Stream, TakeStream}

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.util.chaining.scalaUtilChainingOps

protected trait EPausable {
  val paused: () => Boolean
}

/**
  * A stream capable of generating new events in the given intervals of time. The interval can be given either as
  * [[FiniteDuration]] or as a function that will return [[FiniteDuration]] every time it's called.
  *
  * @note If you use the constant [[FiniteDuration]] as the interval (not the function), the generator will anyway try
  *       to adjust for inevitable delays caused by calling its own code.
  *       We can assume that the initialization will cause the first call to be executed with some delay, so the second
  *       call will be executed a bit earlier than `interval` to accomodate that. The next calls should be executed
  *       as planned, unless external causes will make another delay, after which the `repeat` method will again
  *       try to adjust by shortening the delay for the consecutive call.
 *
  * @param interval Time to the next event generation (to the first event as well). Might be either a [[FiniteDuration]]
  *                 or a function that returns [[FiniteDuration]]. In the second case, the function will be
  *                 called on initialization, and then after each generated event.
  * @tparam E       The type of the generated event.
  */
abstract class GeneratorStream[E](interval: FiniteDuration | (() => FiniteDuration))
                                 (using ExecutionContext)
  extends Stream[E] with NoAutowiring {

  protected lazy val beat: CloseableFuture[Unit] =
    (interval match {
       case intv: FiniteDuration         => CloseableFuture.repeat(intv)(_)
       case intv: (() => FiniteDuration) => CloseableFuture.repeatVariant(intv)(_)
    }) {
      onBeat()
    }

  private val isInitialized: AtomicBoolean = new AtomicBoolean(false)

  protected def onBeat(): Unit = if (!isInitialized.getAndSet(true)) beat

  protected[signals3] final def initialize(): Unit = {
    isInitialized.set(true)
    beat
  }
}

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
class CloseableGeneratorStream[E](interval: FiniteDuration | (() => FiniteDuration),
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

/**
 * A [[Finite]] stream capable of generating new events in the given intervals of time, by iterating over a collection
 * of events. The interval can be given either as [[FiniteDuration]] or as a function that will return [[FiniteDuration]]
 * every time it's called.
 *
 * @note If you use the constant `FiniteDuration` as the interval (not the function), the generator will anyway try
 *       to adjust for inevitable delays caused by calling its own code.
 *       We can assume that the initialization will cause the first call to be executed with some delay, so the second
 *       call will be executed a bit earlier than `interval` to accomodate that. The next calls should be executed
 *       as planned, unless external causes will make another delay, after which the `repeat` method will again
 *       try to adjust by shortening the delay for the consecutive call.
 * @param interval Time to the next event generation (to the first event as well). Might be either a [[FiniteDuration]]
 *                 or a function that returns [[FiniteDuration]]. In the second case, the function will be
 *                 called on initialization, and then after each generated event.
 * @param events   A collection of events to generate. When the generator reaches the end of the collection, it will
 *                 be closed.
 * @param paused   A function called on each event to check if the generator is paused. If it returns `true`,
 *                 the generator will pause going through the events collection.
 * @tparam E       The type of the generated event.
 */
class FiniteGeneratorStream[E](interval: FiniteDuration | (() => FiniteDuration),
                               val events: Iterable[E],
                               override val paused : () => Boolean)
                              (using ExecutionContext)
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

  /**
   * A [[TakeStream]] which will publish all the events from the generator except the last one. See [[Finite.last]].
   */
  lazy val init: TakeStream[E] = this.take(events.size - 1)
}

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

object GeneratorStream {
  /**
    * Creates a [[Closeable]] stream which generates a new event every `interval` by calling the `generate` function
    * which returns an event and publishing it.
    *
    * @param generate A function that creates a new event `E` every time it's called. The event will be resealed
    *                 in the stream. If the function throws an exception, no event will be generated, but
    *                 the generator will call the `generate` function again, after `interval`. The exception
    *                 will be ignored.
    * @param interval Time to the next event generation (to the first event as well). 
    * @param paused   A function called on each event to check if the generator is paused. If it returns `true`,
    *                 the `generate` function will not be called. Optional. By default the generator is never paused.
    * @tparam E       The type of the generated event.
    * @return         A new generator stream.
    */
  def apply[E](generate: () => E,
               interval: FiniteDuration,
               paused  : () => Boolean = () => false)
              (using ExecutionContext): CloseableGeneratorStream[E] =
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
    *                 By default it's `Threading.defaultContext`.
    * @tparam E       The type of the generated event.
    * @return         A generator stream.
    */
  inline def generate[E](interval: FiniteDuration | (() => FiniteDuration))(body: => E)
                        (using ExecutionContext): CloseableGeneratorStream[E] =
    new CloseableGeneratorStream[E](interval, () => body, () => false).tap(_.initialize())

  /**
    * Creates a [[Closeable]] stream which publishes the same event every `interval`.
    *
    * @param event    The event which will be published in the stream every `interval`
    * @param interval Time to the next event generation (and to the first event as well). See [[CloseableFuture.repeat]]
    *                 for explanation how Signals3 tries to ensure that intervals are constant.
    * @tparam E       The type of the generated event.
    * @return         A generator stream.
    */
  inline def repeat[E](event: E, interval: FiniteDuration | (() => FiniteDuration))
                      (using ExecutionContext): CloseableGeneratorStream[E] =
    new CloseableGeneratorStream[E](interval, () => event, () => false).tap(_.initialize())

  /**
    * A utility method that creates a [[Closeable]] stream which publishes `Unit` every given `interval`.
    *
    * @param interval Time to the next event generation (and to the first event as well). See [[CloseableFuture.repeat]]
    *                 for explanation how Signals3 tries to ensure that intervals are constant.
    * @return         A generator stream.
    */
  inline def heartbeat(interval: FiniteDuration | (() => FiniteDuration))
                      (using ExecutionContext): CloseableGeneratorStream[Unit] =
    repeat((), interval).tap(_.initialize())

  /**
   * Creates a [[Finite]] stream which generates events from the given collection.
   * @param events A finite collection of events.
   * @param interval Time to the next event generation (and to the first event as well). See [[CloseableFuture.repeat]]
   *                 for explanation how Signals3 tries to ensure that intervals are constant.
   * @tparam E       The type of the generated event.
   * @return         A generator stream.
   */
  inline def from[E](events: Iterable[E], interval: FiniteDuration | (() => FiniteDuration))
                    (using ExecutionContext): FiniteGeneratorStream[E] =
    FiniteGeneratorStream[E](events, interval)

  /**
   * Creates a [[Finite]] stream which generates events by calling a function that returns an option of an event.
   * If the option is empty, the stream will be closed.
   * @param generate A function that returns an event or `None` if the stream should be closed.
   * @param interval Time to the next event generation (and to the first event as well). See [[CloseableFuture.repeat]]
   *                 for explanation how Signals3 tries to ensure that intervals are constant.
   * @tparam E       The type of the generated event.
   * @return         A generator stream.
   */
  inline def from[E](generate: () => Option[E], interval: FiniteDuration | (() => FiniteDuration))
                    (using ExecutionContext): FiniteGeneratorStream[E] =
    FiniteGeneratorStream[E](generate, interval)

  /**
   * Creates a stream which generates events from the given lazy list.
   * @param events   A lazy list of events.
   * @param interval Time to the next event generation (and to the first event as well). See [[CloseableFuture.repeat]]
   *                 for explanation how Signals3 tries to ensure that intervals are constant.
   * @tparam E       The type of the generated event.
   * @return         A generator stream.
   */
  inline def from[E](events: LazyList[E], interval: FiniteDuration | (() => FiniteDuration))
                    (using ExecutionContext): LazyListGeneratorStream[E] =
    LazyListGeneratorStream[E](events, interval)
}

object FiniteGeneratorStream {
  /**
   * Creates a [[Finite]] stream which generates events from the given collection.
   *
   * @param events   A finite collection of events.
   * @param interval Time to the next event generation (and to the first event as well). See [[CloseableFuture.repeat]]
   *                 for explanation how Signals3 tries to ensure that intervals are constant.
   * @tparam E       The type of the generated event.
   * @return         A generator stream.
   */
  inline def apply[E](events: Iterable[E], interval: FiniteDuration | (() => FiniteDuration))
                     (using ExecutionContext): FiniteGeneratorStream[E] =
    new FiniteGeneratorStream[E](interval, events, () => false).tap(_.initialize())

  /**
   * Creates a [[Finite]] stream which generates events by calling a function that returns an option of an event.
   * If the option is empty, the stream will be closed.
   *
   * @param generate A function that returns an event or `None` if the stream should be closed.
   * @param interval Time to the next event generation (and to the first event as well). See [[CloseableFuture.repeat]]
   *                 for explanation how Signals3 tries to ensure that intervals are constant.
   * @tparam E The type of the generated event.
   * @return A generator stream.
   */
  def apply[E](generate: () => Option[E], interval: FiniteDuration | (() => FiniteDuration))
              (using ExecutionContext): FiniteGeneratorStream[E] = {
    val it = Iterable.from(new Iterator[E]{
      private var _next = generate()
      override def hasNext: Boolean = _next.isDefined
      override def next(): E = _next.get.tap { _ => _next = generate() }
    })
    new FiniteGeneratorStream[E](interval, it, () => false).tap(_.initialize())
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