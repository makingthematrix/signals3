package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.SourceStream.{SourceRecoverStream, SourceRecoverWithStream}
import io.github.makingthematrix.signals3.Stream.EventSubscriber

import scala.annotation.targetName
import scala.concurrent.ExecutionContext

/** The usual entry point for publishing events.
  *
  * Create a new source stream either using the default constructor or the `Stream.apply[V]()` method. The source stream exposes
  * methods you can use for publishing new events. Then you can combine it with other event streams and finally subscribe a function
  * to it which will receive the resulting events.
  *
  * @tparam E the type of the event
  */
class SourceStream[E] extends Stream[E] {
  /** Publishes the event to all subscribers.
    *
    * @see [[Stream.publish]]
    *
    *      The original `publish` method of the [[Stream]] class is `protected` to ensure that intermediate event streams - those created
    *      by methods like `map`, `flatMap`, `filter`, etc. - will not be used to directly publish events to them. The source stream
    *      exposes this method for public use.
    * @param event The event to be published.
    */
  override def publish(event: E): Unit = dispatch(event, None)

  /** An alias for the `publish` method with no explicit execution context. */
  @targetName("bang")
  inline def !(event: E): Unit = publish(event)

  /** Publishes the event to all subscriber, using the given execution context.
    *
    * @see [[Stream.publish]]
    * @param event The event to be published.
    * @param ec The execution context used for dispatching. The default implementation ensures that if `ec` is the same as
    *           the execution context used to register the subscriber, the subscriber will be called immediately. Otherwise,
    *           a future working in the subscriber's execution context will be created and `ec` will be ignored.
    */
  def publish(event: E, ec: ExecutionContext): Unit = dispatch(event, Some(ec))

  /** A version of the `publish` method which takes the implicit execution context for dispatching.
    *
    * The difference between `!!` and `!` (and also between the two `publish` methods) is that even if the source's
    * execution context is the same as the subscriber's execution context, if we send an event using `!`, it will be
    * wrapped in a future and executed asychronously. If we use `!!` then for subscribers working in the same
    * execution context the call will be synchronous. This may be desirable in some cases, but please use with caution.
    */
  @targetName("twobang")
  inline def !!(event: E)(using ec: ExecutionContext): Unit = publish(event, ec)

  override protected def recoverPriv(f: Throwable => Option[E]): SourceStream[E] = SourceRecoverStream(this, f)
  
  override def recover(f: Throwable => E): SourceStream[E] = recoverPriv(t => Some(f(t)))
  override def ignoreExceptions: SourceStream[E] = recoverPriv(_ => None)
  override def ignoreExceptions(f: Throwable => Unit): SourceStream[E] = recoverPriv(t => {f(t); None})

  override protected def recoverWithPriv(pf: PartialFunction[Throwable, Option[E]]): SourceStream[E] = SourceRecoverWithStream[E](this, pf)
  override def recoverWith(pf: PartialFunction[Throwable, E]): SourceStream[E] = recoverWithPriv(pf.andThen(Some(_)))
  override def ignoreExceptionsWith(pf: PartialFunction[Throwable, Unit]): SourceStream[E] = recoverWithPriv(pf.andThen(_ => None))
  override def withDefault(value: E): SourceStream[E] = recover(_ => value)
}

object SourceStream {
  private[signals3] final class SourceRecoverStream[E](source: SourceStream[E], recover: Throwable => Option[E])
    extends SourceStream[E] with EventSubscriber[E] {
    override protected[signals3] def onWire(): Unit = source.subscribe(this)
    override protected[signals3] def onUnwire(): Unit = source.unsubscribe(this)

    override def dispatch(event: E, sourceContext: Option[ExecutionContext]): Unit =
      tryDispatch(event, sourceContext, recover)

    override def publish(event: E): Unit =
      tryDispatch(event, None, recover)

    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      tryDispatch(event, sourceContext, recover)

    private def tryDispatch(event: E, sourceContext: Option[ExecutionContext], recover: Throwable => Option[E]): Unit =
      try super.dispatch(event, sourceContext) catch {
        case t: Throwable => recover(t).foreach(super.dispatch(_, sourceContext))
      }
  }

  private[signals3] final class SourceRecoverWithStream[E](source: SourceStream[E], recoverWith: PartialFunction[Throwable, Option[E]])
    extends SourceStream[E] with EventSubscriber[E] {
    override protected[signals3] def onWire(): Unit = source.subscribe(this)
    override protected[signals3] def onUnwire(): Unit = source.unsubscribe(this)

    override def dispatch(event: E, sourceContext: Option[ExecutionContext]): Unit =
      tryDispatchWith(event, sourceContext, recoverWith)

    override def publish(event: E): Unit = tryDispatchWith(event, None, recoverWith)

    override protected[signals3] def onEvent(event: E, sourceContext: Option[ExecutionContext]): Unit =
      tryDispatchWith(event, sourceContext, recoverWith)

    private def tryDispatchWith(event: E, sourceContext: Option[ExecutionContext], recoverWith: PartialFunction[Throwable, Option[E]]): Unit =
      try super.dispatch(event, sourceContext) catch {
        case t: Throwable if recoverWith.isDefinedAt(t) =>
          recoverWith(t).foreach(super.dispatch(_, sourceContext))
      }
  }
}
