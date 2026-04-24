package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Stream.EventSubscriber
import scala.concurrent.ExecutionContext
import scala.util.chaining.scalaUtilChainingOps

protected[signals3] final class FlatMapStream[E, V](source: Stream[E], f: E => Stream[V])
  extends Stream[V](source.fallbackStrategy) with EventSubscriber[E]{
  @volatile private var mapped: Option[Stream[V]] = None

  private val subscriber = new EventSubscriber[V]{
    override protected[signals3] def onEvent(event: V, currentContext: Option[ExecutionContext]): Unit =
      dispatch(event, currentContext)
  }

  override protected[signals3] def onEvent(event: E, currentContext: Option[ExecutionContext]): Unit = evalAndRun(f(event)) { e =>
    mapped.foreach(_.unsubscribe(subscriber))
    mapped = Some(e.tap(_.subscribe(subscriber)))
  }

  override protected def onWire(): Unit = source.subscribe(this)

  override protected def onUnwire(): Unit = {
    mapped.foreach(_.unsubscribe(subscriber))
    mapped = None
    source.unsubscribe(this)
  }
}
