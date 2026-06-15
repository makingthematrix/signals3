package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.{Serialized, Stream, Threading}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure}

/**
  * TODO: FutureStream handles exceptions differently than other streams. I need to decide what to do with it re FallbackStrategy.
  *
  * @param source The stream from which events are taken.
  * @param f      A function which takes an event from the source stream and returns a [[Future]] with its result.
  * @tparam E The type of the events emitted by the stream constructed from the sources.
  * @tparam V The type of the result of the future returned by the function.
  */
private[signals3] class FutureStream[E, V](source: Stream[E], f: E => Future[V]) extends ProxyStream[E, V](source) {
  private val key = java.util.UUID.randomUUID()

  override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
    Serialized.future(key.toString)(f(event)).andThen {
      case Success(v) => dispatch(v, sourceContext)
      case Failure(_: NoSuchElementException) => // do nothing to allow Future.filter/collect
      case Failure(_) =>
    }(using sourceContext.getOrElse(Threading.defaultContext))

}
