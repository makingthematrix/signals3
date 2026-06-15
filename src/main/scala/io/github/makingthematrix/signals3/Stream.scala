package io.github.makingthematrix.signals3

import Stream.EmptyTakeStream
import Finite.FiniteStream
import io.github.makingthematrix.signals3.priv.*
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.ref.WeakReference
import scala.util.chaining.scalaUtilChainingOps

/** A stream of type `E` dispatches events (of type `E`) to all functions of type `(E) => Unit` which were registered in
  * the stream as its subscribers. It doesn't have an internal state. It provides a handful of methods which enable
  * the user to create new event streams by means of composing the old ones, filtering them, etc., in a way similar to how
  * the user can operate on standard collections, as well as to interact with Scala futures, closeable futures, and signals.
  * Please note that by default a stream is not able to receive events from the outside - that functionality belongs
  * to [[SourceStream]].
  *
  * A stream may also help in sending events from one execution context to another. For example, a source stream may
  * receive an event in one execution context, but the function which consumes it is registered with another execution context
  * specified. In that case the function won't be called immediately, but in a future executed in that execution context.
  *
  * @see `ExecutionContext`
  */
class Stream[E] extends EventSource[E, StreamSubscriber[E]] {
  /** Dispatches the event to all subscribers.
    *
    * @param event The event to be dispatched.
    * @param executionContext An option of the execution context used for dispatching. The default implementation
    *                         ensures that if `executionContext` is `None` or the same as the execution context used to register
    *                         the subscriber, the subscriber will be called immediately. Otherwise, a future working in the subscriber's
    *                         execution context will be created.
    */
  private[signals3] def dispatch(event: E, executionContext: Option[ExecutionContext]): Unit =
    notifySubscribers(_.onEvent(event, executionContext))

  /** Publishes the event to all subscribers using the current execution context.
    *
    * @param event The event to be published.
    */
  private[signals3] def publish(event: E): Unit = dispatch(event, None)

  /** Registers a subscriber in a specified execution context and returns the subscription. An optional event context can also
    * be provided by the user for managing the subscription instead of doing it manually. When an event is published in
    * the stream, the subscriber function will be called in the given execution context instead of the one of the publisher.
    *
    * @see [[EventSource]]
    * @param ec An `ExecutionContext` in which the body function will be executed.
    * @param body A function which consumes the event
    * @param eventContext An [[EventContext]] which will register the [[Subscription]] for further management (optional)
    * @return A [[Subscription]] representing the created connection between the stream and the body function
    */
  override def on(ec: ExecutionContext)
                 (body: E => Unit)
                 (using eventContext: EventContext = EventContext.Global): Subscription =
    new StreamSubscription[E](this, body, Some(ec))(using WeakReference(eventContext)).tap(_.enable())

  /** Registers a subscriber which will always be called in the same execution context in which the event was published.
    * An optional event context can be provided by the user for managing the subscription instead of doing it manually.
    *
    * @see [[EventSource]]
    * @param body A function which consumes the event
    * @param eventContext An [[EventContext]] which will register the [[Subscription]] for further management (optional)
    * @return A [[Subscription]] representing the created connection between the stream and the body function
    */
  override def onCurrent(body: E => Unit)
                        (using eventContext: EventContext = EventContext.Global): Subscription =
    new StreamSubscription[E](this, body, None)(using WeakReference(eventContext)).tap(_.enable())

  protected def recoverPriv(f: Throwable => Option[E]): Stream[E] = RecoverStream[E](this, f)

  /**
    * Creates a new stream which, if a further transformation fails with an exception, will emit a recovery event instead.
    * **Note**: This recovery guard must be placed **before** the risky transformation, not after, as e.g. in the case of [[Try#recover]].
    * @param f A function transforming an exception into a recovery event
    * @return A new stream of the same event type and the recovery guard
    */
  def recover(f: Throwable => E): Stream[E] = recoverPriv(t => Some(f(t)))

  /**
    * Creates a new stream which, if a further transformation fails with an exception, behaves as if no event was emited.
    * **Note**: This recovery guard must be placed **before** the risky transformation, not after, as e.g. in the case of [[Try#recover]].
    * @return A new stream of the same event type and the recovery guard
    */
  def ignoreExceptions: Stream[E] = recoverPriv(_ => None)

