package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.priv.{ProxySignal, ProxyStream}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.chaining.*

/**
  * A stream or a signal can be closeable, meaning that it can be closed and after that it will not publish new events
  * anymore. Every [[GeneratorStream]] and [[GeneratorSignal]] is [[Closeable]] which allows for stopping them when
  * they're no longer needed, but you can make any new stream or signal inherit [[Closeable]] and implement the required
  * logic. 
  * [[Closeable]] extends [[java.lang.AutoCloseable]] so in theory it can be used in Java `try-with-resources`.
  *
  * @see [[ProxyStream]] and [[ProxySignal]] for examples.
  */
trait Closeable extends java.lang.AutoCloseable with CanBeClosed {
  override def closeAndCheck(): Boolean = super.closeAndCheck()

  /**
    * A version of `closeAndCheck()` which ignores the boolean result.
    * If the closeable is used in try-with-resources, this method will be called automatically.
    */
  final inline def close(): Unit = closeAndCheck()
}

object Closeable {
  import ProxySignal.*
  import ProxyStream.*

  /**
    * A type alias for a closeable stream.
    * @tparam E The event type of the stream.
    */
  type CloseableStream[E] = Stream[E] & Closeable
  /**
    * A type alias for a closeable signal.
    * @tparam V The value type of the signal.
    */
  type CloseableSignal[V] = Signal[V] & Closeable

  /**
    * Encapsulates logic for closing original streams/signals/c-futures, checking if they are closed, and calling
    * the registered `onClose` code (but only once, not once per the original source).
    */
  private trait Closeability(sources: Closeable*) extends Closeable{
    private var callOnCloseList: List[() => Unit] = Nil
    @volatile private var open = sources.length

    sources.foreach(_.onClose {
      synchronized {
        open -= 1
        if (open == 0) callOnCloseList.foreach(_())
      }
    })

    final override def closeAndCheck(): Boolean =
      sources.map(_.closeAndCheck()).forall(p => p)

    final override def isClosed: Boolean = sources.forall(_.isClosed)

    final override def onClose(body: => Unit): Unit =
      callOnCloseList = (() => body) :: callOnCloseList
  }

  /**
    * Creates a new `CloseableStream[V]` by mapping events of the type `E` emitted by the original generator or
    * another closeable stream.
    *
    * @param stream The original closeable stream.
    * @param f      The function mapping each event of type `E` into exactly one event of type `V`.
    * @tparam E The type of the original event.
    * @tparam V The type of the resulting event.
    * @return A new closeable stream of type `V`.
    */
  def map[E, V](stream: CloseableStream[E])(f: E => V): CloseableStream[V] =
    new MapStream[E, V](stream, f) with Closeability(stream)

  /**
    * Creates a new `CloseableSignal[Z]` bby mapping the value of the type V of the original generator or
    * another closeable stream.
    *
    * @param signal The original closeable signal.
    * @param f      The function mapping the original value of type `V` into a new value of the type `Z`.
    * @tparam V The type of the original value.
    * @tparam Z The type of the resulting value.
    * @return A new closeable signal of type `Z`.
    */
  def map[V, Z](signal: CloseableSignal[V])(f: V => Z): CloseableSignal[Z] =
    new MapSignal[V, Z](signal, f) with Closeability(signal)

  /**
    * Creates a new `CloseableStream[V]` by mapping events of the type `E` emitted by the original generator or
    * another closeable stream.
    *
    * @param stream The original closeable stream.
    * @param f      A function which for a given event of the type `E` will return a future of the type `V`.
    *               If the future finishes with success, the resulting event of the type `V` will be emitted by
    *               the new stream. Two events, coming one after another, are guaranteed to be mapped in the same order
    *               even if the processing for the second event finishes before the processing for the first one.
    *
    * @tparam E     The type of the original event.
    * @tparam V The type of the resulting event.
    * @return A new closeable stream of type `V`.
    */
  def mapSync[E, V](stream: CloseableStream[E])(f: E => Future[V]): CloseableStream[V] =
    new FutureStream[E, V](stream, f) with Closeability(stream)

