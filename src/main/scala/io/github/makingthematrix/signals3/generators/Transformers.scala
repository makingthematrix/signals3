package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.ProxyStream.*
import io.github.makingthematrix.signals3.ProxySignal.*
import io.github.makingthematrix.signals3.{Closeable, CloseableFuture, ProxySignal, ProxyStream, Signal, Stream, Threading}
import io.github.makingthematrix.signals3.Closeable.{CloseableSignal, CloseableStream}

import scala.concurrent.{ExecutionContext, Future}

/**
  * An object which provides transformations for creating complex [[GeneratorStream]]s and [[GeneratorSignal]]s.
  *
  * `GeneratorStream` and `GeneratorSignal` inherit their own set of transformation methods from [[Stream]] and [[Signal]],
  * respectively, but results of those transformations are just streams and signals. Which is fine, in many cases.
  * The proposed way to use a generator is to keep a reference to it, as well as to the results of its transformations.
  * The resulting streams and signals can be used throughout the program, while the reference to the original generator
  * can be used to stop it eventually, when it's no longer needed.
  *
  * But it may happen that you want to create a more complex generator in the first place, and this is where
  * the methods from `generators.Transformers` can be of help. They work in a similar way to standard transformation
  * methods, but they return a [[Closeable]] stream or signal - closing it will close also the original generator,
  * which you pass to the transformer as the first argument.
  *
  * @see For an example how `Transformers` can be used, see `GeneratorSignal.unfold`.
  */