  /**
    * Creates a new stream which, if a further transformation fails with an exception, behaves as if no event was emited,
    * but also allows for a side-effect, e.g. logging that the exception was caught.
    * **Note**: This recovery guard must be placed **before** the risky transformation, not after, as e.g. in the case of [[Try#recover]].
    *
    * @param f A function that is triggered when the exception is caught
    * @return A new stream of the same event type and the recovery guard
    */
  def ignoreExceptions(f: Throwable => Unit): Stream[E] = recoverPriv(t => { f(t); None })

  /**
    * A utility method that works like [[Stream#recover]] where every exception is replaced with an event of default value.
    * **Note**: This recovery guard must be placed **before** the risky transformation, not after, as e.g. in the case of [[Try#recover]].
    *
    * @param value The value emited if an exception is caught
    * @return A new stream of the same event type and the recovery guard
    */
  def withDefault(value: E): Stream[E] = recover(_ => value)

  protected def recoverWithPriv(pf: PartialFunction[Throwable, Option[E]]): Stream[E] = RecoverWithStream[E](this, pf)

  /**
    * Creates a new stream which, if a further transformation fails with an exception that is handled by a provided partial function,
    * will emit a recovery event instead. **Note**: This recovery guard must be placed **before** the risky transformation, not after,
    * as e.g. in the case of [[Try#recoverWith]].
    * @param pf A partial function transforming an exception into a recovery event
    * @return A new stream of the same event type and the recovery guard
    */
  def recoverWith(pf: PartialFunction[Throwable, E]): Stream[E] = recoverWithPriv(pf.andThen(Some(_)))

  /**
    * Creates a new stream which, if a further transformation fails with an exception that is handled by a provided partial function,
    * will ignore the exception and allow for a side-effect to take place. **Note**: This recovery guard must be placed **before**
    * the risky transformation, not after, as e.g. in the case of [[Try#recoverWith]].
    * @param pf A partial function transforming an exception into a side-effect
    * @return A new stream of the same event type and the recovery guard
    */
  def ignoreExceptionsWith(pf: PartialFunction[Throwable, Unit]): Stream[E] = recoverWithPriv(pf.andThen(_ => None))

  /** Creates a new `Stream[V]` by mapping events of the type `E` emitted by the original stream.
    *
    * @param f The function mapping each event of type `E` into exactly one event of type `V`.
    * @tparam V The type of the resulting event.
    * @return A new stream of type `V`.
    */
  inline final def map[V](f: E => V): Stream[V] = new MapStream[E, V](this, f)

  /** Creates a new `Stream[V]` by mapping each event of the original `Stream[E]` to a new stream and
    * switching to it. The usual use case is that the event from the original stream serves to make a decision which
    * next stream we should be listening to. When another event is emitted by the original stream, we may stay listening
    * to that second one, or change our previous decision.
    *
    * @param f The function mapping each event of type `E` to a stream of type `V`.
    * @tparam V The type of the resulting stream.
    * @return A new or already existing stream to which we switch as the result of receiving the original event.
    */
  inline final def flatMap[V](f: E => Stream[V]): Stream[V] = new FlatMapStream[E, V](this, f)

  /** Flattens a stream whose value type is also a stream.
    *
    * @tparam V The type of the value of the nested stream.
    * @return A new stream of the value type the same as the value type of the nested stream.
    */
  inline final def flatten[V](using E <:< Stream[V]): Stream[V] = flatMap(x => x)

  /** Creates a new `Stream[V]` by mapping events of the type `E` emitted by the original one.
    *
    * @param f  A function which for a given event of the type `E` will return a future of the type `V`. If the future finishes
    *           with success, the resulting event of the type `V` will be emitted by the new stream. Two events, coming one
    *           after another, are guaranteed to be mapped in the same order even if the processing for the second event
    *           finishes before the processing for the first one.
    * @tparam V The type of the resulting event.
    * @return A new stream of type `V`.
    */
  inline final def mapSync[V](f: E => Future[V]): Stream[V] = new FutureStream[E, V](this, f)

