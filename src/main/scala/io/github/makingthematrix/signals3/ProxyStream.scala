package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Stream.EventSubscriber

import scala.concurrent.{ExecutionContext, Future, Promise}
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

  class IndexedStream[E](source: Stream[E]) extends ProxyStream[E, E](source) with Indexed:
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      inc()
      dispatch(event, sourceContext)

  final class DropStream[E](source: Stream[E], drop: Int) extends IndexedStream[E](source):
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      if counter < drop then inc() else dispatch(event, sourceContext)

  final class DropWhileStream[E](source: Stream[E], p: E => Boolean) extends ProxyStream[E, E](source):
    @volatile private var dropping = true
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      if dropping then dropping = p(event)
      if !dropping then dispatch(event, sourceContext)

  final class CloseableStream[E](source: Stream[E]) extends ProxyStream[E, E](source) with Closeable:
    @volatile private var closed = false

    override def closeAndCheck(): Boolean =
      closed = true
      true

    override def isClosed: Boolean = closed

    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      if !closed then dispatch(event, sourceContext)

  protected trait FiniteStream[E] extends Finite[E, Stream[E]]:
    protected var lastPromise: Option[Promise[E]] = None
    override lazy val last: Future[E] = Promise[E]().tap { p => lastPromise = Some(p) }.future

    protected var initStream: Option[SourceStream[E]] = None
    override lazy val init: Stream[E] = SourceStream[E]().tap { s => initStream = Some(s) }

  final class TakeStream[E](source: Stream[E], take: Int) 
    extends IndexedStream[E](source) with FiniteStream[E]:
    override def isClosed: Boolean = super.isClosed || counter >= take

    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit = 
      if !isClosed then
        inc()
        dispatch(event, sourceContext)
        if !isClosed then initStream.foreach { _ ! event }
      if isClosed then lastPromise.foreach {
        case p if !p.isCompleted => p.trySuccess(event)
        case _ =>
      }

  final class TakeWhileStream[E](source: Stream[E], p: E => Boolean)
    extends ProxyStream[E, E](source) with FiniteStream[E]:

    private var previousEvent: Option[E] = None

    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      if !isClosed then
        if !p(event) then
          close()
          (lastPromise, previousEvent) match
            case (Some(p), Some(pe)) if !p.isCompleted => p.trySuccess(pe)
            case _ =>
        else
          dispatch(event, sourceContext)
          (initStream, previousEvent) match
            case (Some(s), Some(pe)) => s ! pe
            case _ =>
          previousEvent = Some(event)
        end if
      end if

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
