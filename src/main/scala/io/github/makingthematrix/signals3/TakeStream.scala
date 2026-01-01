package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.ProxyStream.IndexedStream

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

/**
 * A stream which closes after a given number of events.
 *
 * `TakeStream` is a "proxy stream" - a special subclass of [[Stream]] with additional logic, usually hidden from the user.
 * It is created by the `take` method on another stream, similar to how other proxy streams are created through `map`, `filter`, etc.
 * But, contrary to other proxy streams, `TakeStream` might be useful on its own, so I decided not to hide it.
 *
 * @param source The stream from which events are taken.
 * @param take The number of events to take.
 * @tparam E The type of the events emitted by the stream.
 */
final class TakeStream[E](source: Stream[E], take: Int)
  extends IndexedStream[E](source) with Finite[E] {
  override def isClosed: Boolean = super.isClosed || counter >= take

  override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit = {
    if (!isClosed) {
      inc()
      dispatch(event, sourceContext)
    }
    if (isClosed) lastPromise.foreach {
      case p if !p.isCompleted => p.trySuccess(event)
      case _ =>
    }
  }

  /**
   * A stream of all events of this stream except the last one. See [[Finite.last]].
   */
  lazy val init: TakeStream[E] = source.take(take - 1)
}

object TakeStream {
  /**
   * Creates a new `TakeStream` of length 1 from the given [[Future]].
   * @param future When the future completes, its result will be published in the stream.
   * @param ec The execution context in which the future is executed.
   * @tparam E The type of the stream's result.
   * @return A new `TakeStream` of length 1.
   */
  inline def apply[E](future: Future[E])(using ec: ExecutionContext): TakeStream[E] =
    apply(CloseableFuture.from(future))

  /**
   * Creates a new `TakeStream` of length 1 from the given [[Promise]].
   * If the promise fails, the stream will be closed immediately.
   *
   * @param promise When the promise completes, its result will be published in the stream.
   * @param ec      The execution context in which the promise is executed.
   * @tparam E      The type of the stream's result.
   * @return        A new `TakeStream` of length 1.
   */
  inline def apply[E](promise: Promise[E])(using ec: ExecutionContext): TakeStream[E] =
    apply(CloseableFuture.from(promise))

  /**
   * Creates a new `TakeStream` of length 1 from the given [[CloseableFuture]].
   * If the future is closed or fails, the stream will be closed immediately.
   *
   * @param cf When the future completes, its result will be published in the stream.
   * @param ec The execution context in which the future is executed.
   * @tparam E The type of the stream's result.
   * @return A new `TakeStream` of length 1.
   */
  def apply[E](cf: CloseableFuture[E])(using ec: ExecutionContext): TakeStream[E] = {
    val stream = new TakeStream[E](Stream[E](), 1)
    cf.onComplete {
      case Failure(_) => stream.close() // TODO: log exception?
      case Success(value) => stream.dispatch(value, Some(ec))
    }
    stream
  }
}