  /** Creates a new `Stream[E]` by filtering events emitted by the original one.
    *
    * @param predicate A filtering function which for each event emitted by the original stream returns true or false. 
    *                  Only events for which `predicate(event)` returns true will be emitted in the resulting stream.
    * @return          A new stream emitting only filtered events.
    */
  inline final def filter(predicate: E => Boolean): Stream[E] = new FilterStream[E](this, predicate)

  /** An alias for `filter` used in the for/yield notation.  */
  inline final def withFilter(predicate: E => Boolean): Stream[E] = filter(predicate)

  /** Creates a new stream of events of type `V` by applying a partial function which maps the original event of type `E`
    * to an event of type `V`. If the partial function doesn't work for the emitted event, nothing will be emitted in the
    * new stream. Basically, it's filter + map.
    *
    * @param pf A partial function which for an original event of type `E` may produce an event of type `V`.
    * @tparam V The type of the resulting event.
    * @return A new stream of type `V`.
    */
  inline final def collect[V](pf: PartialFunction[E, V]): Stream[V] = new CollectStream[E, V](this, pf)

  /** Creates a new stream of events of type `V` where each event is a result of applying a function which combines
    * the previous result of type `V` with the original event of type `E` that triggers the emission of the new one.
    *
    * @param zero The initial value of type `V` used to produce the first new event when the first original event comes in.
    * @param f The function which combines the previous result of type `V` with a new original event of type `E` to produce a new result of type `V`.
    * @tparam V The type of the resulting event.
    * @return A new stream of type `V`.
    */
  inline final def scan[V](zero: V)(f: (V, E) => V): Stream[V] = new ScanStream[E, V](this, zero, f)

  /** Creates a new stream by merging the original stream with another one of the same type. The resulting stream
    * will emit events coming from both sources.
    *
    * @param stream The other stream of the same type of events.
    * @return A new stream, emitting events from both original streams.
    */
  inline final def join(stream: Stream[E]): Stream[E] = new JoinStream[E](this, stream)

  /**
    * Creates a new stream that emits all the events of the original stream + one event emited by the provided [[Future]].
    * @param future A future which will result with a new event
    * @return A new stream, emitting events from both the original stream and the future.
    */
  inline final def join(future: Future[E])(using ExecutionContext): Stream[E] = join(Stream.apply(future))

  /** A shorthand for registering a subscriber function in this stream which only purpose is to publish events emitted
    * by this stream in a given [[SourceStream]]. The subscriber function will be called in the execution context of the
    * original publisher.
    *
    * @see [[SourceStream]]
    *
    * @param sourceStream The source stream in which events emitted by this stream will be published.
    * @param ec An [[EventContext]] which can be used to manage the subscription (optional).
    * @return A new [[Subscription]] to this stream.
    */
  inline final def pipeTo(sourceStream: SourceStream[E])(using ec: EventContext = EventContext.Global): Subscription =
    onCurrent(sourceStream ! _)

  /** An alias for `pipeTo`. */
  inline final def |(sourceStream: SourceStream[E])(using ec: EventContext = EventContext.Global): Subscription = 
    pipeTo(sourceStream)

  /**
    * Provides [[Indexed]] functionality to the original stream
    * @return A new indexed stream or the original one if it's already indexed
    */
  final def indexed: IndexedStream[E] = this match {
    case that: IndexedStream[E] => that
    case _ => new IndexedStream[E](this)
  }

  /**
    * Drops a given number of new emited events from the original stream before starting to emit the consecutive ones.
    * @param n The number of events to drop
    * @return A new stream that drops n events and then starts to emit all consecutive events
    */
  final def drop(n: Int): Stream[E] = if (n <= 0) this else new DropStream[E](this, n)

  /**
    * Drops events while they fulfill the condition `p`. The first event that fails is emited and the all consecutive
    * events as well, also those  that would fulfill the condition.
    * @param p The condition function
    * @return A new stream that drops events while `p` is fulfilled
    */
  inline final def dropWhile(p: E => Boolean): Stream[E] = new DropWhileStream[E](this, p)

