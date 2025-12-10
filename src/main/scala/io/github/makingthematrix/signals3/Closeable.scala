package io.github.makingthematrix.signals3

/**
  * A stream or a signal can be closeable, meaning that it can be closed and after that it will not publish new events
  * anymore. Every [[GeneratorStream]] and [[GeneratorSignal]] is [[Closeable]] which allows for stopping them when
  * they're no longer needed, but you can make any new stream or signal inherit [[Closeable]] and implement the required
  * logic. 
  * [[Closeable]] extends [[java.lang.AutoCloseable]] so in theory it can be used in Java `try-with-resources`.
  * 
  * @see [[ProxyStream]] and [[ProxySignal]] for examples.
  */
trait Closeable extends java.lang.AutoCloseable with CanBeClosed {
  override def closeAndCheck(): Boolean = super.closeAndCheck()

  /**
    * A version of `closeAndCheck()` which ignores the boolean result.
    * If the closeable is used in try-with-resources, this method will be called automatically.
    */
  final inline def close(): Unit = closeAndCheck()
}

object Closeable {
  /**
    * A type alias for a closeable stream.
    * @tparam E The event type of the stream.
    */
  type CloseableStream[E] = Stream[E] & Closeable
  /**
    * A type alias for a closeable signal.
    * @tparam V The value type of the signal.
    */
  type CloseableSignal[V] = Signal[V] & Closeable
}
