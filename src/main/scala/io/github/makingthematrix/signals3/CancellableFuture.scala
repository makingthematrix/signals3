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

/** `CancellableFuture` is an object that for all practical uses works like a future but enables the user to cancel the operation.
  * A cancelled future fails with `CancellableFuture.CancelException` so the subscriber can differentiate between thisand other
  * failure reasons.
  *
  * @see https://github.com/wireapp/wire-signals/wiki/Overview
  */
object CancellableFuture:
  extension [T](future: Future[T])
    def lift: CancellableFuture[T] = CancellableFuture.lift(future)(using Threading.defaultContext)

  given toFuture[T]: Conversion[CancellableFuture[T], Future[T]] with
    def apply(cf: CancellableFuture[T]): Future[T] = cf.future
  
  /** When the cancellable future is cancelled, `CancelException` is provided as the reason
    * of failure of the underlying future.
    */
  case object CancelException extends Exception("Operation cancelled") with NoStackTrace

  private final class PromiseCompletingRunnable[T](body: => T) extends Runnable:
    val promise: Promise[T] = Promise[T]()

    override def run(): Unit = if !promise.isCompleted then promise.tryComplete(Try(body))

  /** Creates `CancellableFuture[T]` from the given function with the result type of `T`.
    *
    * @param body The function to be executed asynchronously
    * @param ec The execution context
    * @tparam T The result type of the given function
    * @return A cancellable future executing the function
    */
  final def apply[T](body: => T)(using ec: ExecutionContext = Threading.defaultContext): CancellableFuture[T] =
    val pcr = new PromiseCompletingRunnable(body)
    ec.execute(pcr)
    new Cancellable(pcr.promise)

  /** Turns a regular `Future[T]` into an **uncancellable** `CancellableFuture[T]`.
    *
    * @param future The future to be lifted
    * @param ec The execution context
    * @tparam T The future's result type
    * @return A new **uncancellable** future wrapped over the original future
    */
  inline final def lift[T](future: Future[T])(using ec: ExecutionContext = Threading.defaultContext): CancellableFuture[T] =
    new Uncancellable(future)

  /** Turns a `Promise[T]` into a **cancellable** `CancellableFuture[T]`.
    *
    * @param promise The promise to be lifted
    * @param ec The execution context
    * @tparam T The promise's result type
    * @return A new **cancellable** future wrapped over the original promise
    */
  inline final def from[T](promise: Promise[T])(using ec: ExecutionContext = Threading.defaultContext): CancellableFuture[T] =
    new Cancellable(promise)

  /** Creates an empty cancellable future that will finish its execution with success after the given time.
    * Typically used together with `map` or a similar method to execute computation with a delay.
    *
    * @param duration The duration after which the future finishes its execution
    * @param ec The execution context
    * @return A new cancellable future of `Unit` which will finish with success after the given time
    */
  def delay(duration: FiniteDuration)(using ec: ExecutionContext = Threading.defaultContext): CancellableFuture[Unit] =
    if duration <= Duration.Zero then successful(())
    else
      val p = Promise[Unit]()
      val task = schedule(() => p.trySuccess(()), duration.toMillis)
      new Cancellable(p).onCancel(task.cancel())

  /** Creates an empty cancellable future which will repeat the mapped computation every given `duration`
    * until cancelled. The first computation is executed with `duration` delay. If the operation takes
    * longer than `duration` it will not be cancelled after the given time, but also the ability to cancel
    * it will be lost.
    *
    * @param duration The initial delay and the consecutive time interval between repeats.
    * @param body A task repeated every `duration`.
    * @return A cancellable future representing the whole repeating process.
    */
  def repeat(duration: Duration)(body: => Unit)(using ec: ExecutionContext = Threading.defaultContext): CancellableFuture[Unit] =
    if duration <= Duration.Zero then
      successful(())
    else
      new Cancellable(Promise[Unit]()):
        @volatile private var currentTask: Option[TimerTask] = None
        startNewTimeoutLoop()

        private def startNewTimeoutLoop(): Unit =
          currentTask = Some(schedule(
            () => { body; startNewTimeoutLoop() },
            duration.toMillis
          ))

        override def cancel(): Boolean =
          currentTask.foreach(_.cancel())
          currentTask = None
          super.cancel()

  private lazy val timer: Timer = new Timer()

  private def schedule(f: () => Any, delay: Long): TimerTask =
    new TimerTask {
      override def run(): Unit = f()
    }.tap {
      timer.schedule(_, delay)
    }

  /** A utility method that combines `delay` with `map`.
    * Creates a cancellable future that will execute the given `body` block after the given time.
    *
    * @param duration The duration after which the future finishes its execution
    * @param body A block of code which will be run after the `duration`
    * @tparam T the type of the result returned by `body`
    * @param ec The execution context
    * @return A new cancellable future of `T`
    */
  def delayed[T](duration: FiniteDuration)(body: => T)(using ec: ExecutionContext = Threading.defaultContext): CancellableFuture[T] =
    if duration <= Duration.Zero then CancellableFuture(body)
    else delay(duration).map { _ => body }

  /** Creates an already completed `CancellableFuture[T]` with the specified result.
    *
    * @param result The result of the completed future
    * @tparam T The type of the `result`
    * @return An already successfully completed **un**cancellable future
    */
  def successful[T](result: T): CancellableFuture[T] = new Uncancellable[T](Future.successful(result))

  /** Creates an already failed `CancellableFuture[T]` with the given throwable as the failure reason.
    *
    * @param th A `Throwable` being the reason of why the future is failed
    * @tparam T The type of what the future would return had it been successful
    * @return An already failed **un**cancellable future
    */
  def failed[T](th: Throwable): CancellableFuture[T] = new Uncancellable[T](Future.failed(th))

  /** Creates an already cancelled `CancellableFuture[T]`.
    *
    * @tparam T The type of what the future would return had it been successful
    * @return An already cancelled **un**cancellable future
    */
  def cancelled[T](): CancellableFuture[T] = failed(CancelException)

  /** Creates a new `CancellableFuture[Iterable[T]]` from a Iterable of `Iterable[T]`.
    * The original futures are executed asynchronously, but there are some rules that control them:
    * 1. All original futures need to succeed for the resulting one to succeed.
    * 2. Cancelling the resulting future will cancel all the original ones except those that are uncancellable.
    * 3a. If one of the original futures is cancelled (if it's cancellable) the resulting future will be cancelled
    *     and all other original futures (those that are not uncancellable) will be cancelled as well.
    * 3b. If one of the original futures fails for any other reason than cancellation, the resulting future will fail
    *     with the same reason as the original one and all other original futures will be cancelled (if they are not
    *     uncancellable) - i.e. they will not fail with the original reason but with `CancelException`.
    *
    * @param futures A collection (`Iterable`) of cancellable futures of the same type `T`
    * @param ec The execution context
    * @tparam T The type returned by each of original cancellable futures
    * @return A cancellable future with its result being a collection of results of original futures in the same order
    */
  def sequence[T](futures: Iterable[CancellableFuture[T]])(using ec: ExecutionContext): CancellableFuture[Iterable[T]] =
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
      case Failure(_) => futures.foreach(_.cancel())
      case _ =>
    }

    new Cancellable(promise)

  /** Transforms an `Iterable[T]` into a `CancellableFuture[Iterable[U]]` using
    * the provided function `T => CancellableFuture[U]`. Each cancellable future will be executed
    * asynchronously. If any of the original cancellable futures fails or is cancelled, the resulting
    * one will will fail immediately, but the other original ones will not.
    *
    * @see `sequence` for cancellation rules
    *
    * @param in A collection (`Iterable`) of input elements of the same type `T`
    * @param f A function that for each input element will create a new cancellable future of the type `U`
    * @param ec The execution context
    * @tparam T The type of input elements
    * @tparam U The type of the result of cancellable futures created by `f`
    * @return A cancellable future with its result being a collection of results of futures created by `f`
    */
  def traverse[T, U](in: Iterable[T])(f: T => CancellableFuture[U])
                    (using ec: ExecutionContext): CancellableFuture[Iterable[U]] =
    sequence(in.map(f))

  /** Transforms an `Iterable[T]` into a `CancellableFuture[Iterable[U]]` using
    * the provided function `T => CancellableFuture[U]`. Each cancellable future will be executed
    * synchronously. If any of the original cancellable futures fails or is cancelled, the resulting one
    * also fails and no consecutive original futures will be executed.
    *
    * Cancelling the resulting future will cancel the one which is executed at the moment and it will prevent all
    * the consecutive original futures from being executed.
    *
    * @see `sequence` for cancellation rules
    *
    * @param in A collection (`Iterable`) of input elements of the same type `T`
    * @param f A function that for each input element will create a new cancellable future of the type `U`
    * @param ec The execution context
    * @tparam T The type of input elements
    * @tparam U The type of the result of cancellable futures created by `f`
    * @return A cancellable future with its result being a collection of results of futures created by `f`
    */
  def traverseSequential[T, U](in: Iterable[T])(f: T => CancellableFuture[U])
                              (using ec: ExecutionContext): CancellableFuture[Iterable[U]] =
    def processNext(remaining: Iterable[T], acc: List[U] = Nil): CancellableFuture[Iterable[U]] =
      if remaining.isEmpty then CancellableFuture.successful(acc.reverse)
      else f(remaining.head).flatMap(res => processNext(remaining.tail, res :: acc))

    processNext(in)

  /** Creates a new `CancellableFuture[(T, U)]` from two original cancellable futures, one of the type `T`,
    * and one of the type `U`. The new future will fail or be cancelled if any of the original ones fails.
    * Cancelling the new future will fail both original ones.
    *
    * @param f1 The first of two original cancellable futures, with the result of type `T`
    * @param f2 The second of two original cancellable futures, with the result of type `U`
    * @param ec The execution context
    * @tparam T The type of the result of the first of original cancellable futures
    * @tparam U The type of the result of the second of original cancellable futures
    * @return A new cancellable future with its result being a tuple of results of both original futures
    */
  def zip[T, U](f1: CancellableFuture[T], f2: CancellableFuture[U])(using ec: ExecutionContext): CancellableFuture[(T, U)] =
    val p = Promise[(T, U)]()

    p.completeWith((for r1 <- f1; r2 <- f2 yield (r1, r2)).future)

    new Cancellable(p):
      override def cancel(): Boolean =
        if super.cancel() then
          Future {
            f1.cancel()
            f2.cancel()
          }(ec)
          true
        else false

  /** Turns one or more of cancellable futures into "uncancellable" - entities of the `Uncancellable` subsclass of `CancellableFuture`.
    *
    * @see [[Uncancellable]] for details
    *
    * @param futures One or more cancellable futures you want to make sure won't get cancelled
    * @tparam T The type of the result of the original cancellable futures
    * @return A collection of copies of cancellable futures which are **un**cancellable
    */
  def toUncancellable[T](futures: CancellableFuture[T]*): Iterable[CancellableFuture[T]] = futures.map(_.toUncancellable)