object Transformers:
  /**
    * Creates a new `CloseableStream[V]` by mapping events of the type `E` emitted by the original generator or
    * another closeable stream.
    *
    * @param stream The original closeable stream.
    * @param f      The function mapping each event of type `E` into exactly one event of type `V`.
    * @tparam E     The type of the original event.
    * @tparam V     The type of the resulting event.
    * @return       A new closeable stream of type `V`.
    */
  def map[E, V](stream: CloseableStream[E])(f: E => V): CloseableStream[V] =
    new MapStream[E, V](stream, f) with Closeable:
      override def closeAndCheck(): Boolean = stream.closeAndCheck()
      override def isClosed: Boolean = stream.isClosed

  /**
    * Creates a new `CloseableSignal[Z]` bby mapping the value of the type V of the original generator or
    * another closeable stream.
    *
    * @param signal The original closeable signal.
    * @param f      The function mapping the original value of type `V` into a new value of the type `Z`.
    * @tparam V     The type of the original value.
    * @tparam Z     The type of the resulting value.
    * @return       A new closeable signal of type `Z`.
    */
  def map[V, Z](signal: CloseableSignal[V])(f: V => Z): CloseableSignal[Z] =
    new MapSignal[V, Z](signal, f) with Closeable:
      override def closeAndCheck(): Boolean = signal.closeAndCheck()
      override def isClosed: Boolean = signal.isClosed

  /**
    * Creates a new `CloseableStream[V]` by mapping events of the type `E` emitted by the original generator or
    * another closeable stream.
    *
    * @param stream The original closeable stream.
    * @param f      A function which for a given event of the type `E` will return a future of the type `V`.
    *               If the future finishes with success, the resulting event of the type `V` will be emitted by
    *               the new stream. Two events, coming one after another, are guaranteed to be mapped in the same order
    *               even if the processing for the second event finishes before the processing for the first one.
    * @tparam E     The type of the original event.
    * @tparam V     The type of the resulting event.
    * @return       A new closeable stream of type `V`.
    */
  def mapSync[E, V](stream: CloseableStream[E])(f: E => Future[V]): CloseableStream[V] =
    new FutureStream[E, V](stream, f) with Closeable:
      override def closeAndCheck(): Boolean = stream.closeAndCheck()
      override def isClosed: Boolean = stream.isClosed

  /**
    * Creates a new `CloaseableStream[E]` by filtering events emitted by the original one.
    *
    * @param stream    The original closeable stream.
    * @param predicate A filtering function which for each event emitted by the original stream returns true or false.
    *                  Only events for which `predicate(event)` returns true will be emitted in the resulting stream.
    * @tparam E        The type of the emitted event.
    * @return          A new closeable stream emitting only filtered events.
    */
  def filter[E](stream: CloseableStream[E])(predicate: E => Boolean): CloseableStream[E] =
    new FilterStream[E](stream, predicate) with Closeable:
      override def closeAndCheck(): Boolean = stream.closeAndCheck()
      override def isClosed: Boolean = stream.isClosed

  /**
    * Creates a new `CloseableSignal[V]` which updates its value only if the new value of the original closeable signal
    * satisfies the filter, and changes to empty otherwise. Also, if the initial value of the original signal does not satisfy the filter,
    * the new signal will start empty.
    *
    * @param signal    The original closeable signal.
    * @param predicate A filtering function which for any value of the original signal returns true or false.
    * @tparam V        The type of the value.
    * @return          A new closeable signal of the same value type.
    */
  def filter[V](signal: CloseableSignal[V])(predicate: V => Boolean): CloseableSignal[V] =
    new FilterSignal[V](signal, predicate) with Closeable:
      override def closeAndCheck(): Boolean = signal.closeAndCheck()
      override def isClosed: Boolean = signal.isClosed

  /**
    * Creates a new closeable stream of events of type `V` by applying a partial function which maps the original event
    * of type `E` to an event of type `V`. If the partial function doesn't work for the emitted event, nothing will be
    * emitted in the new stream. Basically, it's filter + map.
    *
    * @param stream The original closeable stream.
    * @param pf     A partial function which for an original event of type `E` may produce an event of type `V`.
    * @tparam E     The type of the original event.
    * @tparam V     The type of the resulting event.
    * @return       A new closeable stream of type `V`.
    */
  def collect[E, V](stream: CloseableStream[E])(pf: PartialFunction[E, V]): CloseableStream[V] =
    new CollectStream[E, V](stream, pf) with Closeable:
      override def closeAndCheck(): Boolean = stream.closeAndCheck()
      override def isClosed: Boolean = stream.isClosed

  /**
    * Creates a new closeable signal of values of the type `Z` by applying a partial function which maps the original
    * value of the type `V` to a value of the type `Z`. If the partial function doesn't work for the current value,
    * the new signal will become empty until the next update. Basically, it's filter + map.
    *
    * @param signal The original closeable signal.
    * @param pf     A partial function which for the original value of the type `V` may produce a value of the type `Z`.
    * @tparam V     The type of the original value.
    * @tparam Z     The type of the resulting value.
    * @return       A new closeable signal of type `Z`.
    */
  def collect[V, Z](signal: CloseableSignal[V])(pf: PartialFunction[V, Z]): CloseableSignal[Z] =
    new CollectSignal[V, Z](signal, pf) with Closeable:
      override def closeAndCheck(): Boolean = signal.closeAndCheck()
      override def isClosed: Boolean = signal.isClosed

  /**
    * Creates a new closeable stream by merging the original closeable streams of the same type.
    * The resulting stream will emit events coming from all sources. Closing the resulting stream will close all
    * the original ones. The `.isClosed` method will return `true` only if all original streams confirm that they
    * are closed.
    *
    * @note Note that this is different from `.zip` methods for [[CloseableSignal]] and from `.sequence`. This version
    *       of `.zip` emits single events of type `E` every time one of the original streams emits them.
    *
    * @param streams An argument list of closeable streams of the same type.
    * @tparam E      The type of the event.
    * @return        A new closeable stream, emitting events from all original streams.
    */
  def zip[E](streams: CloseableStream[E]*): CloseableStream[E] =
    new ZipStream[E](streams: _*) with Closeable:
      override def closeAndCheck(): Boolean = streams.map(_.closeAndCheck()).forall(p => p)
      override def isClosed: Boolean = streams.forall(_.isClosed)

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
    *
    * @param a  The first of the parent closeable signals.
    * @param b  The second of the parent closeable signals.
    * @tparam A The type of the value of the first of parent closeable signals.
    * @tparam B The type of the value of the second of parent closeable signals.
    * @return   A new signal its the value constructed as a tuple of values form the parent closeable signals.
    */
  def zip[A, B](a: CloseableSignal[A], b: CloseableSignal[B]): CloseableSignal[(A, B)] =
    new Zip2Signal[A, B](a, b) with Closeable:
      private[this] lazy val seq = Seq(a, b)
      override def closeAndCheck(): Boolean = seq.map(_.closeAndCheck()).forall(p => p)
      override def isClosed: Boolean = seq.forall(_.isClosed)

  /** A version of the `.zip` method joining three closeable signals of different value types. */
  def zip[A, B, C](a: CloseableSignal[A], b: CloseableSignal[B], c: CloseableSignal[C]): CloseableSignal[(A, B, C)] =
    new Zip3Signal[A, B, C](a, b, c) with Closeable:
      private[this] lazy val seq = Seq(a, b, c)
      override def closeAndCheck(): Boolean = seq.map(_.closeAndCheck()).forall(p => p)
      override def isClosed: Boolean = seq.forall(_.isClosed)

  /** A version of the `.zip` method joining four closeable signals of different value types. */
  def zip[A, B, C, D](a: CloseableSignal[A],
                      b: CloseableSignal[B],
                      c: CloseableSignal[C],
                      d: CloseableSignal[D]): CloseableSignal[(A, B, C, D)] =
    new Zip4Signal[A, B, C, D](a, b, c, d) with Closeable:
      private[this] lazy val seq = Seq(a, b, c, d)
      override def closeAndCheck(): Boolean = seq.map(_.closeAndCheck()).forall(p => p)
      override def isClosed: Boolean = seq.forall(_.isClosed)

  /** A version of the `.zip` method joining five closeable signals of different value types. */
  def zip[A, B, C, D, E](a: CloseableSignal[A],
                         b: CloseableSignal[B],
                         c: CloseableSignal[C],
                         d: CloseableSignal[D],
                         e: CloseableSignal[E]): CloseableSignal[(A, B, C, D, E)] =
    new Zip5Signal[A, B, C, D, E](a, b, c, d, e) with Closeable:
      private[this] lazy val seq = Seq(a, b, c, d, e)
      override def closeAndCheck(): Boolean = seq.map(_.closeAndCheck()).forall(p => p)
      override def isClosed: Boolean = seq.forall(_.isClosed)

  /** A version of the `.zip` method joining six closeable signals of different value types. */
  def zip[A, B, C, D, E, F](a: CloseableSignal[A],
                            b: CloseableSignal[B],
                            c: CloseableSignal[C],
                            d: CloseableSignal[D],
                            e: CloseableSignal[E],
                            f: CloseableSignal[F]): CloseableSignal[(A, B, C, D, E, F)] =
    new Zip6Signal[A, B, C, D, E, F](a, b, c, d, e, f) with Closeable:
      private[this] lazy val seq = Seq(a, b, c, d, e, f)
      override def closeAndCheck(): Boolean = seq.map(_.closeAndCheck()).forall(p => p)
      override def isClosed: Boolean = seq.forall(_.isClosed)

  /**
    * Creates a closeable signal of an arbitrary number of parent signals of the same value.
    * The value of the new signal is the sequence of values of all parent signals in the same order.
    * You can actually think of it as an analogous method to `Stream.zip`. Closing the resulting signal will close all
    * the original ones. The `.isClosed` method will return `true` only if all original signals confirm that they
    * are closed.
    *
    * @see `.zip` method for [[CloseableStream]]
    *
    * @param signals A variable arguments list of parent closeable signals of the same type.
    * @tparam V      The type of the values in the parent closeable signals.
    * @return        A new closeable signal with its value being a sequence of current values of the parent signals.
    */
  def sequence[V](signals: CloseableSignal[V]*): CloseableSignal[Seq[V]] =
    new SequenceSignal[V](signals: _*) with Closeable:
      override def closeAndCheck(): Boolean = signals.map(_.closeAndCheck()).forall(p => p)
      override def isClosed: Boolean = signals.forall(_.isClosed)

  /**
    * Combines the current values of this and another closeable signal of the same or different types `V` and `Z`
    * to produce a closeable signal with the value of yet another type `Y`. Basically, zip + map.
    *
    * @param vSignal The first closeable signal, with values of the type `V`.
    * @param zSignal The second closeable signal with values of the type `Z`.
    * @param f       The function which combines the current values of both parent signals to produce the value of
    *                the new closeable signal with values of the type `Y`.
    * @tparam V      The value type of the first closeable signal.
    * @tparam Z      The value type of the second closeable signal.
    * @tparam Y      The value type of the new closeable signal.
    * @return        A new closeable signal with the values of the type `Y`.
    */
  def combine[V, Z, Y](vSignal: CloseableSignal[V], zSignal: CloseableSignal[Z])(f: (V, Z) => Y): CloseableSignal[Y] =
    new CombineSignal[V, Z, Y](vSignal, zSignal, f) with Closeable:
      private[this] lazy val seq = Seq(vSignal, zSignal)
      override def closeAndCheck(): Boolean = seq.map(_.closeAndCheck()).forall(p => p)
      override def isClosed: Boolean = seq.forall(_.isClosed)

  /**
    * Creates a new closeable stream from a closeable future.
    *
    * @param cFuture A closeable future that will eventually produce an event of the type `E`.
    * @param ec      The execution context in which the closeable future works. Optional.
    *                By default it's `Threading.defaultContext`.
    * @tparam E      The type of the event produced by the closeable future.
    * @return        A new closeable stream which will emit at most only one event of the type `E`.
    */
  def streamFromFuture[E](cFuture: CloseableFuture[E])
                         (using ec: ExecutionContext = Threading.defaultContext): CloseableStream[E] =
    val stream = new Stream[E]() with Closeable:
      override def closeAndCheck(): Boolean = cFuture.closeAndCheck()
      override def isClosed: Boolean = cFuture.isClosed
    cFuture.foreach { stream.dispatch(_, Some(ec)) }
    stream

  /**
    * Creates a new closeable signal from a closeable future.
    *
    * @param cFuture A closeable future that will eventually produce a value of the type `V`.
    * @param ec      The execution context in which the closeable future works. Optional.
    *                By default it's `Threading.defaultContext`.
    * @tparam V      The type of the value produced by the closeable future.
    * @return        A new closeable signal which starts empty and will update at most only once.
    */
  def signalFromFuture[V](cFuture: CloseableFuture[V])
                         (using ec: ExecutionContext = Threading.defaultContext): CloseableSignal[V] =
    val signal = new Signal[V]() with Closeable:
      override def closeAndCheck(): Boolean = cFuture.closeAndCheck()
      override def isClosed: Boolean = cFuture.isClosed
    cFuture.foreach { res => signal.set(Option(res), Some(ec)) }
    signal

  /** Creates a new closeable signal from a closeable stream and an initial value.
    * The signal will be initialized to the initial value on its creation, and subscribe to the stream.
    * Subsequently, it will update the value as new events are published in the parent stream.
    *
    * @param initial The initial value of the signal.
    * @param source  The parent stream.
    * @tparam V      The type of both the initial value and the events in the parent stream.
    * @return        A new signal with the value of the type `V`.
    */
  inline def signalFromStream[V](initial: V, source: CloseableStream[V]): CloseableSignal[V] =
    new StreamSignal[V](source, Option(initial)) with Closeable:
      override def closeAndCheck(): Boolean = source.closeAndCheck()
      override def isClosed: Boolean = source.isClosed

  /** Creates a new closeable signal from a closeable stream.
    * The signal will start uninitialized and subscribe to the parent stream. Subsequently, it will update its value
    * as new events are published in the parent stream.
    *
    * @param source The parent stream.
    * @tparam V     The type of the events in the parent stream.
    * @return       A new signal with the value of the type `V`.
    */
  inline def signalFromStream[V](source: CloseableStream[V]): CloseableSignal[V] =
    new StreamSignal[V](source, None) with Closeable:
      override def closeAndCheck(): Boolean = source.closeAndCheck()
      override def isClosed: Boolean = source.isClosed
