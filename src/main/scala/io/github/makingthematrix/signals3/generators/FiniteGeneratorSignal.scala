package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.Finite.FiniteSignal
import io.github.makingthematrix.signals3.{Finite, Indexed}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.util.chaining.scalaUtilChainingOps
import GeneratorSignal.VPausable

/**
  * A [[Finite]] signal capable of generating new values in the given intervals of time, by iterating over a collection
  * of values. The interval can be given either as [[FiniteDuration]] or as a function that will return [[FiniteDuration]]
  * every time it's called.
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
  * @param values   A collection of values that the generator goes through. When the generator reaches
  *                 the end of the collection, it will be closed.
  * @param paused   A function called before each `update` to check if the generator is paused. If it returns `true`,
  *                 the `update` function will not be called.
  * @param ec       The execution context in which the generator works.
  * @tparam V       The type of the signal's value.
  */
class FiniteGeneratorSignal[V](interval: FiniteDuration | (V => FiniteDuration),
                               val values: Iterable[V],
                               override val paused: V => Boolean)
                              (using ec: ExecutionContext)
  extends GeneratorSignal[V](values.head, interval) with Finite[V] with Indexed with VPausable[V] {
  inc() // the first value in values becomes the initial value, so we already increase the counter to 1
  private val it = values.tail.iterator

  override def isClosed: Boolean = super.isClosed || it.isEmpty

  override protected def onBeat(): Unit = {
    super.onBeat()
    if (!currentValue.exists(paused) && !isClosed) {
      val v = it.next()
      inc()
      publish(v, ec)
      if (isClosed) {
        beat.close()
        lastPromise.foreach {
          case p if !p.isCompleted => p.trySuccess(v)
          case _ =>
        }
      }
    }
  }

  /**
    * A [[io.github.makingthematrix.signals3.TakeSignal]] which will publish all the events from the generator except the last one. See [[Finite.last]].
    */
  lazy val init: FiniteSignal[V] = this.take(values.size - 1)
}

object FiniteGeneratorSignal {
  /**
    * Creates a [[Finite]] signal which goes through values from the given collection.
    *
    * @param values   A finite collection of values. The first value becomes the initial value of the signal.
    * @param interval Time to the next value change. See [[CloseableFuture.repeat]]
    *                 for explanation how Signals3 tries to ensure that intervals are constant.
    * @tparam V The type of the value.
    * @return A generator signal.
    */
  inline def apply[V](values: Iterable[V], interval: FiniteDuration | (V => FiniteDuration))
                     (using ExecutionContext): FiniteGeneratorSignal[V] =
    new FiniteGeneratorSignal[V](interval, values, (_: V) => false).tap(_.initialize())

  /**
    * Creates a [[Finite]] signal which goes through values obtained by calling a function that returns an option of
    * a value. If the option is empty, the signal will be closed.
    *
    * @param generate A function that returns a value or `None` if the signal should be closed.
    *                 The first value becomes the initial value of the signal.
    * @param interval Time to the next value change. See [[CloseableFuture.repeat]]
    *                 for explanation how Signals3 tries to ensure that intervals are constant.
    * @tparam V The type of the value.
    * @return A generator signal.
    */
  def apply[V](generate: () => Option[V], interval: FiniteDuration | (V => FiniteDuration))
              (using ExecutionContext): FiniteGeneratorSignal[V] = {
    val it = Iterable.from(new Iterator[V]{
      private var value = generate()
      override def hasNext: Boolean = value.isDefined
      override def next(): V = value.get.tap { _ => value = generate() }
    })
    new FiniteGeneratorSignal[V](interval, it, (_: V) => false).tap(_.initialize())
  }
}
