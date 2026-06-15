package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.CloseableFuture

import scala.concurrent.{ExecutionContext, Future, Promise}

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
  * @tparam T The result type of the closeable future
  */
private[signals3] class ActuallyCloseable[+T](promise: Promise[T])
                                             (using ExecutionContext) extends CloseableFuture[T]{
  override val future: Future[T] = promise.future

  override def fail(ex: Throwable): Boolean = promise.tryFailure(ex)

  override def toUncloseable: CloseableFuture[T] = new Uncloseable[T](future)

  override def isCloseable: Boolean = !future.isCompleted
}
