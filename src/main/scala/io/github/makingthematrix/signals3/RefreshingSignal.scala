package io.github.makingthematrix.signals3

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}
/** A signal which initializes its value by executing the `loader` closeable future and then updates the value the same way
  * every time a new refresh event is published in the associated stream. The type of the event is not important.
  *
  * A typical use case for a refreshing signal might be, for example, to inform another component that something changed
  * in the storage while already retrieving the updated data. In this case, the refresh stream can be anything that
  * indicates the data has changed, and the loader is the query. The refresh event might even be a false positive:
  * then the loader function will be called but the subscriber function of the refreshing signal will not be notified as
  * the result of the loader is the same and so the value of the signal doesn't change.
  *
  * @see [[AggregatingSignal]]
  * @see [[CloseableFuture]]
  * @param loader A closeable future computing the value of the signal. It's passed by name, so if it is created in
  *               the place of argument, it will be executed for the first time only when the first subscriber function
  *               is registered in the signal, or immediately if `disableAutowiring` is used.
  *               If the execution fails or is cancelled, the value of the signal won't be updated.
  * @param refreshStream a stream publishing events which will trigger new executions of the `loader`. If a new
  *                      event comes before the previous call to `loader` finishes, the previous call will be cancelled.
  * @param ec The execution context in which the `loader` is executed.
  * @tparam V The value type of the signal and the result of the `loader` closeable future.
  */
final class RefreshingSignal[V](loader: () => CloseableFuture[V], refreshStream: Stream[?])
                               (using ec: ExecutionContext)
  extends Signal[V] {
  @volatile private var loadFuture = CloseableFuture.closed[Unit]
  @volatile private var subscription = Option.empty[Subscription]

  private def reload(): Unit = subscription.foreach { _ =>
    loadFuture.close()
    val p = Promise[Unit]()
    val thisReload = CloseableFuture.from(p)
    loadFuture = thisReload
    loader().onComplete {
      case Success(v) if loadFuture == thisReload =>
        p.success(updateWith(Some(v), Some(ec)))
      case Failure(ex) if loadFuture == thisReload =>
        p.failure(ex)
      case _ =>
    }
  }

  override protected def onWire(): Unit = {
    super.onWire()
    Future {
      subscription = Some(refreshStream.on(ec)(_ => reload())(using EventContext.Global))
      reload()
    }(using ec)
  }

  override protected def onUnwire(): Unit = {
    super.onUnwire()
    Future {
      subscription.foreach(_.unsubscribe())
      subscription = None
      loadFuture.close()
      value = None
    }(using ec)
  }
}


object RefreshingSignal {
  /** Creates a new refreshing signal from the `loader` which will be used to compute the signal's value and a stream of
    * events which will trigger reloading. The `loader` - a closeable future - will be every time executed in the
    * provided execution context. If the execution context is not provided, the default one will be used.
    *
    * @see [[Threading]]
    *
    * @param loader A closeable future computing the value of the signal. It's passed by name, so if it is created in
    *               the place of argument, it will be executed for the first time only when the first subscriber function
    *               is registered in the signal, or immediately if `disableAutowiring` is used.
    *               If the execution fails or is cancelled, the value of the signal won't be updated.
    * @param refreshStream a stream publishing events which will trigger new executions of the `loader`. If a new
    *                      event comes before the previous call to `loader` finishes, the previous call will be cancelled.
    * @param ec The execution context in which the `loader` is executed.
    * @tparam V The value type of the signal and the result of the `loader` closeable future.
    * @return A new refreshing signal with the value of the type `V`.
    */
  def apply[V](loader: () => CloseableFuture[V], refreshStream: Stream[?])
              (using ec: ExecutionContext): RefreshingSignal[V] =
    new RefreshingSignal(loader, refreshStream)

  /** A version of the `apply` method where the loader is a regular Scala future. It will be wrapped in a closeable future
    * on the first execution.
    */
  inline def from[V](loader: => Future[V], refreshStream: Stream[?])
                    (using ec: ExecutionContext): RefreshingSignal[V] =
    new RefreshingSignal(() => CloseableFuture.from(loader), refreshStream)
}