/** `CancellableFuture` is an object that for all practical uses works like a future but enables the user to cancel the operation.
  * A cancelled future fails with `CancellableFuture.CancelException` so the subscriber can differentiate between this and other
  * failure reasons. It is impossible to cancel a future if it is already completed or if it is uncancellable.
  *
  * @see https://github.com/wireapp/wire-signals/wiki/Overview
  * @see `Uncancellable` for details on uncancellable futures
  */
abstract class CancellableFuture[+T](using ec: ExecutionContext = Threading.defaultContext) extends Awaitable[T] { self =>
  import CancellableFuture._

  /** Gives direct access to the underlying future. */
  def future: Future[T]

  /** Returns if the future is actually cancellable (not completed and not uncancellable)
    * @see `Uncancellable` for details on uncancellable futures
    */
  def isCancellable: Boolean

  /** Creates a copy of this future that cannot be cancelled
    * @see `Uncancellable` for details on uncancellable futures
    */
  def toUncancellable: CancellableFuture[T]

  /** Tries to fails the future with the given exception as the failure's reason.
    *
    * @param th The reason for the failure
    * @return `true` if the future was cancelled, `false` if it was not possible to cancel it
    */
  def fail(th: Throwable): Boolean

  /** Adds a callback for when the future is cancelled (but not failed for any other reason).
    * There can be more than one `onCancel` callback.
    * Returns the reference to itself so it can be chained with other `CancellableFuture` methods.
    *
    * @param body The callback code
    * @return The reference to itself
    */
  def onCancel(body: => Unit): CancellableFuture[T] =
    if isCancellable then
      future.onComplete {
        case Failure(CancelException) => body
        case _ =>
      }
    this

