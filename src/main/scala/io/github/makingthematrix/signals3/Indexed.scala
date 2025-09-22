package io.github.makingthematrix.signals3

trait Indexed {
  @volatile private var _counter = 0
  inline def counter: Int = _counter
  protected def inc(): Unit = _counter += 1
}
