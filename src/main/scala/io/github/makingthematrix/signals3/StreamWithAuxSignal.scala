package io.github.makingthematrix.signals3

import Stream.EventSubscriber
import Signal.SignalSubscriber

import scala.concurrent.ExecutionContext

/** a stream coupled with an auxiliary signal.
  * You can use it if you want to repeat some computations based on the current value of the signal every time when an event
  * is published in the source stream.
  * ```
  * val aux = Signal[Int]()
  * val source = Stream[Unit]()
  *
  * val newStream = EventStreamWithAuxSignal(source, aux)
  * newStream.foreach { case (_, Option(n)) => /* ... */ }
  * ```
  * Here, `newStream` extends `Stream[Unit, Option[Int]]`.
  * The subscriber function registered in `newStream`` will be called every time a new unit event is published in `source`
  * and it will receive a tuple of the event and the current value of `aux`: `Some[Int]` if something was already published
  * in the signal, or `None` if it is not initialized yet.
  *
  * @param source The source stream used to trigger events in this stream. Every event of type `A` published in `source`
  *               will become the first part of the tuple published in this stream.
  * @param aux An auxiliary signal of values of the type `B`. Every time a new event is published in `source`, this stream will
  *            access the signal for its current value. The value (or lack of it) will become the second part of the tuple published
  *            in this stream.
  * @tparam A The type of events in the source stream.
  * @tparam B The type of values in the auxiliary signal.
  */
final class StreamWithAuxSignal[A, B](source: Stream[A], aux: Signal[B]) extends Stream[(A, Option[B])]:
  protected[this] val subscriber: EventSubscriber[A] =
    (event: A, sourceContext: Option[ExecutionContext]) => dispatch((event, aux.currentValue), sourceContext)

  protected[this] lazy val auxSubscriber: SignalSubscriber =
    (_: Option[ExecutionContext]) => ()

  override protected def onWire(): Unit =
    source.subscribe(subscriber)
    aux.subscribe(auxSubscriber)

  override protected[signals3] def onUnwire(): Unit =
    source.unsubscribe(subscriber)
    aux.unsubscribe(auxSubscriber)

object StreamWithAuxSignal:
  def apply[A, B](source: Stream[A], aux: Signal[B]): StreamWithAuxSignal[A, B] =
    new StreamWithAuxSignal(source, aux)