package io.github.makingthematrix.signals3

import scala.util.chaining.scalaUtilChainingOps

/**
 * A common supertrait for [[CloseableFuture]] and all streams and signals that can be closed at some point,
 * either by the user or by internal logic.
 */
protected[signals3] trait CanBeClosed {
  @volatile private var closed: Boolean = false

  /**
   * Checks if the stream/signal is already closed.
   * @return `true` if the stream/signal is already closed, `false` if it's not. Note that if the stream/signal failed,
   *         the result can be unreliable.
   */
  def isClosed: Boolean = closed

  protected def closeAndCheck(): Boolean = if (!closed) {
    closed = true
    callOnClose()
    true
  } else false

  /**
   * Registers a block of code that should be called exactly once when the closeable is being closed.
   * @param body
   */
  def onClose(body: => Unit): Unit =
    _onClose = (() => body) :: _onClose

  private final inline def callOnClose(): Unit = _onClose.foreach(_())

  private var _onClose: List[() => Unit] = Nil

  lazy val isClosedSignal: Signal[Boolean] =
    DoneSignal().tap { signal => if (isClosed) signal.done() else onClose(signal.done()) }
}