  /** If the future is actually cancellable, adds a timeout after which this future will be cancelled.
    * In theory, it's possible to add more than one timeout - the shortest one will cancel all others.
    * Returns the reference to itself so it can be chained with other `CancellableFuture` methods.
    *
    * @param timeout A time interval after which this future is cancelled if it's cancellable
    * @return The reference to itself
    */
  final def addTimeout(timeout: FiniteDuration): CancellableFuture[T] =
    if isCancellable then
      val f = CancellableFuture.delayed(timeout)(this.cancel())
      onComplete(_ => f.cancel())
    this

  /** Tries to cancel the future. If successful, the future fails with `CancellableFuture.CancelException`
    *
    * @return `true` if the future was cancelled, `false` if it was not possible to cancel it
    */
  def cancel(): Boolean = fail(CancelException)

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

  /** Creates a new cancellable future by applying the `f` function to the successful result
    * of this one. If this future is completed with an exception then the new future will
    * also contain this exception. If the operation after the cancelling was provided,
    * it will be carried over to the new cancellable future.
    * If you cancel this future,
    *
    * @see `Future.map` for more - this is its equivalent for `CancellableFuture`
    *
    * @param f The function transforming the result type of this future into the result type of the new one
    * @param executor The execution context in which the new future will be executed, by default it will be
    *                 the execution context of this future
    * @tparam U The result type of `f`
    * @return A new cancellable future with the result type `U` being the outcome of the function `f` applied to
    *         the result type of this future
    */
  final def map[U](f: T => U)(using executor: ExecutionContext = ec): CancellableFuture[U] =
    val p = Promise[U]()
    @volatile var cancelSelf: Option[() => Unit] = Some(() => Future(self.cancel())(executor))

