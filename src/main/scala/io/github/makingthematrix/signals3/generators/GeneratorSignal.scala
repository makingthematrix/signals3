package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.Closeable.CloseableSignal
import io.github.makingthematrix.signals3.{Closeable, CloseableFuture, Finite, Indexed, Signal}
import io.github.makingthematrix.signals3.EventSource.NoAutowiring
import io.github.makingthematrix.signals3.Finite.FiniteSignal

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.util.chaining.scalaUtilChainingOps

protected trait VPausable[V] {
  val paused: V => Boolean
}

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
  * @param interval Time to the next event generation (to the first event as well). Might be either a `FiniteDuration`
  *                 or a function that returns the number of milliseconds. In the second case, the function will be
  *                 called on initialization, and then after each generated event.
  * @tparam V       The type of the signal's value.
  */

abstract class GeneratorSignal[V](init : V, interval: FiniteDuration | (V => FiniteDuration))
                                 (using ExecutionContext)
  extends Signal[V](Some(init)) with NoAutowiring {

  protected lazy val beat: CloseableFuture[Unit] = (interval match {
    case intv: FiniteDuration        => CloseableFuture.repeat(intv)(_)
    case intv: (V => FiniteDuration) => CloseableFuture.repeatVariant(() => intv(currentValue.getOrElse(init)))(_)
  }) { onBeat() }

  private val isInitialized: AtomicBoolean = new AtomicBoolean(false)

  protected def onBeat(): Unit = if (!isInitialized.getAndSet(true)) beat

  protected[signals3] final def initialize(): Unit = {
    isInitialized.set(true)
    beat
  }
}

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

object GeneratorSignal {
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
    * @tparam V       The type of the signal's value.
    * @return         A new generator signal.
    */
  def apply[V](init    : V,
               update  : V => V,
               interval: FiniteDuration,
               paused  : V => Boolean = (_: V) => false)
              (using ExecutionContext): CloseableGeneratorSignal[V] =
    new CloseableGeneratorSignal[V](init, update, interval, paused).tap(_.initialize())

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
    *                 By default it's `Threading.defaultContext`.
    * @tparam V       The type of the signal's value.
    * @return         A new generator signal.
    */
  inline def generate[V](init: V, interval: FiniteDuration | (V => FiniteDuration))(update: V => V)
                        (using ExecutionContext): CloseableGeneratorSignal[V] =
    new CloseableGeneratorSignal[V](init, update, interval, (_: V) => false).tap(_.initialize())


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
    *                 By default it's `Threading.defaultContext`.
    * @tparam V       The type of the generator's internal value.
    * @tparam Z       The type of the generator's published value.
    * @return         A new generator signal.
    */
  inline def unfold[V, Z](init: V, interval: FiniteDuration)(update: V => (V, Z))
                         (using ExecutionContext): CloseableSignal[Z] =
    Transformers.map[(V, Z), Z](
      new CloseableGeneratorSignal[(V, Z)](update(init), { (v, _) => update(v) }, interval, _ => false).tap(_.initialize())
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
    *                 By default it's `Threading.defaultContext`.
    * @tparam V       The type of the generator's internal value.
    * @tparam Z       The type of the generator's published value.
    * @return         A new generator signal.
    */
  inline def unfold[V, Z](init: V, interval: V => FiniteDuration)(update: V => (V, Z))
                         (using ExecutionContext): CloseableSignal[Z] =
    Transformers.map[(V, Z), Z](
      new CloseableGeneratorSignal[(V, Z)](
        update(init),
        { case (v, _) => update(v) },
        { case (v, _) => interval(v) },
        _ => false
      ).tap(_.initialize())
    )(_._2)

  /**
    * A utility method that works as a counter. The counter starts at zero and every given interval` it's incremented
    * by one.
    *
    * @param interval Time to the next update.
    *                 By default it's `Threading.defaultContext`.
    * @return         A new generator signal of integers.
    */
  inline def counter(interval: FiniteDuration | (Int => FiniteDuration))
                    (using ExecutionContext): CloseableGeneratorSignal[Int] =
    generate(0, interval)(_ + 1)

  /**
   * Creates a [[Finite]] signal which goes through values from the given collection.
   *
   * @param values   A finite collection of values. The first value becomes the initial value of the signal.
   * @param interval Time to the next value change. See [[CloseableFuture.repeat]]
   *                 for explanation how Signals3 tries to ensure that intervals are constant.
   * @tparam V       The type of the value.
   * @return         A generator signal.
   */
  inline def from[V](values: Iterable[V], interval: FiniteDuration | (V => FiniteDuration))
                    (using ExecutionContext): FiniteGeneratorSignal[V] =
    FiniteGeneratorSignal[V](values, interval)

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
  inline def from[V](generate: () => Option[V], interval: FiniteDuration | (V => FiniteDuration))
             (using ExecutionContext): FiniteGeneratorSignal[V] = {
    FiniteGeneratorSignal[V](generate, interval)
  }

  /**
   * Creates a signal which goes through values obtained from a lazy list.
   *
   * @param values A lazy list of values. The first value becomes the initial value of the signal.
   * @param interval Time to the next value change. See [[CloseableFuture.repeat]]
   *                 for explanation how Signals3 tries to ensure that intervals are constant.
   * @tparam V The type of the value.
   * @return A generator signal.
   */
  inline def from[V](values: LazyList[V], interval: FiniteDuration | (V => FiniteDuration))
                    (using ExecutionContext): LazyListGeneratorSignal[V] =
    LazyListGeneratorSignal[V](values, interval)
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