  /**
    * Emits a given number of events and closes the stream.
    * @param n The number of events to take
    * @return A new stream that takes n events and closes
    */
  final def take(n: Int): TakeStream[E] =
    if (n <= 0) EmptyTakeStream.asInstanceOf[TakeStream[E]] else new TakeStream[E](this, n)

  /**
    * Emits events while they fulfill the condition `p`. The first event that fails closes the stream.
    * @param p The condition function
    * @return A new stream that emits events while `p` is fulfilled
    */
  inline final def takeWhile(p: E => Boolean): FiniteStream[E] = new TakeWhileStream[E](this, p)

  /**
    * Splits the stream into a finite stream that emits the given number of event and closes, and another stream that picks up
    * emiting the events after the first stops.
    * @param n The number of events to split the stream at
    * @return A tuple of streams of the same event type
    */
  inline final def splitAt(n: Int): (FiniteStream[E], Stream[E]) = (take(n), drop(n))

  /**
    * Splits the stream into a finite stream that emits events until thee new event fails to fulfill the given condition, 
    * and another stream that picks up emiting the events after the first stops.
    *
    * @param p The condition function
    * @return A tuple of streams of the same event type
    */
  inline final def splitAt(p: E => Boolean): (FiniteStream[E], Stream[E]) = (takeWhile(p), dropWhile(p))

  /**
    * Creates a closeable wrapper around this stream
    * @return A new closeable stream or this one if it's already closeable
    */
  final def closeable: CloseableStream[E] = this match {
    case that: CloseableStream[E] => that
    case _ => new CloseableStream[E](this)
  }

  /**
    * Groups events in sequences of even size and emits each sequence as one event.
    * @param n The size of the sequence
    * @return A new stream where the event type is a sequence of original events
    */
  inline final def grouped(n: Int): Stream[Seq[E]] = new GroupedStream[E](this, n)

  /**
    * Groups events in sequences of uneven size and emits each sequence as one event.
    * The size of each sequence is decided by a condition. If the new event fulfills the condition,
    * all events already stored in a buffer are released as one sequence, and the new event becomes
    * the first element of a new sequence (it's not released yet). Otherwise, the event is added
    * to the buffer and nothing is released.
    *
    * @param p A condition function
    * @return A new stream where the event type is a sequence of original events
    */
  inline final def groupBy(p: E => Boolean): Stream[Seq[E]] = new GroupByStream[E](this, p)

  /** Produces a [[CloseableFuture]] which will finish when the next event is emitted in the parent stream.
    *
    * @param eventContext Internally, the method creates a subscription to the stream, and an [[EventContext]] can be provided
    *                     to manage it. In practice it's rarely needed. The subscription will be destroyed when the returning
    *                     future is finished or cancelled.
    * @param executionContext The [[ExecutionContext]] in which the new closeable future will run.
    * @return A closeable future which will finish with the next event emitted by the stream.
    */
  final def next(using eventContext: EventContext = EventContext.Global, executionContext: ExecutionContext): CloseableFuture[E] = {
    val p = Promise[E]()
    val o = onCurrent { p.trySuccess }
    p.future.onComplete(_ => o.destroy())
    CloseableFuture.from(p)
  }

  /** A shorthand for `next` which additionally unwraps the closeable future */
  inline final def future(using context: EventContext = EventContext.Global, executionContext: ExecutionContext): Future[E] =
    next.future

  /** An alias to the `future` method. */
  inline final def head(using ExecutionContext): Future[E] = future

  /** An alias for `drop(1)`, i.e. a stream that ignores one event and emits all the rest. */
  inline final def tail: Stream[E] = drop(1)

  /** Assuming that the event emitted by the stream can be interpreted as a boolean, this method creates a new stream
    * of type `Unit` which emits unit events for each original event which is interpreted as true.
    *
    * @return A new stream of units.
    */
  final def ifTrue(using E <:< Boolean): Stream[Unit] = collect { case true => () }

  /** Assuming that the event emitted by the stream can be interpreted as a boolean, this method creates a new stream
    * of type `Unit` which emits unit events for each original event which is interpreted as false.
    *
    * @return A new stream of units.
    */
  final def ifFalse(using E <:< Boolean): Stream[Unit] = collect { case false => () }

