package io.makingthematrix.signals3

import java.util.concurrent.atomic.AtomicReference

import java.util.Random
import scala.concurrent.duration.{FiniteDuration, _}
import scala.annotation.tailrec
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.util.{Failure, Success, Try}

package object testutils {
  private val localRandom = new ThreadLocal[Random] {
    override def initialValue: Random = new Random
  }

  def random: Random = localRandom.get

  implicit class EnrichedInt(val a: Int) extends AnyVal {
    def times(f: => Unit): Unit = (1 to a).foreach(_ => f)
  }

  @tailrec
  def compareAndSet[A](ref: AtomicReference[A])(updater: A => A): A = {
    val current = ref.get
    val updated = updater(current)
    if (ref.compareAndSet(current, updated)) updated
    else compareAndSet(ref)(updater)
  }

  def withDelay[T](body: => T, delay: FiniteDuration = 300.millis)(implicit ec: ExecutionContext): CancellableFuture[T] =
    CancellableFuture.delayed(delay)(body)

  val DefaultTimeout: FiniteDuration = 5.seconds

  def result[A](future: Future[A])(implicit duration: FiniteDuration = DefaultTimeout): A = Await.result(future, duration)

  inline def await[A](future: Future[A])(implicit duration: FiniteDuration = DefaultTimeout): Unit = tryResult(future)

  def tryResult[A](future: Future[A])(implicit duration: FiniteDuration = DefaultTimeout): Try[A] =
    try {
      Try(result(future))
    } catch {
      case t: Throwable => Failure(t)
    }

  def waitForResult[V](signal: Signal[V], expected: V, timeout: FiniteDuration): Boolean = {
    val offset = System.currentTimeMillis()
    while (System.currentTimeMillis() - offset < timeout.toMillis) {
      Try(result(signal.head)(timeout)) match {
        case Success(obtained) if obtained == expected => return true
        case Failure(_: TimeoutException) => return false
        case Failure(ex) =>
          println(s"waitForResult failed waiting for $expected, with exception: ${ex.getMessage} (${ex.getClass.getCanonicalName})")
        case _ =>
      }
      Thread.sleep(100)
    }
    false
  }

  def waitForResult[V](signal: Signal[V], expected: V): Boolean = waitForResult(signal, expected, DefaultTimeout)

  def waitForResult[E](stream: EventStream[E], expected: E, timeout: FiniteDuration): Boolean = {
    val offset = System.currentTimeMillis()
    while (System.currentTimeMillis() - offset < timeout.toMillis) {
      Try(result(stream.next)(timeout)) match {
        case Success(obtained) if obtained == expected => return true
        case Failure(_: TimeoutException) => return false
        case Failure(ex) =>
          println(s"waitForResult failed waiting for $expected, with exception: ${ex.getMessage} (${ex.getClass.getCanonicalName})")
        case _ =>
      }
      Thread.sleep(100)
    }
    false
  }

  def waitForResult[E](stream: EventStream[E], expected: E): Boolean = waitForResult(stream, expected, DefaultTimeout)

  /**
    * Very useful for checking that something DOESN'T happen (e.g., ensure that a signal doesn't get updated after
    * performing a series of actions)
    */
  def awaitAllTasks(implicit timeout: FiniteDuration = DefaultTimeout, dq: DispatchQueue): Unit = {
    if (!tasksCompletedAfterWait)
      throw new TimeoutException(s"Background tasks didn't complete in ${timeout.toSeconds} seconds")
  }

  def tasksRemaining(implicit dq: DispatchQueue): Boolean = dq.hasRemainingTasks

  private def tasksCompletedAfterWait(implicit timeout: FiniteDuration = DefaultTimeout, dq: DispatchQueue) = {
    val start = System.currentTimeMillis()
    val before = start + timeout.toMillis
    while(tasksRemaining && System.currentTimeMillis() < before) Thread.sleep(10)
    !tasksRemaining
  }
}
