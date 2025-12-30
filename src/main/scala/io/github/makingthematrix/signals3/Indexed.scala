package io.github.makingthematrix.signals3

import java.util.concurrent.atomic.AtomicInteger

/** A trait that keeps track of the number of events published so far. */
trait Indexed {
  private val _counter: AtomicInteger = new AtomicInteger(0)

  /** The number of events published so far. */
  inline def counter: Int = _counter.get()

  /** Increments the counter by one. */
  inline protected def inc(): Unit = _counter.incrementAndGet()

  /** Increments the counter by one and returns the new value. */
  inline protected def incAndGet(): Int = _counter.incrementAndGet()

  /** Increments the counter by one and returns the previous value. */
  inline protected def getAndInc(): Int = _counter.getAndIncrement()
}
