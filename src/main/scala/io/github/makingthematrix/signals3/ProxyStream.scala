package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Stream.EventSubscriber

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.util.chaining.scalaUtilChainingOps


/** A superclass for all event streams which compose other event streams into one.
  *
  * @param sources A variable arguments list of event streams serving as sources of events for the resulting stream.
  * @tparam A The type of the events emitted by all the source streams.
  * @tparam E The type of the events emitted by the stream constructed from the sources.
  */
abstract private[signals3] class ProxyStream[A, E](sources: Stream[A]*)
  extends Stream[E] with EventSubscriber[A]:
  /** When the first subscriber is registered in this stream, subscribe the stream to all its sources. */
  override protected[signals3] def onWire(): Unit = sources.foreach(_.subscribe(this))

  /** When the last subscriber is unregistered from this stream, unsubscribe the stream from all its sources. */
  override protected[signals3] def onUnwire(): Unit = sources.foreach(_.unsubscribe(this))

private[signals3] object ProxyStream:
  class MapStream[E, V](source: Stream[E], f: E => V) extends ProxyStream[E, V](source):
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      dispatch(f(event), sourceContext)
  
  class FutureStream[E, V](source: Stream[E], f: E => Future[V])
    extends ProxyStream[E, V](source):
    private val key = java.util.UUID.randomUUID()
  
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      Serialized.future(key.toString)(f(event)).andThen {
        case Success(v)                         => dispatch(v, sourceContext)
        case Failure(_: NoSuchElementException) => // do nothing to allow Future.filter/collect
        case Failure(_)                         =>
      }(using sourceContext.getOrElse(Threading.defaultContext))
  
  class CollectStream[E, V](source: Stream[E], pf: PartialFunction[E, V])
    extends ProxyStream[E, V](source):
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      if pf.isDefinedAt(event) then dispatch(pf(event), sourceContext)
  
  class FilterStream[E](source: Stream[E], predicate: E => Boolean)
    extends ProxyStream[E, E](source):
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      if predicate(event) then dispatch(event, sourceContext)
  
  class JoinStream[E](sources: Stream[E]*)
    extends ProxyStream[E, E](sources*):
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      dispatch(event, sourceContext)
  
  final class ScanStream[E, V](source: Stream[E], zero: V, f: (V, E) => V)
    extends ProxyStream[E, V](source):
    @volatile private var value = zero
  
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      value = f(value, event)
      dispatch(value, sourceContext)

  class IndexedStream[E](source: Stream[E]) extends ProxyStream[E, E](source) with Indexed[E]:
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      inc()
      dispatch(event, sourceContext)

  final class DropStream[E](source: Stream[E], drop: Int) extends IndexedStream[E](source):
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      inc()
      if counter > drop then dispatch(event, sourceContext)

  final class CloseableStream[E](source: Stream[E]) extends ProxyStream[E, E](source) with Closeable:
    @volatile private var closed = false

    override def closeAndCheck(): Boolean =
      closed = true
      true

    override def isClosed: Boolean = closed

    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      if !closed then dispatch(event, sourceContext)

  final class TakeStream[E](source: Stream[E], take: Int) extends IndexedStream[E](source) with Closeable:
    @volatile private var forceClose = false

    override def closeAndCheck(): Boolean =
      forceClose = true
      true

    override def isClosed: Boolean = forceClose || counter >= take

    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      if !isClosed then
        inc()
        dispatch(event, sourceContext)

  final class FlatMapStream[E, V](source: Stream[E], f: E => Stream[V])
    extends Stream[V] with EventSubscriber[E]:
    @volatile private var mapped: Option[Stream[V]] = None
  
    private val subscriber = new EventSubscriber[V]:
      override protected[signals3] def onEvent(event: V, currentContext: Option[ExecutionContext]): Unit =
        dispatch(event, currentContext)
  
    override protected[signals3] def onEvent(event: E, currentContext: Option[ExecutionContext]): Unit =
      mapped.foreach(_.unsubscribe(subscriber))
      mapped = Some(f(event).tap(_.subscribe(subscriber)))
  
    override protected def onWire(): Unit = source.subscribe(this)
  
    override protected def onUnwire(): Unit =
      mapped.foreach(_.unsubscribe(subscriber))
      mapped = None
      source.unsubscribe(this)
  
