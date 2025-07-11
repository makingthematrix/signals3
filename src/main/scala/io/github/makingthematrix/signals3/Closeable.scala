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
trait Closeable extends java.lang.AutoCloseable:
  /**
    * Tries to close the stream/signal and returns if it worked.
    * @return Should return `true` if it was possible to close the stream/signal, `false` if it was impossible 
    *         to close it or **if the stream/signal was already closed**.
    */
  def closeAndCheck(): Boolean

  /**
    * Checks if the stream/signal is already closed.
    * @return `true` if the stream/signal is already closed, `false` if it's not. Note that if the stream/signal failed,
    *         the result can be unreliable.
    */
  def isClosed: Boolean

  /**
    * Registers a block of code that should be called exactly once when the closeable is being closed.
    * @param body
    */
  def onClose(body: => Unit): Unit

  /**
    * A version of `closeAndCheck()` which ignores the boolean result.
    * If the closeable is used in try-with-resources, this method will be called automatically.
    */
  override final def close(): Unit = closeAndCheck()

object Closeable:
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