    future.onComplete { v =>
      cancelSelf = None
      p.tryComplete(v.flatMap(res => Try(f(res))))
    }(executor)

    new Cancellable(p):
      override def cancel(): Boolean =
        if super.cancel() then
          cancelSelf.foreach(_())
          true
        else false

  /** Creates a new cancellable future by applying the predicate `p` to the current one. If the original future
    * completes with success, but the result does not satisfies the predicate, the resulting future will fail with
    * a `NoSuchElementException`.
    *
    * @see `Future.filter` for more - this is its equivalent for `CancellableFuture`
    *
    * @param p a predicate function returning a boolean
    * @param executor The execution context in which the new future will be executed, by default it will be
    *                 the execution context of this future
    * @return a new cancellable future that will finish with success only if the predicate is satisfied
    */
  final def filter(p: T => Boolean)(using executor: ExecutionContext = ec): CancellableFuture[T] =
    flatMap {
      case res if p(res) => CancellableFuture.successful(res)
      case res => CancellableFuture.failed(new NoSuchElementException(s"CancellableFuture.filter predicate is not satisfied, the result is $res"))
    }

  /** An alias for `filter`, used by for-comprehensions. */
  final def withFilter(p: T => Boolean)(using executor: ExecutionContext = ec): CancellableFuture[T] = filter(p)

  /** Creates a new cancellable future by mapping the value of the current one, if the given partial function
    * is defined at that value. Otherwise, the resulting cancellable future will fail with a `NoSuchElementException`.
    *
    * @see `Future.collect` for more - this is its equivalent for `CancellableFuture`
    *
    * @param pf A partial function which will be applied to the result of the original future if that result belongs to
    *           a subset for which the partial function is defined
    * @param executor The execution context in which the new future will be executed, by default it will be
    *                 the execution context of this future
    * @tparam U The result type of the partial function `pf`
    * @return A new cancellable future that will finish with success only if the partial function is applied
    */
  final def collect[U](pf: PartialFunction[T, U])(using executor: ExecutionContext = ec): CancellableFuture[U] =
    flatMap {
      case r if pf.isDefinedAt(r) => CancellableFuture(pf.apply(r))
      case t => CancellableFuture.failed(new NoSuchElementException(s"CancellableFuture.collect partial function is not defined at $t"))
    }

