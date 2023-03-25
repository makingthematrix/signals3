package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3

import java.util.{Timer, TimerTask}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.*
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.language.implicitConversions
import scala.ref.WeakReference
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}
import scala.util.chaining.scalaUtilChainingOps

/** `CloseableFuture` is an object that for all practical uses works like a future but enables the user to close the operation.
  * A closed future fails with `CloseableFuture.Closed` so the subscriber can differentiate between this and other
  * failure reasons. It is impossible to close a future if it is already completed or if it is uncloseable.
  *
  * @see https://github.com/wireapp/wire-signals/wiki/Overview
  * @see `Uncloseable` for details on uncloseable futures
  */
abstract class CloseableFuture[+T](using ec: ExecutionContext = Threading.defaultContext)
  extends Awaitable[T] with Closeable:
  self =>
  import CloseableFuture._

  /** Gives direct access to the underlying future. */
  def future: Future[T]

  /** Returns if the future is actually closeable (not completed and not uncloseable)
    * @see `Uncloseable` for details on uncloseable futures
    */
  def isCloseable: Boolean

  /** Creates a copy of this future that cannot be closed
    * @see `Uncloseable` for details on uncloseable futures
    */
  def toUncloseable: CloseableFuture[T]

  /** Tries to fails the future with the given exception as the failure's reason.
    *
    * @param th The reason for the failure
    * @return `true` if the future was closed, `false` if it was not possible to close it
    */
  def fail(th: Throwable): Boolean

  /** Adds a callback for when the future is closed (but not failed for any other reason).
    * There can be more than one `onClose` callback.
    * Returns the reference to itself so it can be chained with other `CloseableFuture` methods.
    *
    * @param body The callback code
    * @return The reference to itself
    */
  def onClose(body: => Unit): CloseableFuture[T] =
    if isCloseable then
      future.onComplete {
        case Failure(Closed) => body
        case _ =>
      }
    this

  /** If the future is actually closeable, adds a timeout after which this future will be closed.
    * In theory, it's possible to add more than one timeout - the shortest one will close all others.
    * Returns the reference to itself so it can be chained with other `CloseableFuture` methods.
    *
    * @param timeout A time interval after which this future is closed if it's closeable
    * @return The reference to itself
    */
  final def addTimeout(timeout: FiniteDuration): CloseableFuture[T] =
    if isCloseable then
      val f = CloseableFuture.delayed(timeout)(this.close())
      onComplete(_ => f.close())
    this

  /** Tries to close the future. If successful, the future fails with `CloseableFuture.Closed`
    *
    * @return `true` if the future was closed, `false` if it was not possible to close it
    */
  override def closeAndCheck(): Boolean = fail(Closed)

  override def isClosed: Boolean = future.isCompleted

  /** Same as `Future.onComplete`.
    * @see `Future`
    *
    * @param f The function which will be executed when this future is completed
    * @param executor The execution context in which `f` will be executed, by default it will be the execution context
    *                 of this future
    * @tparam U The result type of `f`
    */
  inline final def onComplete[U](f: Try[T] => U)(using executor: ExecutionContext = ec): Unit = future.onComplete(f)(executor)

  /** Same as `Future.foreach`.
    * @see `Future`
    *
    * @param pf The partial function which will be executed when this future is completed with success
    * @param executor The execution context in which `pf` will be executed, by default it will be the execution context
    *                 of this future
    * @tparam U The result type of `pf`
    */
  inline final def foreach[U](pf: T => U)(using executor: ExecutionContext = ec): Unit = future.foreach(pf)(executor)

  /** Creates a new closeable future by applying the `f` function to the successful result
    * of this one. If this future is completed with an exception then the new future will
    * also contain this exception. If the operation after the closing was provided,
    * it will be carried over to the new closeable future.
    * If you close this future,
    *
    * @see `Future.map` for more - this is its equivalent for `CloseableFuture`
    *
    * @param f The function transforming the result type of this future into the result type of the new one
    * @param executor The execution context in which the new future will be executed, by default it will be
    *                 the execution context of this future
    * @tparam U The result type of `f`
    * @return A new closeable future with the result type `U` being the outcome of the function `f` applied to
    *         the result type of this future
    */
  final def map[U](f: T => U)(using executor: ExecutionContext = ec): CloseableFuture[U] =
    val p = Promise[U]()
    @volatile var closeSelf: Option[() => Unit] = Some(() => Future(self.close())(executor))

    future.onComplete { v =>
      closeSelf = None
      p.tryComplete(v.flatMap(res => Try(f(res))))
    }(executor)

    new ActuallyCloseable(p):
      override def closeAndCheck(): Boolean =
        if super.closeAndCheck() then
          closeSelf.foreach(_())
          true
        else false

  /** Creates a new closeable future by applying the predicate `p` to the current one. If the original future
    * completes with success, but the result does not satisfies the predicate, the resulting future will fail with
    * a `NoSuchElementException`.
    *
    * @see `Future.filter` for more - this is its equivalent for `CloseableFuture`
    *
    * @param p a predicate function returning a boolean
    * @param executor The execution context in which the new future will be executed, by default it will be
    *                 the execution context of this future
    * @return a new closeable future that will finish with success only if the predicate is satisfied
    */
  final def filter(p: T => Boolean)(using executor: ExecutionContext = ec): CloseableFuture[T] =
    flatMap {
      case res if p(res) => CloseableFuture.successful(res)
      case res => CloseableFuture.failed(new NoSuchElementException(s"CloseableFuture.filter predicate is not satisfied, the result is $res"))
    }

  /** An alias for `filter`, used by for-comprehensions. */
  final def withFilter(p: T => Boolean)(using executor: ExecutionContext = ec): CloseableFuture[T] = filter(p)

  /** Creates a new closeable future by mapping the value of the current one, if the given partial function
    * is defined at that value. Otherwise, the resulting closeable future will fail with a `NoSuchElementException`.
    *
    * @see `Future.collect` for more - this is its equivalent for `CloseableFuture`
    *
    * @param pf A partial function which will be applied to the result of the original future if that result belongs to
    *           a subset for which the partial function is defined
    * @param executor The execution context in which the new future will be executed, by default it will be
    *                 the execution context of this future
    * @tparam U The result type of the partial function `pf`
    * @return A new closeable future that will finish with success only if the partial function is applied
    */
  final def collect[U](pf: PartialFunction[T, U])(using executor: ExecutionContext = ec): CloseableFuture[U] =
    flatMap {
      case r if pf.isDefinedAt(r) => CloseableFuture(pf.apply(r))
      case t => CloseableFuture.failed(new NoSuchElementException(s"CloseableFuture.collect partial function is not defined at $t"))
    }

  /** Creates a new closeable future by applying a function to the successful result of this future, and returns
    * the result of the function as the new future. If this future fails or is closed, the close operation and
    * the result is carried over.
    *
    * @see `Future.flatMap` for more - this is its equivalent for `CloseableFuture`
    *
    * @param f A function which will be applied to the result of the original future if that result belongs to
    *           a subset for which the partial function is defined
    * @param executor The execution context in which the new future will be executed, by default it will be
    *                 the execution context of this future
    * @tparam U The result type of the function `f`
    * @return A new closeable future that will finish with success only if the partial function is applied
    */
  final def flatMap[U](f: T => CloseableFuture[U])(using executor: ExecutionContext = ec): CloseableFuture[U] =
    val p = Promise[U]()
    @volatile var closeSelf: Option[() => Unit] = Some(() => self.close())

    self.future.onComplete { res =>
      closeSelf = None
      if !p.isCompleted then res match
        case f: Failure[_] => p.tryComplete(f.asInstanceOf[Failure[U]])
        case Success(v) =>
          Try(f(v)) match
            case Success(future) =>
              closeSelf = Some(() => future.close())
              future.onComplete { res =>
                closeSelf = None
                p.tryComplete(res)
              }
              if p.isCompleted then future.close()
            case Failure(t) =>
              p.tryFailure(t)
    }

    new ActuallyCloseable(p):
      override def closeAndCheck(): Boolean =
        if super.closeAndCheck() then
          Future(closeSelf.foreach(_ ()))(executor)
          true
        else false

  /** Creates a new closeable future that will handle any matching throwable that this future might contain.
    * If there is no match, or if this future contains a valid result then the new future will contain the same.
    * Works also if the current future is closed.
    *
    * @see `Future.recover` for more - this is its equivalent for `CloseableFuture`
    *
    * @param pf A partial function which will be applied to the `Throwable` returned as the failure reason of
    *           the original future if that result belongs to a subset for which the partial function is defined.
    *           (For example, you can define a partial function that works only on a specific type of exception,
    *           i.e. on a subclass of `Throwable`, and for other exceptions the future should really fail).
    * @param executor The execution context in which the new future will be executed, by default it will be
    *                 the execution context of this future
    * @tparam U The result type of the partial function `pf` and the result type of the new future if `pf` is defined
    *           for the `Throwable` returned by the original future
    * @return A new closeable future that will finish with success only if the partial function is applied
    */
  inline final def recover[U >: T](pf: PartialFunction[Throwable, U])(using executor: ExecutionContext = ec): CloseableFuture[U] =
    recoverWith(pf.andThen(CloseableFuture.successful(_)))

  /** Creates a new closeable future that will handle any matching throwable that the current one might contain by
    * assigning it a value of another future. Works also if the current future is closed. If there is no match,
    * or if this closeable future contains a valid result, then the new future will contain the same result.
    *
    * @see `Future.recoverWith` for more - this is its equivalent for `CloseableFuture`
    *
    * @param pf A partial function which will be applied to the `Throwable` returned as the failure reason of
    *           the original future if that result belongs to a subset for which the partial function is defined.
    *           (For example, you can define a partial function that works only on a specific type of exception,
    *           i.e. on a subclass of `Throwable`, and for other exceptions the future should really fail).
    * @param executor The execution context in which the new future will be executed, by default it will be
    *                 the execution context of this future
    * @tparam U The result type of the partial function `pf` and the result type of the new future if `pf` is defined
    *           for the `Throwable` returned by the original future
    * @return A new closeable future that will finish with success only if the partial function is applied
    */
  final def recoverWith[U >: T](pf: PartialFunction[Throwable, CloseableFuture[U]])
                               (using executor: ExecutionContext = ec): CloseableFuture[U] =
    val p = Promise[U]()
    @volatile var closeSelf: Option[() => Unit] = Some(() => self.close())

    future.onComplete { res =>
      closeSelf = None
      if !p.isCompleted then res match
        case Failure(t) if pf.isDefinedAt(t) =>
          val future = pf.applyOrElse(t, (_: Throwable) => this)
          closeSelf = Some(() => future.close())
          future.onComplete { res =>
            closeSelf = None
            p.tryComplete(res)
          }
          if p.isCompleted then future.close()
        case other =>
          p.tryComplete(other)
    }

    new ActuallyCloseable(p):
      override def closeAndCheck(): Boolean =
        if super.closeAndCheck() then
          Future(closeSelf.foreach(_ ()))(executor)
          true
        else false

  /** Flattens a nested closeable future.
    * If we have a closeable future `val cf: CloseableFuture[ CloseableFuture[U] ]` with the result of `cf` is
    * another closeable future - i.e. after executing the original one we will get another future and then we will
    * execute that, and only then get the result of type `U` - then we can use `CloseableFuture.flatten` to merge
    * these two steps into one. As a result, we will have one closeable future which after successful execution will
    * give us a result of type `U`.
    *
    * @see `Future.flatten` for more - this is its equivalent for `CloseableFuture`
    *
    * @param executor The execution context in which the new future will be executed, by default it will be
    *                 the execution context of this future
    * @tparam U The result type of the nested future and the result type of the resulting future
    * @return A new closeable future made from flattening the original one
    */
  inline final def flatten[U](using evidence: T <:< CloseableFuture[U], executor: ExecutionContext = ec): CloseableFuture[U] =
    flatMap(x => x)

  /** Creates a new CloseableFuture from the current one and the provided one. The new future completes with success
    * only if both original futures complete with success. If any of the fails or is closed, the resulting future
    * fails or is closed as well. If the user closes the resulting future, both original futures are closed.
    *
    * @see `Future.zip` for more - this is its equivalent for `CloseableFuture`
    *
    * @param other The other closeable future, with the result of the type `U`, that will be zipped with this one
    * @param executor The execution context in which the new future will be executed, by default it will be
    *                 the execution context of this future
    * @tparam U the result type of the other future
    * @return A new closeable future with the result type being a tuple `(T, U)` where `T` is the result type of this future
    */
  inline final def zip[U](other: CloseableFuture[U])(using executor: ExecutionContext = ec): CloseableFuture[(T, U)] =
    CloseableFuture.zip(self, other)

  /** Same as `Future.ready`.
    *'''''This method should not be called directly; use `Await.ready` instead.'''''
    */
  @throws[InterruptedException]
  @throws[TimeoutException]
  override final def ready(atMost: Duration)(using permit: CanAwait): this.type =
    future.ready(atMost)
    this

  /** Same as `Future.result`.
    * '''''This method should not be called directly; use `Await.result` instead.'''''
    */
  @throws[Exception]
  override final def result(atMost: Duration)(using permit: CanAwait): T = future.result(atMost)

  /** Registers the closeable future in the given event context. When the event context is stopped, the future
    * will be closed. The subscription is also returned so it can be managed manually.
    *
    * @see `EventContext`
    *
    * @param eventContext The event context which this future will be subscribed to
    * @return A `Subscription` representing the connection between the event context and the future.
    *         You can use it to close the future manually, even if the event context hasn't stopped yet.
    */
  final def withAutoClosing(using eventContext: EventContext = EventContext.Global): Subscription =
    new BaseSubscription(WeakReference(eventContext)) {
      override def onUnsubscribe(): Unit =
        close()
        eventContext.unregister(this)

      override def onSubscribe(): Unit = {}
    }.tap(eventContext.register)

