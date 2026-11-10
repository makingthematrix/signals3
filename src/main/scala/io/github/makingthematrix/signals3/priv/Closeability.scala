package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Behaviors.Beh
import io.github.makingthematrix.signals3.Closeable

/**
  * Encapsulates logic for closing original streams/signals/c-futures, checking if they are closed, and calling
  * the registered `onClose` code (but only once, not once per the original source).
  */
private[signals3] trait Closeability(sources: Closeable*) extends Closeable {
  private var callOnCloseList: List[Beh] = Nil
  @volatile private var open = sources.length

  sources.foreach(_.onClose {
    synchronized {
      open -= 1
      if (open == 0) callOnCloseList.foreach(_.f())
    }
  })

  final override def closeAndCheck(): Boolean =
    sources.map(_.closeAndCheck()).forall(p => p)

  final override def isClosed: Boolean = sources.forall(_.isClosed)

  final override def onClose(beh: Beh): Unit =
    callOnCloseList ::= beh
}