  /**
    * Creates a new `CloaseableStream[E]` by filtering events emitted by the original one.
    *
    * @param stream    The original closeable stream.
    * @param predicate A filtering function which for each event emitted by the original stream returns true or false.
    *                  Only events for which `predicate(event)` returns true will be emitted in the resulting stream.
    *
    * @tparam E        The type of the emitted event.
    * @return A new closeable stream emitting only filtered events.
    */
  def filter[E](stream: CloseableStream[E])(predicate: E => Boolean): CloseableStream[E] =
    new FilterStream[E](stream, predicate) with Closeability(stream)

  /**
    * Creates a new `CloseableSignal[V]` which updates its value only if the new value of the original closeable signal
    * satisfies the filter, and changes to empty otherwise. Also, if the initial value of the original signal does not satisfy the filter,
    * the new signal will start empty.
    *
    * @param signal    The original closeable signal.
    * @param predicate A filtering function which for any value of the original signal returns true or false.
    * @tparam V The type of the value.
    * @return A new closeable signal of the same value type.
    */
  def filter[V](signal: CloseableSignal[V])(predicate: V => Boolean): CloseableSignal[V] =
    new FilterSignal[V](signal, predicate) with Closeability(signal)

  /**
    * Creates a new closeable stream of events of type `V` by applying a partial function which maps the original event
    * of type `E` to an event of type `V`. If the partial function doesn't work for the emitted event, nothing will be
    * emitted in the new stream. Basically, it's filter + map.
    *
    * @param stream The original closeable stream.
    * @param pf     A partial function which for an original event of type `E` may produce an event of type `V`.
    * @tparam E The type of the original event.
    * @tparam V The type of the resulting event.
    * @return A new closeable stream of type `V`.
    */
  def collect[E, V](stream: CloseableStream[E])(pf: PartialFunction[E, V]): CloseableStream[V] =
    new CollectStream[E, V](stream, pf) with Closeability(stream)

  /**
    * Creates a new closeable signal of values of the type `Z` by applying a partial function which maps the original
    * value of the type `V` to a value of the type `Z`. If the partial function doesn't work for the current value,
    * the new signal will become empty until the next update. Basically, it's filter + map.
    *
    * @param signal The original closeable signal.
    * @param pf     A partial function which for the original value of the type `V` may produce a value of the type `Z`.
    * @tparam V The type of the original value.
    * @tparam Z The type of the resulting value.
    * @return A new closeable signal of type `Z`.
    */
  def collect[V, Z](signal: CloseableSignal[V])(pf: PartialFunction[V, Z]): CloseableSignal[Z] =
    new CollectSignal[V, Z](signal, pf) with Closeability(signal)

  /**
    * Creates a new closeable stream by merging the original closeable streams of the same type.
    * The resulting stream will emit events coming from all sources. Closing the resulting stream will close all
    * the original ones. The `.isClosed` method will return `true` only if all original streams confirm that they
    * are closed.
    *
    * @note Note that this is different from `.zip` methods for [[CloseableSignal]] and from `.sequence`. This version
    *       of `.zip` emits single events of type `E` every time one of the original streams emits them.
    * @param streams An argument list of closeable streams of the same type.
    * @tparam E The type of the event.
    * @return A new closeable stream, emitting events from all original streams.
    */
  def zip[E](streams: CloseableStream[E]*): CloseableStream[E] =
    new JoinStream[E](streams *) with Closeability(streams *)

  /**
    * Creates a new closeable signal by joining together the original signals of two different types of values,
    * `A` and `B`. The resulting closeable signal will hold a tuple of the original values and update every time
    * one of them changes.
    *
    * Note this is *not* a method analogous to `.zip` for [[CloseableStream]]. Here the parent signals can be of
    * different types ([[CloseableStream]] `.zip` requires all parent streams to be of the same type) but on the other
    * hand we're not able to zip an arbitrary number of signals. Also, the result value is a tuple, not just one event
    * after another.
    *
    * @see Please see `.sequence` for a method which resembles `.zip` for [[CloseableStream]] in a different way.
    * @param a The first of the parent closeable signals.
    * @param b The second of the parent closeable signals.
    * @tparam A The type of the value of the first of parent closeable signals.
    * @tparam B The type of the value of the second of parent closeable signals.
    * @return A new signal its the value constructed as a tuple of values form the parent closeable signals.
    */
  def zip[A, B](a: CloseableSignal[A], b: CloseableSignal[B]): CloseableSignal[(A, B)] =
    new Zip2Signal[A, B](a, b) with Closeability(a, b)