  /** Creates a new cancellable future by applying a function to the successful result of this future, and returns
    * the result of the function as the new future. If this future fails or is cancelled, the cancel operation and
    * the result is carried over.
    *
    * @see `Future.flatMap` for more - this is its equivalent for `CancellableFuture`
    *
    * @param f A function which will be applied to the result of the original future if that result belongs to
    *           a subset for which the partial function is defined
    * @param executor The execution context in which the new future will be executed, by default it will be
    *                 the execution context of this future
    * @tparam U The result type of the function `f`
    * @return A new cancellable future that will finish with success only if the partial function is applied
    */
  final def flatMap[U](f: T => CancellableFuture[U])(using executor: ExecutionContext = ec): CancellableFuture[U] =
    val p = Promise[U]()
    @volatile var cancelSelf: Option[() => Unit] = Some(() => self.cancel())

    self.future.onComplete { res =>
      cancelSelf = None
      if !p.isCompleted then res match
        case f: Failure[_] => p.tryComplete(f.asInstanceOf[Failure[U]])
        case Success(v) =>
          Try(f(v)) match
            case Success(future) =>
              cancelSelf = Some(() => future.cancel())
              future.onComplete { res =>
                cancelSelf = None
                p.tryComplete(res)
              }
              if p.isCompleted then future.cancel()
            case Failure(t) =>
              p.tryFailure(t)
    }

    new Cancellable(p):
      override def cancel(): Boolean =
        if super.cancel() then
          Future(cancelSelf.foreach(_ ()))(executor)
          true
        else false

  /** Creates a new cancellable future that will handle any matching throwable that this future might contain.
    * If there is no match, or if this future contains a valid result then the new future will contain the same.
    * Works also if the current future is cancelled.
    *
    * @see `Future.recover` for more - this is its equivalent for `CancellableFuture`
    *
    * @param pf A partial function which will be applied to the `Throwable` returned as the failure reason of
    *           the original future if that result belongs to a subset for which the partial function is defined.
    *           (For example, you can define a partial function that works only on a specific type of exception,
    *           i.e. on a subclass of `Throwable`, and for other exceptions the future should really fail).
    * @param executor The execution context in which the new future will be executed, by default it will be
    *                 the execution context of this future
    * @tparam U The result type of the partial function `pf` and the result type of the new future if `pf` is defined
    *           for the `Throwable` returned by the original future
    * @return A new cancellable future that will finish with success only if the partial function is applied
    */
  inline final def recover[U >: T](pf: PartialFunction[Throwable, U])(using executor: ExecutionContext = ec): CancellableFuture[U] =
    recoverWith(pf.andThen(CancellableFuture.successful(_)))

  /** Creates a new cancellable future that will handle any matching throwable that the current one might contain by
    * assigning it a value of another future. Works also if the current future is cancelled. If there is no match,
    * or if this cancellable future contains a valid result, then the new future will contain the same result.
    *
    * @see `Future.recoverWith` for more - this is its equivalent for `CancellableFuture`
    *
    * @param pf A partial function which will be applied to the `Throwable` returned as the failure reason of
    *           the original future if that result belongs to a subset for which the partial function is defined.
    *           (For example, you can define a partial function that works only on a specific type of exception,
    *           i.e. on a subclass of `Throwable`, and for other exceptions the future should really fail).
    * @param executor The execution context in which the new future will be executed, by default it will be
    *                 the execution context of this future
    * @tparam U The result type of the partial function `pf` and the result type of the new future if `pf` is defined
    *           for the `Throwable` returned by the original future
    * @return A new cancellable future that will finish with success only if the partial function is applied
    */
  final def recoverWith[U >: T](pf: PartialFunction[Throwable, CancellableFuture[U]])
                               (using executor: ExecutionContext = ec): CancellableFuture[U] =
    val p = Promise[U]()
    @volatile var cancelSelf: Option[() => Unit] = Some(() => self.cancel())

