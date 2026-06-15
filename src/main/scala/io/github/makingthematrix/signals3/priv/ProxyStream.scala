package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Stream

/** A superclass for all event streams which compose other event streams into one.
  *
  * @param sources A variable arguments list of event streams serving as sources of events for the resulting stream.
  * @tparam A The type of the events emitted by all the source streams.
  * @tparam E The type of the events emitted by the stream constructed from the sources.
  */
abstract private[signals3] class ProxyStream[A, E](sources: Stream[A]*) extends Stream[E] with StreamSubscriber[A] {
  /** When the first subscriber is registered in this stream, subscribe the stream to all its sources. */
  override protected[signals3] def onWire(): Unit = sources.foreach(_.subscribe(this))

  /** When the last subscriber is unregistered from this stream, unsubscribe the stream from all its sources. */
  override protected[signals3] def onUnwire(): Unit = sources.foreach(_.unsubscribe(this))
}