  /** A version of the `.zip` method joining three closeable signals of different value types. */
  def zip[A, B, C](a: CloseableSignal[A], b: CloseableSignal[B], c: CloseableSignal[C]): CloseableSignal[(A, B, C)] =
    new Zip3Signal[A, B, C](a, b, c) with Closeability(a, b, c)

  /** A version of the `.zip` method joining four closeable signals of different value types. */
  def zip[A, B, C, D](a: CloseableSignal[A],
                      b: CloseableSignal[B],
                      c: CloseableSignal[C],
                      d: CloseableSignal[D]): CloseableSignal[(A, B, C, D)] =
    new Zip4Signal[A, B, C, D](a, b, c, d) with Closeability(a, b, c, d)

  /** A version of the `.zip` method joining five closeable signals of different value types. */
  def zip[A, B, C, D, E](a: CloseableSignal[A],
                         b: CloseableSignal[B],
                         c: CloseableSignal[C],
                         d: CloseableSignal[D],
                         e: CloseableSignal[E]): CloseableSignal[(A, B, C, D, E)] =
    new Zip5Signal[A, B, C, D, E](a, b, c, d, e) with Closeability(a, b, c, d, e)

  /** A version of the `.zip` method joining six closeable signals of different value types. */
  def zip[A, B, C, D, E, F](a: CloseableSignal[A],
                            b: CloseableSignal[B],
                            c: CloseableSignal[C],
                            d: CloseableSignal[D],
                            e: CloseableSignal[E],
                            f: CloseableSignal[F]): CloseableSignal[(A, B, C, D, E, F)] =
    new Zip6Signal[A, B, C, D, E, F](a, b, c, d, e, f) with Closeability(a, b, c, d, e, f)

  /**
    * Creates a closeable signal of an arbitrary number of parent signals of the same value.
    * The value of the new signal is the sequence of values of all parent signals in the same order.
    * You can actually think of it as an analogous method to `Stream.zip`. Closing the resulting signal will close all
    * the original ones. The `.isClosed` method will return `true` only if all original signals confirm that they
    * are closed.
    *
    * @see `.zip` method for [[CloseableStream]]
    * @param signals A variable arguments list of parent closeable signals of the same type.
    * @tparam V The type of the values in the parent closeable signals.
    * @return A new closeable signal with its value being a sequence of current values of the parent signals.
    */
  def sequence[V](signals: CloseableSignal[V]*): CloseableSignal[Seq[V]] =
    new SequenceSignal[V](signals *) with Closeability(signals *)

  /**
    * Combines the current values of this and another closeable signal of the same or different types `V` and `Z`
    * to produce a closeable signal with the value of yet another type `Y`. Basically, zip + map.
    *
    * @param vSignal The first closeable signal, with values of the type `V`.
    * @param zSignal The second closeable signal with values of the type `Z`.
    * @param f       The function which combines the current values of both parent signals to produce the value of
    *                the new closeable signal with values of the type `Y`.
    *
    * @tparam V      The value type of the first closeable signal.
    * @tparam Z The value type of the second closeable signal.
    * @tparam Y The value type of the new closeable signal.
    * @return A new closeable signal with the values of the type `Y`.
    */
  def combine[V, Z, Y](vSignal: CloseableSignal[V],
                       zSignal: CloseableSignal[Z])
                      (f: (V, Z) => Y): CloseableSignal[Y] =
    new CombineSignal[V, Z, Y](vSignal, zSignal, f) with Closeability(vSignal, zSignal)

  /**
    * Creates a new closeable stream from a closeable future.
    *
    * @param cFuture A closeable future that will eventually produce an event of the type `E`.
    * @param ec      The execution context in which the closeable future works. Optional.
    *                By default it's `Threading.defaultContext`.
    *
    * @tparam E      The type of the event produced by the closeable future.
    * @return A new closeable stream which will emit at most only one event of the type `E`.
    */
  def streamFromFuture[E](cFuture: CloseableFuture[E])
                         (using ec: ExecutionContext): CloseableStream[E] =
    new Stream[E]() with Closeability(cFuture).tap { stream =>
      cFuture.foreach {stream.dispatch(_, Some(ec))}
    }