    future.onComplete { res =>
      cancelSelf = None
      if !p.isCompleted then res match
        case Failure(t) if pf.isDefinedAt(t) =>
          val future = pf.applyOrElse(t, (_: Throwable) => this)
          cancelSelf = Some(() => future.cancel())
          future.onComplete { res =>
            cancelSelf = None
            p.tryComplete(res)
          }
          if p.isCompleted then future.cancel()
        case other =>
          p.tryComplete(other)
    }

    new Cancellable(p):
      override def cancel(): Boolean =
        if super.cancel() then
          Future(cancelSelf.foreach(_ ()))(executor)
          true
        else false

  /** Flattens a nested cancellable future.
    * If we have a cancellable future `val cf: CancellableFuture[ CancellableFuture[U] ]` with the result of `cf` is
    * another cancellable future - i.e. after executing the original one we will get another future and then we will
    * execute that, and only then get the result of type `U` - then we can use `CancellableFuture.flatten` to merge
    * these two steps into one. As a result, we will have one cancellable future which after successful execution will
    * give us a result of type `U`.
    *
    * @see `Future.flatten` for more - this is its equivalent for `CancellableFuture`
    *
    * @param executor The execution context in which the new future will be executed, by default it will be
    *                 the execution context of this future
    * @tparam U The result type of the nested future and the result type of the resulting future
    * @return A new cancellable future made from flattening the original one
    */
  inline final def flatten[U](using evidence: T <:< CancellableFuture[U], executor: ExecutionContext = ec): CancellableFuture[U] =
    flatMap(x => x)

  /** Creates a new CancellableFuture from the current one and the provided one. The new future completes with success
    * only if both original futures complete with success. If any of the fails or is cancelled, the resulting future
    * fails or is cancelled as well. If the user cancels the resulting future, both original futures are cancelled.
    *
    * @see `Future.zip` for more - this is its equivalent for `CancellableFuture`
    *
    * @param other The other cancellable future, with the result of the type `U`, that will be zipped with this one
    * @param executor The execution context in which the new future will be executed, by default it will be
    *                 the execution context of this future
    * @tparam U the result type of the other future
    * @return A new cancellable future with the result type being a tuple `(T, U)` where `T` is the result type of this future
    */
  inline final def zip[U](other: CancellableFuture[U])(using executor: ExecutionContext = ec): CancellableFuture[(T, U)] =
    CancellableFuture.zip(self, other)

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

  /** Registers the cancellable future in the given event context. When the event context is stopped, the future
    * will be cancelled. The subscription is also returned so it can be managed manually.
    *
    * @see `EventContext`
    *
    * @param eventContext The event context which this future will be subscribed to
    * @return A `Subscription` representing the connection between the event context and the future.
    *         You can use it to cancel the future manually, even if the event context hasn't stopped yet.
    */
  final def withAutoCanceling(using eventContext: EventContext = EventContext.Global): Subscription =
    new BaseSubscription(WeakReference(eventContext)) {
      override def onUnsubscribe(): Unit =
        cancel()
        eventContext.unregister(this)

      override def onSubscribe(): Unit = {}
    }.tap(eventContext.register)
}

/** A subclass of `CancellableFuture` which represents a subset of cancellable futures which are actually cancellable.
  * I know it means the name of the parent class is misleading, since not all cancellable futures are cancellable, but,
  * well, naming is a hard problem in computer science.
  * The reasoning here goes like this: An actually cancellable `CancellableFuture` is a wrapper over a `Promise`, not
  * a `Future`. We create a promise and try to fulfill it with the code given to us in one of the constructor methods.
  * If we succeed, the cancellable future will behave just as a regular one. But thanks to that we hold a promise,
  * we are able to cancel the ongoing execution of that piece of code by calling `tryFailure` on the promise.
  *
  * However, in some situations cancelling will not be possible. One is of course that the future may already be completed -
  * with success or with failure (including that it might have been already cancelled). This is why the `cancel` method
  * returns a boolean - we can't be always 100% sure that calling it will actually cancel the future.
  *
  * But there is also another possibility. Because of how ubiquitous futures are in the Scala standard library, it makes
  * sense to be able to wrap them in cancellable futures so that they can be used in the same code with the actually
  * cancellable futures. After all, if we're on the happy path, the two behave the same. The only difference appears
  * only when we try to cancel such a "cancellable" future - we don't have access to the parent promise of that future,
  * so we can't cancel it. Calling `cancel` on such a future will always return `false`, a callback registered with
  * `onCancel` will be ignored, etc.
  *
  * The last case is an inverse of the second one: Sometimes we have an actually cancellable future but we want to
  * make sure that it won't be cancelled in a given piece of code. Imagine a situation when we wait for a result of
  * complex computations which are modelled as a cancellable future. The computations may be cancelled by one class
  * that manages the computations, but we also give a reference to that cancellable future to a few other classes in
  * the program, so that when the computations finish, those classes will be immediately informed, get the result, and
  * use it. But they shouldn't be able to cancel the original computations - even if the given class becomes disinterested
  * in the result, it should not be able to stop the computations for all others.
  * In such case, we can use the method `toUncancellable` - it will give us an `Uncancellable` wrapped over
  * the `promise.future` of our original `Cancellable` future.
  *
  * @param promise The promise a new cancellable future wraps around
  * @param ec The execution context in which the future will be executed. By default it's the default context set
  *           in the `Threading` class
  * @tparam T The result type of the cancellable future
  */
