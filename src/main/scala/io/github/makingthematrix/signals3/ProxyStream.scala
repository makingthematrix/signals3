package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Stream.EventSubscriber

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** A superclass for all event streams which compose other event streams into one.
  *
  * @param sources A variable arguments list of event streams serving as sources of events for the resulting stream.
  * @tparam A The type of the events emitted by all the source streams.
  * @tparam E The type of the events emitted by the stream constructed from the sources.
  */
abstract private[signals3] class ProxyStream[A, E](sources: Stream[A]*) extends Stream[E] with EventSubscriber[A] {
  /** When the first subscriber is registered in this stream, subscribe the stream to all its sources. */
  override protected[signals3] def onWire(): Unit = sources.foreach(_.subscribe(this))

  /** When the last subscriber is unregistered from this stream, unsubscribe the stream from all its sources. */
  override protected[signals3] def onUnwire(): Unit = sources.foreach(_.unsubscribe(this))
}

private[signals3] object ProxyStream {
  final class RecoverStream[E](source: Stream[E], recover: Throwable => Option[E])
    extends ProxyStream[E, E] {
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      try dispatch(event, sourceContext) catch {
        case t: Throwable => recover(t).foreach(dispatch(_, sourceContext))
      }
  }

  final class RecoverWithStream[E](source: Stream[E], recoverWith: PartialFunction[Throwable, Option[E]])
    extends ProxyStream[E, E] {
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      try dispatch(event, sourceContext) catch {
        case t: Throwable if recoverWith.isDefinedAt(t) =>
          recoverWith(t).foreach(dispatch(_, sourceContext))
      }
  }

  class MapStream[E, V](source: Stream[E], f: E => V) extends ProxyStream[E, V](source) {
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      dispatch(f(event), sourceContext)
  }

  /**
    * TODO: FutureStream handles exceptions differently than other streams. I need to decide what to do with it re FallbackStrategy.
    * @param source The stream from which events are taken.
    * @param f A function which takes an event from the source stream and returns a [[Future]] with its result.
    * @tparam E The type of the events emitted by the stream constructed from the sources.
    * @tparam V The type of the result of the future returned by the function.
    */
  class FutureStream[E, V](source: Stream[E], f: E => Future[V]) extends ProxyStream[E, V](source) {
    private val key = java.util.UUID.randomUUID()
  
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      Serialized.future(key.toString)(f(event)).andThen {
        case Success(v) => dispatch(v, sourceContext)
        case Failure(_: NoSuchElementException) => // do nothing to allow Future.filter/collect
        case Failure(_) =>
      }(using sourceContext.getOrElse(Threading.defaultContext))

  }
  
  class CollectStream[E, V](source: Stream[E], pf: PartialFunction[E, V]) extends ProxyStream[E, V](source) {
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      if (pf.isDefinedAt(event)) dispatch(pf(event), sourceContext)
  }
  
  class FilterStream[E](source: Stream[E], predicate: E => Boolean) extends ProxyStream[E, E](source) {
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit = {
      if (predicate(event)) dispatch(event, sourceContext)
    }
  }
  
  class JoinStream[E](sources: Stream[E]*) extends ProxyStream[E, E](sources*) {
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      dispatch(event, sourceContext)
  }
  
  final class ScanStream[E, V](source: Stream[E], zero: V, f: (V, E) => V) extends ProxyStream[E, V](source) {
    @volatile private var value = zero
  
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit = {
      value = f(value, event)
      dispatch(value, sourceContext)
    }
  }

  final class GroupedStream[E](source: Stream[E], n: Int) extends ProxyStream[E, Seq[E]](source) {
    require(n > 0, "n must be positive")
    private val buffer = ArrayBuffer.empty[E]

    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit = {
      buffer.addOne(event)
      if (buffer.size == n) {
        val res = buffer.toSeq
        buffer.clear()
        dispatch(res, sourceContext)
      }
    }
  }

  final class GroupByStream[E](source: Stream[E], groupBy: E => Boolean) extends ProxyStream[E, Seq[E]](source) {
    private val buffer = ArrayBuffer.empty[E]

    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit = {
      if (groupBy(event)) {
        val res = buffer.toSeq
        buffer.clear()
        if (res.nonEmpty) dispatch(res, sourceContext)
      }
      buffer.addOne(event)
    }
  }

  class IndexedStream[E](source: Stream[E]) extends ProxyStream[E, E](source) with Indexed {
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit = {
      inc()
      dispatch(event, sourceContext)
    }
  }

  final class DropStream[E](source: Stream[E], drop: Int) extends IndexedStream[E](source) {
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      if (counter < drop) inc() else dispatch(event, sourceContext)
  }

  final class DropWhileStream[E](source: Stream[E], p: E => Boolean) extends ProxyStream[E, E](source) {
    @volatile private var dropping = true
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit = {
      if (dropping) dropping = p(event)
      if (!dropping) dispatch(event, sourceContext)
    }
  }

  final class CloseableStream[E](source: Stream[E])
    extends ProxyStream[E, E](source) with Closeable {
    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      if (!isClosed) dispatch(event, sourceContext)
  }

  final class TakeWhileStream[E](source: Stream[E], p: E => Boolean)
    extends ProxyStream[E, E](source) with Finite[E]{

    private var previousEvent: Option[E] = None

    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit = if (!isClosed) {
      if (p(event)) {
        dispatch(event, sourceContext)
        previousEvent = Some(event)
      } else {
        close()
        (lastPromise, previousEvent) match {
          case (Some(p), Some(pe)) if !p.isCompleted => p.trySuccess(pe)
          case _ =>
        }
      }
    }
  }
}
