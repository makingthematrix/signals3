package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Behaviors.TF
import io.github.makingthematrix.signals3.actors.MutableActor
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
  protected val closed: AtomicBoolean = new AtomicBoolean(false)
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

	protected def runOnClose[Msg, State](actor: MutableActor[Msg, State]): Boolean = registry.run(actor)

	protected def resetOnClose(): Unit = registry.reset()

  /**
   * Registers a block of code that should be called exactly once when the closeable is being closed.
   * The function is registered in [[Behaviors]] under a generated id and unregistered there after
   * a successful close.
   *
   * @param f The code to run when the closeable is closed.
   * @return The generated id of the registered function - save it to `Behaviors.unregister` the
   *         function manually, if you ever need to.
   */
  inline final def onClose(f: () => Any): Unit = registry.addOneShot(f)

	inline final def onClose[Msg, State](tf: TF[Msg, State]): Unit = registry.addOneShot(tf)

	/**
   * Registers an already registered close behavior by its id.
   *
   * @param id The id of a [[Behaviors]] behavior.
   * @return `true` if the id was added; `false` if there is no behavior registered under it.
   */
  inline final def onClose(id: String): Boolean = registry.addId(id)

  /**
   * Registers a failure behavior with a function taking the exception; the function is registered
   * in [[Behaviors]] under a generated id.
   *
   * @param f The function to run when the close fails.
   * @return The generated id of the registered function.
   */
  inline final def onCloseFailed(f: Throwable => Any): Unit = registry.addFailOneShot(f)

  /**
   * Registers an already registered failure behavior by its id.
   *
   * @param id The id of a [[Behaviors]] failure behavior.
   * @return `true` if the id was added; `false` if there is no failure behavior registered under it.
   */
  inline final def onCloseFailedId(id: String): Boolean = registry.addFailId(id)

  final def isClosedSignal(using ExecutionContext): Signal[Boolean] = FlagSignal().tap { signal =>
    if (isClosed) signal.set() else {
      onClose(() => signal.set())
      onCloseFailed(_ => signal.clear())
    }
  }

	inline final def removeOnClose(id: String): Unit = registry.remove(id)
}