/** `CloseableFuture` is an object that for all practical uses works like a future but enables the user to close the operation.
  * A closed future fails with `CloseableFuture.Closed` so the subscriber can differentiate between this and other
  * failure reasons.
  *
  * @see https://github.com/wireapp/wire-signals/wiki/Overview
  */
object CloseableFuture:
  extension [T](future: Future[T])
    def lift: CloseableFuture[T] = CloseableFuture.lift(future)(using Threading.defaultContext)

  given toFuture[T]: Conversion[CloseableFuture[T], Future[T]] with
    def apply(cf: CloseableFuture[T]): Future[T] = cf.future

  /** When the closeable future is closed, `Closed` is provided as the reason
    * of failure of the underlying future.
    */
  case object Closed extends Exception("Future closed") with NoStackTrace

  /** A subclass of `CloseableFuture` which represents a subset of closeable futures which are actually closeable.
    * I know it means the name of the parent class is misleading, since not all closeable futures are closeable, but,
    * well, naming is a hard problem in computer science.
    * The reasoning here goes like this: An actually closeable `CloseableFuture` is a wrapper over a `Promise`, not
    * a `Future`. We create a promise and try to fulfill it with the code given to us in one of the constructor methods.
    * If we succeed, the closeable future will behave just as a regular one. But thanks to that we hold a promise,
    * we are able to close the ongoing execution of that piece of code by calling `tryFailure` on the promise.
    *
    * However, in some situations closing will not be possible. One is of course that the future may already be completed -
    * with success or with failure (including that it might have been already closed). This is why the `close` method
    * returns a boolean - we can't be always 100% sure that calling it will actually close the future.
    *
    * But there is also another possibility. Because of how ubiquitous futures are in the Scala standard library, it makes
    * sense to be able to wrap them in closeable futures so that they can be used in the same code with the actually
    * closeable futures. After all, if we're on the happy path, the two behave the same. The only difference appears
    * only when we try to close such a "closeable" future - we don't have access to the parent promise of that future,
    * so we can't close it. Calling `close` on such a future will always return `false`, a callback registered with
    * `onClose` will be ignored, etc.
    *
    * The last case is an inverse of the second one: Sometimes we have an actually closeable future but we want to
    * make sure that it won't be closed in a given piece of code. Imagine a situation when we wait for a result of
    * complex computations which are modelled as a closeable future. The computations may be closed by one class
    * that manages the computations, but we also give a reference to that closeable future to a few other classes in
    * the program, so that when the computations finish, those classes will be immediately informed, get the result, and
    * use it. But they shouldn't be able to close the original computations - even if the given class becomes disinterested
    * in the result, it should not be able to stop the computations for all others.
    * In such case, we can use the method `toUncloseable` - it will give us an `Uncloseable` wrapped over
    * the `promise.future` of our original `Closeable` future.
    *
    * @param promise The promise a new closeable future wraps around
    * @param ec      The execution context in which the future will be executed. By default it's the default context set
    *                in the `Threading` class
    * @tparam T The result type of the closeable future
    */
  private[signals3] class ActuallyCloseable[+T](promise: Promise[T])
                                               (using ec: ExecutionContext = Threading.defaultContext) extends CloseableFuture[T]:
    override val future: Future[T] = promise.future
    override def fail(ex: Throwable): Boolean = promise.tryFailure(ex)
    override def toUncloseable: CloseableFuture[T] = new Uncloseable[T](future)
    override def isCloseable: Boolean = !future.isCompleted

  /** A subclass of `CloseableFuture` which represents a subset of closeable futures which are actually **un**closeable.
    * I know it means the name of the parent class is misleading, since not all closeable futures are closeable, but,
    * well, naming is a hard problem in computer science.
    * The reasoning here goes like this: An actually closeable `CloseableFuture` is a wrapper over a `Promise`, not
    * a `Future`. We create a promise and try to fulfill it with the code given to us in one of the constructor methods.
    * If we succeed, the closeable future will behave just as a regular one. But thanks to that we hold a promise,
    * we are able to close the ongoing execution of that piece of code by calling `tryFailure` on the promise.
    *
    * However, in some situations closing will not be possible. One is of course that the future may already be completed -
    * with success or with failure (including that it might have been already closed). This is why the `close` method
    * returns a boolean - we can't be always 100% sure that calling it will actually close the future.
    *
    * But there is also another possibility. Because of how ubiquitous futures are in the Scala standard library, it makes
    * sense to be able to wrap them in closeable futures so that they can be used in the same code with the actually
    * closeable futures. After all, if we're on the happy path, the two behave the same. The only difference appears
    * only when we try to close such a "closeable" future - we don't have access to the parent promise of that future,
    * so we can't close it. Calling `close` on such a future will always return `false`, a callback registered with
    * `onClose` will be ignored, etc.
    *
    * The last case is an inverse of the second one: Sometimes we have an actually closeable future but we want to
    * make sure that it won't be closed in a given piece of code. Imagine a situation when we wait for a result of
    * complex computations which are modelled as a closeable future. The computations may be closed by one class
    * that manages the computations, but we also give a reference to that closeable future to a few other classes in
    * the program, so that when the computations finish, those classes will be immediately informed, get the result, and
    * use it. But they shouldn't be able to close the original computations - even if the given class becomes disinterested
    * in the result, it should not be able to stop the computations for all others.
    * In such case, we can use the method `toUncloseable` - it will give us an `Uncloseable` wrapped over
    * the `promise.future` of our original `Closeable` future.
    *
    * @param future The future a new **un**closeable future wraps around
    * @param ec     The execution context in which the future will be executed. By default it's the default context set
    *               in the `Threading` class
    * @tparam T The result type of the closeable future
    */
  private[signals3] final class Uncloseable[+T](override val future: Future[T])
                                               (using ec: ExecutionContext = Threading.defaultContext) extends CloseableFuture[T]:
    override def fail(ex: Throwable): Boolean = false
    override def toUncloseable: CloseableFuture[T] = this
    override def isCloseable: Boolean = false

  private final class PromiseCompletingRunnable[T](body: => T) extends Runnable:
    val promise: Promise[T] = Promise[T]()

    override def run(): Unit = if !promise.isCompleted then promise.tryComplete(Try(body))

  /** Creates `CloseableFuture[T]` from the given function with the result type of `T`.
    *
    * @param body The function to be executed asynchronously
    * @param ec The execution context
    * @tparam T The result type of the given function
    * @return A closeable future executing the function
    */
  final def apply[T](body: => T)(using ec: ExecutionContext = Threading.defaultContext): CloseableFuture[T] =
    val pcr = new PromiseCompletingRunnable(body)
    ec.execute(pcr)
    new ActuallyCloseable(pcr.promise)

  /** Turns a regular `Future[T]` into an **uncloseable** `CloseableFuture[T]`.
    *
    * @param future The future to be lifted
    * @param ec The execution context
    * @tparam T The future's result type
    * @return A new **uncloseable** future wrapped over the original future
    */
  inline final def lift[T](future: Future[T])(using ec: ExecutionContext = Threading.defaultContext): CloseableFuture[T] =
    new Uncloseable(future)

  /** Turns a `Promise[T]` into a **closeable** `CloseableFuture[T]`.
    *
    * @param promise The promise to be lifted
    * @param ec The execution context
    * @tparam T The promise's result type
    * @return A new **closeable** future wrapped over the original promise
    */
  inline final def from[T](promise: Promise[T])(using ec: ExecutionContext = Threading.defaultContext): CloseableFuture[T] =
    new ActuallyCloseable(promise)

  /** Creates an empty closeable future that will finish its execution with success after the given time.
    * Typically used together with `map` or a similar method to execute computation with a delay.
    *
    * @param duration The duration after which the future finishes its execution
    * @param ec The execution context
    * @return A new closeable future of `Unit` which will finish with success after the given time
    */
  final def delay(duration: FiniteDuration)(using ec: ExecutionContext = Threading.defaultContext): CloseableFuture[Unit] =
    if duration <= Duration.Zero then successful(())
    else
      val p = Promise[Unit]()
      val task = schedule(() => p.trySuccess(()), duration.toMillis)
      new ActuallyCloseable(p).onClose(task.cancel())

  /** Creates an empty closeable future which will repeat the mapped computation every given `interval` until
    * closed. The first computation is executed with `interval` delay. If the executed operation takes longer
    * than `interval` it will not be closed - the new execution will start as scheduled, but the old one will
    * continue. The ability to close the old one will be lost, as the reference will from now on point to
    * the new execution.
    *
    * @note Since Signals3 1.1.0 this method tries to adjust for inevitable delays caused by calling its own code.
    *       We assume that the initialization will cause the first call to be executed with some delay, so the second
    *       call will be executed a bit earlier than `interval` to accomodate that. The next calls should be executed
    *       as planned, unless external causes will make another delay, after which the `repeat` method will again
    *       try to adjust by shortening the delay for the consecutive call.
    *
    * @param interval The initial delay and the consecutive time interval between repeats.
    * @param body A task repeated every `interval`. If `body` throws an exception, the method will ignore it and
    *             call `body` again, after interval`.
    * @return A closeable future representing the whole process.
    */
  final def repeat(interval: FiniteDuration)(body: => Unit)(using ec: ExecutionContext = Threading.defaultContext): CloseableFuture[Unit] =
    val intervalMillis = interval.toMillis
    @volatile var last = System.currentTimeMillis
    def calcInterval(): Long =
      val now = System.currentTimeMillis
      val error = now - last - intervalMillis
      last = now
      intervalMillis - (error / 2L) - 1L

    repeatVariant(calcInterval)(body)

  /** Creates an empty closeable future which will repeat the mapped computation until closed. At creation,
    * and then after each execution, the c.f. will call the `interval` function to get the `FiniteDuration` after which
    * the next execution should occur. This allows to modify the interval between each two executions based on some
    * external data. If the executed operation takes longer than `interval` it will not be closed - the new execution
    * will start as scheduled, but the old one will continue. The ability to close the old one will be lost,
    * as the reference will from now on point to the new execution.
    *
    * @todo The logic for interval <= 0L is wrong. This version, with one call to `successful` must be moved to `repeat`
    *       with constant interval. In case of variable interval, it's possible that one interval <= 0L will occur.
    *       The task should be then performed immediately, and then another interval should be computer.
    *
    * @param interval The function returning the delay to the first and then to each next execution.
    * @param body A task repeated every `interval`. If `body` throws an exception, the method will ignore it and
    *             call `body` again, after `interval`.
    * @return A closeable future representing the whole process.
    */
  final def repeatVariant(interval: () => Long)(body: => Unit)(using ec: ExecutionContext = Threading.defaultContext): CloseableFuture[Unit] =
    val intv = interval()
    if intv <= 0L then successful(Try(body))
    else
      new ActuallyCloseable(Promise[Unit]()):
        inline def sched(t: Long): TimerTask = schedule(() => { Try(body); startNewTimeoutLoop() }, t)
        @volatile private var closed: Boolean = false
        @volatile private var task: TimerTask = sched(intv)

        private def startNewTimeoutLoop(): Unit =
          if !closed then
            task = sched(interval())

        override def closeAndCheck(): Boolean =
          task.cancel()
          closed = true
          super.closeAndCheck()

  private val timer: Timer = new Timer()

  private def schedule(f: () => Any, delay: Long): TimerTask =
    new TimerTask {
      override def run(): Unit = f()
    }.tap {
      timer.schedule(_, delay)
    }

  /** A utility method that combines `delay` with `map`.
    * Creates a closeable future that will execute the given `body` block after the given time.
    *
    * @param duration The duration after which the future finishes its execution
    * @param body A block of code which will be run after the `duration`
    * @tparam T the type of the result returned by `body`
    * @param ec The execution context
    * @return A new closeable future of `T`
    */
  final def delayed[T](duration: FiniteDuration)(body: => T)(using ec: ExecutionContext = Threading.defaultContext): CloseableFuture[T] =
    if duration <= Duration.Zero then CloseableFuture(body)
    else delay(duration).map { _ => body }

  /** Creates an already completed `CloseableFuture[T]` with the specified result.
    *
    * @param result The result of the completed future
    * @tparam T The type of the `result`
    * @return An already successfully completed **un**closeable future
    */
  inline final def successful[T](result: T): CloseableFuture[T] = new Uncloseable[T](Future.successful(result))

  /** Creates an already failed `CloseableFuture[T]` with the given throwable as the failure reason.
    *
    * @param th A `Throwable` being the reason of why the future is failed
    * @tparam T The type of what the future would return had it been successful
    * @return An already failed **un**closeable future
    */
  inline final def failed[T](th: Throwable): CloseableFuture[T] = new Uncloseable[T](Future.failed(th))

  /** Creates an already closed `CloseableFuture[T]`.
    *
    * @tparam T The type of what the future would return had it been successful
    * @return An already closed **un**closeable future
    */
  inline final def closed[T](): CloseableFuture[T] = failed(Closed)

  /** Creates a new `CloseableFuture[Iterable[T]]` from a Iterable of `Iterable[T]`.
    * The original futures are executed asynchronously, but there are some rules that control them:
    * 1. All original futures need to succeed for the resulting one to succeed.
    * 2. Closing the resulting future will close all the original ones except those that are uncloseable.
    * 3a. If one of the original futures is closed (if it's closeable) the resulting future will be closed
    *     and all other original futures (those that are not uncloseable) will be closed as well.
    * 3b. If one of the original futures fails for any other reason than closing, the resulting future will fail
    *     with the same reason as the original one and all other original futures will be closed (if they are not
    *     uncloseable) - i.e. they will not fail with the original reason but with `CloseableFuture.Closed`.
    *
    * @param futures A collection (`Iterable`) of closeable futures of the same type `T`
    * @param ec The execution context
    * @tparam T The type returned by each of original closeable futures
    * @return A closeable future with its result being a collection of results of original futures in the same order
    */
  def sequence[T](futures: Iterable[CloseableFuture[T]])(using ec: ExecutionContext): CloseableFuture[Iterable[T]] =
    val results = new ArrayBuffer[(Int, T)](futures.size)
    val promise = Promise[Iterable[T]]()

    futures.zipWithIndex.foreach { case (f, i) =>
      f.onComplete {
        case Success(t) =>
          synchronized {
            results.append((i, t))
            if results.size == futures.size then promise.trySuccess(results.sortBy(_._1).map(_._2).toVector)
          }
        case Failure(ex) => promise.tryFailure(ex)
      }
    }

    promise.future.onComplete {
      case Failure(_) => futures.foreach(_.close())
      case _ =>
    }

    new ActuallyCloseable(promise)

  /** Transforms an `Iterable[T]` into a `CloseableFuture[Iterable[U]]` using
    * the provided function `T => CloseableFuture[U]`. Each closeable future will be executed
    * asynchronously. If any of the original closeable futures fails or is closed, the resulting
    * one will will fail immediately, but the other original ones will not.
    *
    * @see `sequence` for closing rules
    *
    * @param in A collection (`Iterable`) of input elements of the same type `T`
    * @param f A function that for each input element will create a new closeable future of the type `U`
    * @param ec The execution context
    * @tparam T The type of input elements
    * @tparam U The type of the result of closeable futures created by `f`
    * @return A closeable future with its result being a collection of results of futures created by `f`
    */
  inline def traverse[T, U](in: Iterable[T])(f: T => CloseableFuture[U])
                           (using ec: ExecutionContext): CloseableFuture[Iterable[U]] =
    sequence(in.map(f))

  /** Transforms an `Iterable[T]` into a `CloseableFuture[Iterable[U]]` using
    * the provided function `T => CloseableFuture[U]`. Each closeable future will be executed
    * synchronously. If any of the original closeable futures fails or is closed, the resulting one
    * also fails and no consecutive original futures will be executed.
    *
    * Closing the resulting future will close the one which is executed at the moment and it will prevent all
    * the consecutive original futures from being executed.
    *
    * @see `sequence` for closing rules
    *
    * @param in A collection (`Iterable`) of input elements of the same type `T`
    * @param f A function that for each input element will create a new closeable future of the type `U`
    * @param ec The execution context
    * @tparam T The type of input elements
    * @tparam U The type of the result of closeable futures created by `f`
    * @return A closeable future with its result being a collection of results of futures created by `f`
    */
  def traverseSequential[T, U](in: Iterable[T])(f: T => CloseableFuture[U])
                              (using ec: ExecutionContext): CloseableFuture[Iterable[U]] =
    def processNext(remaining: Iterable[T], acc: List[U] = Nil): CloseableFuture[Iterable[U]] =
      if remaining.isEmpty then CloseableFuture.successful(acc.reverse)
      else f(remaining.head).flatMap(res => processNext(remaining.tail, res :: acc))

    processNext(in)

  /** Creates a new `CloseableFuture[(T, U)]` from two original closeable futures, one of the type `T`,
    * and one of the type `U`. The new future will fail or be closed if any of the original ones fails.
    * Closing the new future will fail both original ones.
    *
    * @param f1 The first of two original closeable futures, with the result of type `T`
    * @param f2 The second of two original closeable futures, with the result of type `U`
    * @param ec The execution context
    * @tparam T The type of the result of the first of original closeable futures
    * @tparam U The type of the result of the second of original closeable futures
    * @return A new closeable future with its result being a tuple of results of both original futures
    */
  def zip[T, U](f1: CloseableFuture[T], f2: CloseableFuture[U])(using ec: ExecutionContext): CloseableFuture[(T, U)] =
    val p = Promise[(T, U)]()

    p.completeWith((for r1 <- f1; r2 <- f2 yield (r1, r2)).future)

    new ActuallyCloseable(p):
      override def closeAndCheck(): Boolean =
        if super.closeAndCheck() then
          Future { f1.close(); f2.close() }(ec)
          true
        else false

  /** Turns one or more of closeable futures into "uncloseable" - entities of the `Uncloseable` subsclass of `CloseableFuture`.
    *
    * @see [[Uncloseable]] for details
    * @param futures One or more closeable futures you want to make sure won't get closed
    * @tparam T The type of the result of the original closeable futures
    * @return A collection of copies of closeable futures which are **un**closeable
    */
  inline def toUncloseable[T](futures: CloseableFuture[T]*): Iterable[CloseableFuture[T]] = futures.map(_.toUncloseable)
