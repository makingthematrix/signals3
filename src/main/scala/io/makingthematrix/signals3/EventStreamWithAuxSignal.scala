package io.makingthematrix.signals3

import EventStreamWithAuxSignal.DoNothingSignalSubscriber
import EventStream.EventSubscriber
import Signal.SignalSubscriber

import scala.concurrent.ExecutionContext

object EventStreamWithAuxSignal {
  def apply[A, B](source: EventStream[A], aux: Signal[B]): EventStreamWithAuxSignal[A, B] = new EventStreamWithAuxSignal(source, aux)

  final class DoNothingSignalSubscriber extends SignalSubscriber {
    override def changed(currentContext: Option[ExecutionContext]): Unit = {}
  }
}

/** An event stream coupled with an auxiliary signal.
  * You can use it if you want to repeat some computations based on the current value of the signal every time when an event
  * is published in the source stream.
  * ```
  * val aux = Signal[Int]()
  * val source = EventStream[Unit]()
  *
  * val newStream = EventStreamWithAuxSignal(source, aux)
  * newStream.foreach { case (_, Option(n)) => /* ... */ }
  * ```
  * Here, `newStream` extends `EventStream[Unit, Option[Int]]`.
  * The subscriber function registered in `newStream`` will be called every time a new unit event is published in `source`
  * and it will receive a tuple of the event and the current value of `aux`: `Some[Int]` if something was already published
  * in the signal, or `None` if it is not initialized yet.
  *
  * @param source The source event stream used to trigger events in this event stream. Every event of type `A` published in `source`
  *               will become the first part of the tuple published in this stream.
  * @param aux An auxiliary signal of values of the type `B`. Every time a new event is published in `source`, this stream will
  *            access the signal for its current value. The value (or lack of it) will become the second part of the tuple published
  *            in this stream.
  * @tparam A The type of events in the source stream.
  * @tparam B The type of values in the auxiliary signal.
  */
class EventStreamWithAuxSignal[A, B](source: EventStream[A], aux: Signal[B]) extends EventStream[(A, Option[B])] {
  protected[this] val subscriber: EventSubscriber[A] = new EventSubscriber[A] {
    override protected[signals3] def onEvent(event: A, sourceContext: Option[ExecutionContext]): Unit = {
      dispatch((event, aux.currentValue), sourceContext)
    }
  }

  protected[this] lazy val auxSubscriber: SignalSubscriber = new DoNothingSignalSubscriber()

  override protected def onWire(): Unit = {
    source.subscribe(subscriber)
    aux.subscribe(auxSubscriber)
  }

  override protected[signals3] def onUnwire(): Unit = {
    source.unsubscribe(subscriber)
    aux.unsubscribe(auxSubscriber)
  }
}