  /**
    * Creates a new closeable signal from a closeable future.
    *
    * @param cFuture A closeable future that will eventually produce a value of the type `V`.
    * @param ec      The execution context in which the closeable future works. Optional.
    *                By default it's `Threading.defaultContext`.
    *
    * @tparam V      The type of the value produced by the closeable future.
    * @return A new closeable signal which starts empty and will update at most only once.
    */
  def signalFromFuture[V](cFuture: CloseableFuture[V])
                         (using ec: ExecutionContext): CloseableSignal[V] =
    new Signal[V]() with Closeability(cFuture).tap { signal =>
      cFuture.foreach { res => signal.updateWith(Option(res), Some(ec)) }
    }

  /** Creates a new closeable signal from a closeable stream and an initial value.
    * The signal will be initialized to the initial value on its creation, and subscribe to the stream.
    * Subsequently, it will update the value as new events are published in the parent stream.
    *
    * @param initial The initial value of the signal.
    * @param source  The parent stream.
    * @tparam V The type of both the initial value and the events in the parent stream.
    * @return A new signal with the value of the type `V`.
    */
  def signalFromStream[V](initial: V, source: CloseableStream[V]): CloseableSignal[V] =
    new StreamSignal[V](source, Option(initial)) with Closeability(source)

  /** Creates a new closeable signal from a closeable stream.
    * The signal will start uninitialized and subscribe to the parent stream. Subsequently, it will update its value
    * as new events are published in the parent stream.
    *
    * @param source The parent stream.
    * @tparam V The type of the events in the parent stream.
    * @return A new signal with the value of the type `V`.
    */
  def signalFromStream[V](source: CloseableStream[V]): CloseableSignal[V] =
    new StreamSignal[V](source, None) with Closeability(source)

  /**
    * Creates a new closeable stream which, if a further transformation fails with an exception, will emit a recovery event instead.
    * **Note**: This recovery guard must be placed **before** the risky transformation, not after, as e.g. in the case of [[Try#recover]].
    *
    * @param rec A function transforming an exception into a recovery event
    * @return A new closeable stream of the same event type and the recovery guard
    */
  def recover[E](source: CloseableStream[E], rec: Throwable => Option[E]): CloseableStream[E] =
    new RecoverStream[E](source, rec) with Closeability(source)

  /**
    * Creates a new closeable stream which, if a further transformation fails with an exception, behaves as if no event was emited.
    * **Note**: This recovery guard must be placed **before** the risky transformation, not after, as e.g. in the case of [[Try#recover]].
    *
    * @return A new closeable stream of the same event type and the recovery guard
    */
  inline def ignoreExceptions[E](source: CloseableStream[E]): CloseableStream[E] = recover(source, _ => None)

  /**
    * Creates a new closeable stream which, if a further transformation fails with an exception, behaves as if no event was emited,
    * but also allows for a side-effect, e.g. logging that the exception was caught.
    * **Note**: This recovery guard must be placed **before** the risky transformation, not after, as e.g. in the case of [[Try#recover]].
    *
    * @param rec A function that is triggered when the exception is caught
    * @return A new closeable stream of the same event type and the recovery guard
    */
  inline def ignoreExceptions[E](source: CloseableStream[E], rec: Throwable => Unit): CloseableStream[E] =
    recover(source, t => {rec(t); None})

  /**
    * A utility method that works like [[Transformers#recover]] where every exception is replaced with an event of default value.
    * **Note**: This recovery guard must be placed **before** the risky transformation, not after, as e.g. in the case of [[Try#recover]].
    *
    * @param value The value emited if an exception is caught
    * @return A new closeable stream of the same event type and the recovery guard
    */
  inline def withDefault[E](source: CloseableStream[E], value: E): CloseableStream[E] = recover(source, _ => Some(value))

  /**
    * Creates a new closeable signal which, if a further transformation fails with an exception, will set to a recovery value instead.
    * **Note**: This recovery guard must be placed **before** the risky transformation, not after, as e.g. in the case of [[Try#recover]].
    *
    * @param rec A function transforming an exception into a recovery value
    * @return A new closeable signal of the same value type and the recovery guard
    */
  def recover[V](source: CloseableSignal[V], rec: Throwable => Option[V]): CloseableSignal[V] =
    new RecoverSignal[V](source, rec) with Closeability(source)

