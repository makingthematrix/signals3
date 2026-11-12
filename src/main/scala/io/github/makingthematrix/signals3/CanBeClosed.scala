package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Behaviors.{Beh, Fail}
import io.github.makingthematrix.signals3.priv.BehaviorRegistry

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
import scala.util.chaining.scalaUtilChainingOps

/**
 * A common supertrait for [[CloseableFuture]] and all streams and signals that can be closed at some point,
 * either by the user or by internal logic.
 * Implements automatic cleanup and resource management through the closeable lifecycle.
 */
trait CanBeClosed {
  private val closed: AtomicBoolean = new AtomicBoolean(false)
  private val registry = BehaviorRegistry("Close")

  /**
   * Checks if the stream/signal is already closed.
   * @return `true` if the stream/signal is already closed, `false` if it's not. Note that if the stream/signal failed,
   *         the result can be unreliable.
   */
  def isClosed: Boolean = closed.get()

  protected def closeAndCheck(): Boolean =
    if (!closed.getAndSet(true)) {
      if (runOnClose()) true
      else {
        closed.set(false)
        false
      }
    } else false

  protected def runOnClose(): Boolean = registry.run()

  /**
   * Registers a block of code that should be called exactly once when the closeable is being closed.
   * @param beh Logic that is going to be executed when the closeable is closed.
   */
  protected def onClose(beh: Beh): Unit = registry.add(beh)
	inline final def onClose(body: => Any): Unit = registry.add(body)
	inline final def onClose(id: String, f: () => Any): Unit = registry.add(id, f)
	inline final def onCloseById(id: String): Boolean = registry.addById(id)

	protected def onCloseFailed(fail: Fail): Unit = registry.addFail(fail)
	def onCloseFailed(f: Throwable => Any): Unit =
		registry.addFail(f)
	inline final def onCloseFailed(id: String, f: Throwable => Any): Unit = registry.addFail(id, f)
	inline final def onCloseFailedById(id: String): Boolean = registry.addFailById(id)

	final def isClosedSignal(using ExecutionContext): Signal[Boolean] = FlagSignal().tap { signal =>
		if (isClosed) signal.set() else {
			onClose(signal.set())
			onCloseFailed(_ => signal.clear())
		}
	}
}
