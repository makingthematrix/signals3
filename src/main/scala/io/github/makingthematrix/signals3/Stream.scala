package io.github.makingthematrix.signals3

import Stream.{EventSubscriber, StreamSubscription}
import ProxyStream.*

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.ref.WeakReference
import scala.util.Try
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
class Stream[E] extends EventSource[E, EventSubscriber[E]]:
  /** Dispatches the event to all subscribers.
    *
    * @param event The event to be dispatched.
    * @param executionContext An option of the execution context used for dispatching. The default implementation
    *                         ensures that if `executionContext` is `None` or the same as the execution context used to register
    *                         the subscriber, the subscriber will be called immediately. Otherwise, a future working in the subscriber's
    *                         execution context will be created.
    */
  protected[signals3] def dispatch(event: E, executionContext: Option[ExecutionContext]): Unit =
    notifySubscribers(_.onEvent(event, executionContext))

  /** Publishes the event to all subscribers using the current execution context.
    *
    * @param event The event to be published.
    */
  protected def publish(event: E): Unit = dispatch(event, None)

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

  /** An alias for `join`. */
  inline final def :::(stream: Stream[E]): Stream[E] = join(stream)
  
  inline final def join(future: Future[E]): Stream[E] = join(Stream.from(future))
  
  inline final def ::(future: Future[E]): Stream[E] = join(future)

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

  inline final def indexed: IndexedStream[E] = this match
    case that: IndexedStream[E] => that
    case _ => new IndexedStream[E](this)

  inline final def drop(n: Int): DropStream[E] = new DropStream[E](this, n)
  inline final def take(n: Int): TakeStream[E] = new TakeStream[E](this, n)

  // TODO: Maybe let's make every stream Closeable?
  inline final def closeable: CloseableStream[E] = this match
    case that: CloseableStream[E] => that
    case _ => new CloseableStream[E](this)
  
  /** Produces a [[CloseableFuture]] which will finish when the next event is emitted in the parent stream.
    *
    * @param context Internally, the method creates a subscription to the stream, and an [[EventContext]] can be provided
    *                to manage it. In practice it's rarely needed. The subscription will be destroyed when the returning
    *                future is finished or cancelled.
    * @return A closeable future which will finish with the next event emitted by the stream.
    */
  final def next(using context: EventContext = EventContext.Global, executionContext: ExecutionContext = Threading.defaultContext): CloseableFuture[E] =
    val p = Promise[E]()
    val o = onCurrent { p.trySuccess }
    p.future.onComplete(_ => o.destroy())
    CloseableFuture.from(p)

  /** A shorthand for `next` which additionally unwraps the closeable future */
  inline final def future(using context: EventContext = EventContext.Global, executionContext: ExecutionContext = Threading.defaultContext): Future[E] =
    next.future

  /** An alias to the `future` method. */
  inline final def head: Future[E] = future
  
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

object Stream:
  private[signals3] trait EventSubscriber[E]:
    // 'currentContext' is the context this method IS run in, NOT the context any subsequent methods SHOULD run in
    protected[signals3] def onEvent(event: E, currentContext: Option[ExecutionContext]): Unit

  final private class StreamSubscription[E](source:            Stream[E],
                                            f:                 E => Unit,
                                            executionContext:  Option[ExecutionContext] = None
                                           )(using context: WeakReference[EventContext])
    extends BaseSubscription(context) with EventSubscriber[E]:

    override def onEvent(event: E, currentContext: Option[ExecutionContext]): Unit =
      if subscribed then
        executionContext match
          case Some(ec) if !currentContext.contains(ec) => Future(if subscribed then Try(f(event)))(using ec)
          case _ => f(event)

    override protected[signals3] def onSubscribe(): Unit = source.subscribe(this)

    override protected[signals3] def onUnsubscribe(): Unit = source.unsubscribe(this)

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
  inline def from[E](signal: Signal[E]): Stream[E] = signal.onChanged

  /** Creates a stream from a future. The stream will emit one event if the future finishes with success, zero otherwise.
    *
    * @param future The `Future` treated as the source of the only event that can be emitted by the event source.
    * @param executionContext The `ExecutionContext` in which the event will be dispatched.
    * @tparam E The type of the event.
    * @return A new stream.
    */
  inline def from[E](future: Future[E], executionContext: ExecutionContext): Stream[E] =
    new Stream[E]().tap { stream =>
      future.foreach { stream.dispatch(_, Some(executionContext)) }(using executionContext)
    }

  /** A shorthand for creating a stream from a future in the default execution context.
    *
    * @see [[Threading]]
    *
    * @param future The `Future` treated as the source of the only event that can be emitted by the event source.
    * @tparam E The type of the event.
    * @return A new stream.
    */
  inline def from[E](future: Future[E]): Stream[E] = from(future, Threading.defaultContext)

  object `::`:
    def unapply[E](stream: Stream[E]): (Future[E], Stream[E]) = (stream.head, stream.tail)