  /**
    * Creates a new closeable signal which, if a further transformation fails with an exception, behaves as if nothing happened.
    * Note: This recovery guard must be placed **before** the risky transformation, not after, as e.g. in the case of [[Try#recover]].
    *
    * @return A new closeable signal of the same value type and the recovery guard
    */
  inline def ignoreExceptions[V](source: CloseableSignal[V]): CloseableSignal[V] = recover(source, _ => None)

  /**
    * Creates a new closeable signal which, if a further transformation fails with an exception, does not update its value,
    * but also allows for a side-effect, e.g. logging that the exception was caught.
    * **Note**: This recovery guard must be placed **before** the risky transformation, not after, as e.g. in the case of [[Try#recover]].
    *
    * @param rec A function that is triggered when the exception is caught
    * @return A new closeable signal of the same event type and the recovery guard
    */
  inline def ignoreExceptions[V](source: CloseableSignal[V], rec: Throwable => Unit): CloseableSignal[V] =
    recover(source, t => {rec(t); None})

  /**
    * Creates a new closeable stream which, if a further transformation fails with an exception that is handled by a provided partial function,
    * will emit a recovery event instead. **Note**: This recovery guard must be placed **before** the risky transformation, not after,
    * as e.g. in the case of [[Try#recoverWith]].
    *
    * @param rec A partial function transforming an exception into a recovery event
    * @return A new closeable stream of the same event type and the recovery guard
    */
  def recoverWith[E](source: CloseableStream[E], rec: PartialFunction[Throwable, Option[E]]): CloseableStream[E] =
    new RecoverWithStream[E](source, rec) with Closeability(source)

  /**
    * Creates a new closeable stream which, if a further transformation fails with an exception that is handled by a provided partial function,
    * will ignore the exception and allow for a side-effect to take place. **Note**: This recovery guard must be placed **before**
    * the risky transformation, not after, as e.g. in the case of [[Try#recoverWith]].
    *
    * @param rec A partial function transforming an exception into a side-effect
    * @return A new closeable stream of the same event type and the recovery guard
    */
  inline def ignoreExceptionsWith[E](source: CloseableStream[E], rec: PartialFunction[Throwable, Unit]): CloseableStream[E] =
    recoverWith(source, rec.andThen(_ => None))

  /**
    * Creates a new closeable signal which, if a further transformation fails with an exception that is handled by a provided partial function,
    * will use a recovery value instead. **Note**: This recovery guard must be placed **before** the risky transformation, not after,
    * as e.g. in the case of [[Try#recoverWith]].
    *
    * @param rec A partial function transforming an exception into a recovery value
    * @return A new closeable signal of the same value type and the recovery guard
    */
  def recoverWith[V](source: CloseableSignal[V], rec: PartialFunction[Throwable, Option[V]]): CloseableSignal[V] =
    new RecoverWithSignal[V](source, rec) with Closeability(source)

  /**
    * Creates a new closeable signal which, if a further transformation fails with an exception that is handled by a provided partial function,
    * will ignore the exception and allow for a side-effect to take place. **Note**: This recovery guard must be placed **before**
    * the risky transformation, not after, as e.g. in the case of [[Try#recoverWith]].
    *
    * @param rec A partial function transforming an exception into a side-effect
    * @return A new closeable signal of the same value type and the recovery guard
    */
  def ignoreExceptionsWith[V](source: CloseableSignal[V], rec: PartialFunction[Throwable, Unit]): CloseableSignal[V] =
    recoverWith(source, rec.andThen(_ => None))

  /** Creates a new closeable stream of events of type `V` where each event is a result of applying a function which combines
    * the previous result of type `V` with the original event of type `E` that triggers the emission of the new one.
    *
    * @param zero The initial value of type `V` used to produce the first new event when the first original event comes in.
    * @param f    The function which combines the previous result of type `V` with a new original event of type `E` to produce a new result of type `V`.
    * @tparam V The type of the resulting event.
    * @return A new closeable stream of type `V`.
    */
  def scan[E, V](source: CloseableStream[E], zero: V)(f: (V, E) => V): CloseableStream[V] =
    new ScanStream[E, V](source, zero, f) with Closeability(source)

