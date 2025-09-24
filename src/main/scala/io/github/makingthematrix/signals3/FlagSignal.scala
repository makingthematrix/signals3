package io.github.makingthematrix.signals3

final class FlagSignal extends Signal(Some(false)) {
  inline def done(): Unit = publish(true)
  inline def state: Boolean = value.contains(true)
  inline def onDone(body : => Unit): Unit = this.foreach(_ => body)
}
