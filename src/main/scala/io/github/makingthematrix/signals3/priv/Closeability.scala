package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Closeable

/**
  * Encapsulates logic for closing original streams/signals/c-futures, checking if they are closed, and calling
  * the registered `onClose` code (but only once, not once per the original source).
  *
  * Behaviors registered on this compound (through the `onClose` methods inherited from `CanBeClosed`)
  * are stored - as ids - in this object's own close registry and run once, when the last of the
  * original sources closes.
  *
  * The per-source wiring which counts the open sources is registered on each source as a ''one-shot''
  * close behavior: it fires exactly once - when that source closes - so its function is unregistered
  * from [[Behaviors]] after the source closes successfully. See `BehaviorRegistry` for the details.
  */
private[signals3] trait Closeability(sources: Closeable*) extends Closeable {
  @volatile private var open = sources.length

  sources.foreach(_.onCloseOneShot { () =>
    synchronized {
      open -= 1
      if (open == 0) runOnClose()
    }
  })

  final override def closeAndCheck(): Boolean =
    sources.map(_.closeAndCheck()).forall(p => p)

  final override def isClosed: Boolean = sources.forall(_.isClosed)
}