class Cancellable[+T](promise: Promise[T])
                     (using ec: ExecutionContext = Threading.defaultContext) extends CancellableFuture[T]:
  override val future: Future[T] = promise.future

  override def fail(ex: Throwable): Boolean = promise.tryFailure(ex)

  override def toUncancellable: CancellableFuture[T] = new Uncancellable[T](future)(using ec)

  override def isCancellable: Boolean = !future.isCompleted

/** A subclass of `CancellableFuture` which represents a subset of cancellable futures which are actually **un**cancellable.
  * I know it means the name of the parent class is misleading, since not all cancellable futures are cancellable, but,
  * well, naming is a hard problem in computer science.
  * The reasoning here goes like this: An actually cancellable `CancellableFuture` is a wrapper over a `Promise`, not
  * a `Future`. We create a promise and try to fulfill it with the code given to us in one of the constructor methods.
  * If we succeed, the cancellable future will behave just as a regular one. But thanks to that we hold a promise,
  * we are able to cancel the ongoing execution of that piece of code by calling `tryFailure` on the promise.
  *
  * However, in some situations cancelling will not be possible. One is of course that the future may already be completed -
  * with success or with failure (including that it might have been already cancelled). This is why the `cancel` method
  * returns a boolean - we can't be always 100% sure that calling it will actually cancel the future.
  *
  * But there is also another possibility. Because of how ubiquitous futures are in the Scala standard library, it makes
  * sense to be able to wrap them in cancellable futures so that they can be used in the same code with the actually
  * cancellable futures. After all, if we're on the happy path, the two behave the same. The only difference appears
  * only when we try to cancel such a "cancellable" future - we don't have access to the parent promise of that future,
  * so we can't cancel it. Calling `cancel` on such a future will always return `false`, a callback registered with
  * `onCancel` will be ignored, etc.
  *
  * The last case is an inverse of the second one: Sometimes we have an actually cancellable future but we want to
  * make sure that it won't be cancelled in a given piece of code. Imagine a situation when we wait for a result of
  * complex computations which are modelled as a cancellable future. The computations may be cancelled by one class
  * that manages the computations, but we also give a reference to that cancellable future to a few other classes in
  * the program, so that when the computations finish, those classes will be immediately informed, get the result, and
  * use it. But they shouldn't be able to cancel the original computations - even if the given class becomes disinterested
  * in the result, it should not be able to stop the computations for all others.
  * In such case, we can use the method `toUncancellable` - it will give us an `Uncancellable` wrapped over
  * the `promise.future` of our original `Cancellable` future.
  *
  * @param future The future a new **un**cancellable future wraps around
  * @param ec The execution context in which the future will be executed. By default it's the default context set
  *           in the `Threading` class
  * @tparam T The result type of the cancellable future
  */
final class Uncancellable[+T](override val future: Future[T])
                             (using ec: ExecutionContext = Threading.defaultContext) extends CancellableFuture[T]:
  override def fail(ex: Throwable): Boolean = false

  override def toUncancellable: CancellableFuture[T] = this

  override def isCancellable: Boolean = false
