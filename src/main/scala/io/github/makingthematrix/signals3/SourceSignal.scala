package io.github.makingthematrix.signals3

import scala.annotation.targetName
import scala.concurrent.ExecutionContext

object SourceSignal:
  /** Creates a source signal initially holding the given value.
    *
    * @see also `Signal.apply`
    *
    * @param v The value of the signal
    * @tparam V The type of the value
    * @return A new source signal with the given value
    */
  def apply[V](v: V): SourceSignal[V] = new SourceSignal(Option(v))

  /** Creates an initially empty source signal.
    *
    * @see also `Signal.apply`
    *
    * @tparam V The type of the value
    * @return A new source signal
    */
  def apply[V](): SourceSignal[V] = new SourceSignal[V](None)

/** The usual entry point for publishing values in signals.
  *
  * Create a new signal either using the default constructor or the `Signal.apply[V]()` method. The source signal exposes
  * methods you can use for changing its value. Then you can combine it with other signals and finally subscribe a function
  * to it which will be called initially, and then on each change of the signal's value.
  *
  * @tparam V the type of the value held by the signal.
  */
class SourceSignal[V](protected val v: Option[V]) extends Signal[V](v):
  /** Changes the value of the signal.
    *
    * The original `publish` method of the [[Signal]] class is `protected` to ensure that intermediate signals - those created
    * by methods like `map`, `flatMap`, `filter`, etc. - will not be used to directly change their values. The source signal
    * exposes this method for public use.
    *
    * @see [[Signal]]
    *
    * @param value The new value of the signal.
    * @param ec The execution context used for dispatching. The default implementation ensures that if `ec` is the same as
    *           the execution context used to register the subscriber, the subscriber will be called immediately. Otherwise,
    *           a future working in the subscriber's execution context will be created and `ec` will be ignored.
    */
  override def publish(value: V, ec: ExecutionContext): Unit = super.publish(value, ec)

  /** Changes the value of the signal.
    *
    * The original `publish` method of the [[Signal]] class is `protected` to ensure that intermediate signals - those created
    * by methods like `map`, `flatMap`, `filter`, etc. - will not be used to directly change their values. The source signal
    * exposes this method for public use.
    *
    * @see [[Signal]]
    *
    * @param value The new value of the signal.
    */
  override def publish(value: V): Unit = super.publish(value)

  /** An alias for the `publish` method. */
  @targetName("bang")
  inline final def !(value: V): Unit = publish(value)

  /** A version of the `publish` method which takes the implicit execution context for dispatching.
    *
    * The difference between `!!` and `!` (and also between the two `publish` methods) is that even if the source's
    * execution context is the same as the subscriber's execution context, if we send an event using `!`, it will be
    * wrapped in a future and executed asychronously. If we use `!!` then for subscribers working in the same
    * execution context the call will be synchronous. This may be desirable in some cases, but please use with caution.
    */
  @targetName("twobang")
  inline final def !!(value: V)(using ec: ExecutionContext): Unit = publish(value, ec)

  /** Changes the value of the signal by applying the given function to the current value.
    * If the signal is empty, it will stay empty.
    *
    * @param f The function used to modify the signal's value.
    * @return true if the signal's value is actually changed to something different, and so the subscribers will be notified,
    *         false otherwise.
    */
  inline final def mutate(f: V => V): Boolean = update(_.map(f))

  /** Changes the value of the signal by applying the given function to the current value.
    * If the signal is empty, it will stay empty.
    *
    * @param f The function used to modify the signal's value.
    * @param ec The execution context used for dispatching. The default implementation ensures that if `ec` is the same as
    *           the execution context used to register the subscriber, the subscriber will be called immediately. Otherwise,
    *           a future working in the subscriber's execution context will be created and `ec` will be ignored.
    * @return true if the signal's value is actually changed to something different, and so the subscribers will be notified,
    *         false otherwise.
    */
  inline final def mutate(f: V => V, ec: ExecutionContext): Boolean = update(_.map(f), Some(ec))

  /** Changes the value of the signal by applying the given function to the current value.
    * If the signal is empty, it will be set to the given default value instead.
    *
    * @param f The function used to modify the signal's value.
    * @param default The default value used instead of `f` if the signal is empty.
    * @return true if the signal's value is actually changed to something different, and so the subscribers will be notified,
    *         false otherwise.
    */
  inline final def mutateOrDefault(f: V => V, default: V): Boolean = update(_.map(f).orElse(Some(default)))
