package io.github.makingthematrix.signals3

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
import scala.util.chaining.scalaUtilChainingOps

/**
 * A common supertrait for [[CloseableFuture]] and all streams and signals that can be closed at some point,
 * either by the user or by internal logic.
 */
trait CanBeClosed {
  private val closed: AtomicBoolean = new AtomicBoolean(false)

  /**
   * Checks if the stream/signal is already closed.
   * @return `true` if the stream/signal is already closed, `false` if it's not. Note that if the stream/signal failed,
   *         the result can be unreliable.
   */
  def isClosed: Boolean = closed.get()

  protected def closeAndCheck(): Boolean =
    if (!closed.getAndSet(true)) {
      callOnClose()
      true
    } else false

  /**
   * Registers a block of code that should be called exactly once when the closeable is being closed.
   * @param body Logic that is going to be executed when the closeable is closed.
   */
  def onClose(body: => Unit): Unit =
    _onClose = (() => body) :: _onClose

  private final inline def callOnClose(): Unit = _onClose.foreach(_())

  private var _onClose: List[() => Unit] = Nil

  /**
   * Returns a signal that works on a given [[ExecutionContext]]; it starts with the value set to `false` (unless it's
   * created after the closeable is already closed) and it will be set to `true` when the closeable is closed.
   * @param ec The execution context on which the signal will be executed.
   * @return A signal that will be set to `true` when the closeable is closed.
   */
  def isClosedSignal(using ExecutionContext): Signal[Boolean] =
    DoneSignal().tap { signal => if (isClosed) signal.done() else onClose(signal.done()) }

  /**
    * Triggers another instance when this one is being closed.
    * Example use case: A finite stream is chained with a constructor of another stream. When the finite stream ends,
    * only then the constructor is called and a new stream starts to work.
    * @param body The functionality that is being called when this CanBeClosed instance is closed
    * @tparam T The concrete type of the new CanBeClosed instance
    * @return The new CanBeClosed instance which starts to work when this one is closed
    *//*
  infix def next[T <: CanBeClosed](body: => T): T = {
    onClose(body)
    body
  }

  /* An alias to `next` */
  inline def >>[T <: CanBeClosed](body: => T): T = next(body)*/
}