  /** Creates a new closeable signal with the value type `Z` where the change in the value is the result of applying a function
    * which combines the previous value of type `Z` with the changed value of the type `V` of the parent signal.
    *
    * @todo Test if it really works like that, the code is a bit complicated.
    * @param zero The initial value of the new signal.
    * @param f    The function which combines the current value of the new signal with the new, changed value of the parent (this) signal
    *             to produce a new value for the new signal (might be the same as the old one and then subscribers won't be notified).
    * @tparam Z The value type of the new signal.
    * @return A new closeable signal with the value of the type `Z`.
    */
  def scan[V, Z](source: CloseableSignal[V], zero: Z)(f: (Z, V) => Z): CloseableSignal[Z] =
    new ScanSignal[V, Z](source, zero, f) with Closeability(source)

  /**
    * Groups events in sequences of even size and emits each sequence as one event.
    *
    * @param n The size of the sequence
    * @return A new closeable stream where the event type is a sequence of original events
    */
  def grouped[E](source: CloseableStream[E], n: Int): CloseableStream[Seq[E]] =
    new GroupedStream[E](source, n) with Closeability(source)

  /**
    * Groups values in sequences of even size and uses each sequence as one value.
    *
    * @param n The size of the sequence
    * @return A new closeable signal where the value type is a sequence of original values
    */
  def grouped[V](source: CloseableSignal[V], n: Int): CloseableSignal[Seq[V]] =
    new GroupedSignal[V](source, n) with Closeability(source)

  /**
    * Groups events in sequences of uneven size and emits each sequence as one event.
    * The size of each sequence is decided by a condition. If the new event fulfills the condition,
    * all events already stored in a buffer are released as one sequence, and the new event becomes
    * the first element of a new sequence (it's not released yet). Otherwise, the event is added
    * to the buffer and nothing is released.
    *
    * @param p A condition function
    * @return A new closeable stream where the event type is a sequence of original events
    */
  def groupBy[E](source: CloseableStream[E], p: E => Boolean): CloseableStream[Seq[E]] =
    new GroupByStream[E](source, p) with Closeability(source)

  /**
    * Groups values in sequences of uneven size and uses each sequence as one value.
    * The size of each sequence is decided by a condition. If the new value fulfills the condition,
    * all values already stored in a buffer are used as one new value (a sequence), and the new value becomes
    * the first element of a new sequence (it's not released yet). Otherwise, the value is added
    * to the buffer and the result value is not changed.
    *
    * @param p A condition function
    * @return A new closeable signal where the value type is a sequence of original values
    */
  def groupBy[V](source: CloseableSignal[V], p: V => Boolean): CloseableSignal[Seq[V]] =
    new GroupBySignal[V](source, p) with Closeability(source)

  /**
    * Drops a given number of new emited events from the original closeable stream before starting to emit the consecutive ones.
    *
    * @param n The number of events to drop
    * @return A new closeable stream that drops n events and then starts to emit all consecutive events
    */
  def drop[E](source: CloseableStream[E], n: Int): CloseableStream[E] =
    if (n <= 0) source
    else new DropStream[E](source, n) with Closeability(source)

  /**
    * Ignores a given number of new values from the original closeable signal before starting to update to the consecutive ones.
    *
    * @param n The number of values to drop
    * @return A new closeable signal that drops n values and then starts to use all consecutive values
    */
  def drop[V](source: CloseableSignal[V], n: Int): CloseableSignal[V] =
    if (n <= 0) source
    else new DropSignal[V](source, n) with Closeability(source)

  /**
    * Drops events while they fulfill the condition `p`. The first event that fails is emited and the all consecutive
    * events as well, also those  that would fulfill the condition.
    *
    * @param p The condition function
    * @return A new closeable stream that drops events while `p` is fulfilled
    */
  def dropWhile[E](source: CloseableStream[E], p: E => Boolean): CloseableStream[E] =
    new DropWhileStream[E](source, p) with Closeability(source)

  /**
    * Ignores new values while they fulfill the condition `p`. The first event that fails is emited and the all consecutive
    * events as well, also those  that would fulfill the condition.
    *
    * @param p The condition function
    * @return A new closeable signal that drops events while `p` is fulfilled
    */
  def dropWhile[V](source: CloseableSignal[V], p: V => Boolean): CloseableSignal[V] =
    new DropWhileSignal[V](source, p) with Closeability(source)

  // no .take and no .takeWhile because Closeable does not mix with Finite - just use standard take/takeWhile methods
}