  /** Assuming that the event emitted by the stream can be interpreted as a boolean, this method creates a new stream
    * of type `Boolean` where each event is the opposite of the original event.
    *
    * @return A new stream of `Boolean`.
    */
  inline final def not(using E <:< Boolean): Stream[Boolean] = map(!_)

  /** By default, a stream does not have the internal state so there's nothing to do in `onWire` and `onUnwire`*/
  override protected def onWire(): Unit = {}

  /** By default, a stream does not have the internal state so there's nothing to do in `onWire` and `onUnwire`*/
  override protected def onUnwire(): Unit = {}
}

object Stream {
  extension [E](f: Future[E]) {
    /**
      * Creates a finite event stream that emits one event produced by the original future and closes.
      * @return A finite stream of the same event type as the original future
      */
    inline def toStream(using ExecutionContext): Stream[E] = Stream.apply(f)
  }

  extension [E](p: Promise[E]) {
    /**
      * Creates a finite event stream that emits one event produced by the original promise and closes.
      * @return A finite stream of the same event type as the original promise
      */
    inline def toStream(using ExecutionContext): Stream[E] = Stream.apply(p)
  }

  /**
    * Splits the stream into a future which completes when the first event is emited, and a stream that emits
    * all the rest of events from the original stream.
    */
  object `::` {
    def unapply[E](stream: Stream[E])(using ExecutionContext): (Future[E], Stream[E]) = (stream.head, stream.tail)
  }

  private final val EmptyTakeStream: TakeStream[Any] = new TakeStream[Any](Stream[Any](), 0)

  /** Creates a new [[SourceStream]] of events of the type `E`. A usual entry point for the event streams network.
    *
    * @tparam E The event type.
    * @return A new stream of events of the type `E`.
    */
  inline def apply[E]() = new SourceStream[E]

  /** Creates a new stream by joining together the original streams of the same type of events, `E`.
    * The resulting stream will emit all events published to any of the original streams.
    * 
    * @note Note that this is different from `.zip` and `.sequence` methods for [[Signal]]. This version
    * of `.zip` emits single events of type `E` every time one of the original streams emits them.
    *
    * @param streams A variable arguments list of original event streams of the same event type.
    * @tparam E The event type.
    * @return A new stream of events of type `E`.
    */
  inline def join[E](streams: Stream[E]*): Stream[E] = new JoinStream(streams*)

  /** Creates a new event source from a signal of the same type of events.
    * The event source will publish a new event every time the value of the signal changes or its set of subscribers changes.
    *
    * @see [[Signal]]
    * @see [[Signal.onChanged]]
    *
    * @param signal The original signal.
    * @tparam E The type of events.
    * @return A new stream, emitting an event corresponding to the value of the original signal.
    */
  inline def apply[E](signal: Signal[E]): Stream[E] = signal.onChanged

  /** Creates a stream from a future. The stream will emit one event if the future finishes with success, zero otherwise.
    * @param future The [[Future]] treated as the source of the only event that can be emitted by the event source.
    * @tparam E The type of the event.
    * @return A new stream.
    */
  inline def apply[E](future: Future[E])(using ExecutionContext): TakeStream[E] = apply(CloseableFuture.from(future))

  /** Creates a stream from a promise. The stream will emit one event if the promise finishes with success, zero otherwise.
   *
   * @param promise          The [[Promise]] treated as the source of the only event that can be emitted by the event source.
   * @tparam E The type of the event.
   * @return A new stream.
   */
  inline def apply[E](promise: Promise[E])(using ExecutionContext): TakeStream[E] = apply(CloseableFuture.from(promise))

  /** Creates a stream from a closeable future. The stream will emit one event if the future finishes with success,
   * zero otherwise.
   * @param cf The [[CloseableFuture]] treated as the source of the only event that can be emitted by the event source.
   * @tparam E The type of the event.
   * @return A new stream.
   */
  inline def apply[E](cf: CloseableFuture[E])(using ExecutionContext): TakeStream[E] = TakeStream[E](cf)
}
