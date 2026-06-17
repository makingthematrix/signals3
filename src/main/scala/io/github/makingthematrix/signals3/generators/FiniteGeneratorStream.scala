package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.generators.GeneratorStream.EPausable
import io.github.makingthematrix.signals3.{Finite, Indexed, TakeStream}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.util.chaining.scalaUtilChainingOps

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
class FiniteGeneratorStream[E] protected[signals3] (interval: FiniteDuration | (() => FiniteDuration),
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
  def apply[E](events: Iterable[E], interval: FiniteDuration | (() => FiniteDuration))
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
