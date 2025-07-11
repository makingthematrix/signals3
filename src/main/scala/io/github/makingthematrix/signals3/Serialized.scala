package io.github.makingthematrix.signals3

import scala.concurrent.Future
import scala.collection.immutable.Map

/** A utility object for serializing futures.
  *
  * The need for this functionality comes from the fact that we can't assume an event will be processed before the next one comes,
  * but sometimes it is also crucial to process the next event only after the first one is done. In such case, the user can use
  * one of the methods of `Serialized` to schedule processing the first event, tag it with a key, and then simply use the same key
  * to schedule processing of the second event. The user doesn't have to know if the first event was already processed or not -
  * if yes, processing of the second will start immediately, if not, the processing (in the form of a future or a [[CloseableFuture]])
  * will be attached to the end of the ongoing processing and triggered only after it's done.
  */
object Serialized:
  private given dispatcher: DispatchQueue = SerialDispatchQueue("Serialized")

  private var locks = Map[String, Future[?]]()

  /** Serializes a closeable future.
    * Please note that if a new future is added to one which is not yet completed, and that one will be cancelled
    * afterwards, the new one won't be processed.
    *
    * @param key Any string that will serve as a tag for adding next futures to the end of the ongoing one.
    * @param body The closeable future to be run. The argument is passed lazily - it will be run immediately if there is
    *             no other future running on the same `key`, but otherwise it will be postponed until the previous one finishes.
    * @tparam T The result type of the closeable future.
    * @return A new closeable future representing the whole chain of operations which ends with the one which was just added.
    *         The user should refer to this one from now on instead of the original one.
    */
  def apply[T](key: String)(body: => CloseableFuture[T]): CloseableFuture[T] = dispatcher {
    val future = locks.get(key).fold(body) { lock =>
      CloseableFuture.lift(lock.recover { case _ => }).flatMap(_ => body)
    }
    val lock = future.future
    locks += ((key, lock))
    future.onComplete { _ => if locks.get(key).contains(lock) then locks -= key }
    future
  }.flatten

  /** Serializes a standard Scala future.
    * It is possible to add a standard future to a chain of closeable futures using the same key in both `apply` and `future` method calls.
    *
    * @param key Any string that will serve as a tag for adding next futures to the end of the ongoing one.
    * @param body The future to be run. The argument is passed lazily - it will be run immediately if there is no other future
    *             running on the same `key`, but otherwise it will be postponed until the previous one finishes.
    * @tparam T The result type of the future.
    * @return A new future representing the whole chain of operations which ends with the one which was just added.
    *         The user should refer to this one from now on instead of the original one.
    */
  def future[T](key: String)(body: => Future[T]): Future[T] =
    val future = locks.get(key).fold(body) { lock =>
      lock.recover { case _ => }.flatMap(_ => body)
    }
    locks += ((key, future))
    future.onComplete { _ => if locks.get(key).contains(future) then locks -= key }
    future
