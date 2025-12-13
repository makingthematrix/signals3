package io.github.makingthematrix.signals3

import java.util.concurrent.atomic.AtomicInteger
import Stream.EventSubscriber

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object AggregatingSignal {
  /** Creates a new aggregating signal from the `loader` which will be used to compute the initial value of the signal,
    * a stream of events, and the `updater` function which will use those events to update the value of the signal.
    * The `loader` - which is a future - will be executed in the provided execution context or the default execution
    * context otherwise.
    *
    * @param loader A future used for computing the initial value of the signal. It's passed by name, so if it is created in
    *               the place of argument, it will be executed for the first time only when the first subscriber function
    *               is registered in the signal, or immediately if `disableAutowiring` is used.
    *               If a new event comes while the `loader` not yet finished, the event will be memorized and used to produce
    *               the first updated value right afterwards.
    * @param sourceStream  a stream publishing events which will be used to update the value of the signal.
    * @param updater A function combining the current value of the signal with a new event to produce the updated value.
    * @param ec The execution context in which the `loader` is executed (optional).
    * @tparam E The type of the update events.
    * @tparam V The type of the value held in the signal and the result of the `loader` execution.
    * @return A new aggregating signal with the value type `V`.
    */
  def apply[E, V](loader: () => Future[V], sourceStream: Stream[E], updater: (V, E) => V)
                 (using ec: ExecutionContext): AggregatingSignal[E, V]
    = new AggregatingSignal(loader, sourceStream, updater)
}

/** A signal which initializes its value by executing the `loader` future and then updates the value by composition of
  * the previous value and an event published in the associated `source` stream.
  * You may think of it as a more performance-efficient version of `RefreshingSignal`, useful when the `loader`
  * requires heavy computations but an update between one value and another is simple in comparison. For example:
  * ```
  * val loader: Future[ Vector[DBEntry] ] = fetchDBTableData()
  * val sourceStream: Stream[DBEntry] = newDBTableEntryStream()
  * val updater: (Vector[DBEntry], DBEntry) => Vector[DBEntry] = { (table, newEntry) => table :+ newEntry }
  * val signal = new AggregatingSignal(loader, sourceStream, updater)
  * ```
  * Here, the `loader` fetches the whole DB table, but if we know that the only change to that table is that new entries
  * can be added to it, we can avoid calling the `loader` every time the event comes. Instead, we can create the `updater`
  * function which will combine the current value of the signal (i.e. the in-memory cache of the DB table, created by
  * calling the `loader` only once, when the signal was initialized), with the new entry.
  *
  * @see [[RefreshingSignal]]
  *
  * @param loader A future used for computing the initial value of the signal. It's passed by name, so if it is created in
  *               the place of argument, it will be executed for the first time only when the first subscriber function
  *               is registered in the signal, or immediately if `disableAutowiring` is used.
  *               If a new event comes while the `loader` not yet finished, the event will be memorized and used to produce
  *               the first updated value right afterwards.
  * @param sourceStream  a stream publishing events which will be used to update the value of the signal.
  * @param updater A function combining the current value of the signal with a new event to produce the updated value.
  * @param ec The execution context in which the `loader` is executed.
  * @tparam E The type of the update events.
  * @tparam V The type of the value held in the signal and the result of the `loader` execution.
  */
final class AggregatingSignal[E, V](loader: () => Future[V], sourceStream: Stream[E], updater: (V, E) => V)
                                   (using ec: ExecutionContext)
  extends Signal[V] with EventSubscriber[E] {
  self =>
  private object valueMonitor

  private val loadId = new AtomicInteger(0)
  @volatile private var stash = Vector.empty[E]

  override protected[signals3] def onEvent(event: E, currentContext: Option[ExecutionContext]): Unit = valueMonitor.synchronized {
    if loadId.intValue() == 0 then
      value.foreach(v => self.setValue(Some(updater(v, event)), currentContext))
    else
      stash :+= event
  }

  private def startLoading(id: Int): Unit = loader().onComplete {
    case Success(s) if loadId.intValue() == id => valueMonitor.synchronized {
      self.setValue(Some(stash.foldLeft(s)(updater)), Some(ec))
      loadId.compareAndSet(id, 0)
      stash = Vector.empty
    }
    case Failure(_) if loadId.intValue() == id => valueMonitor.synchronized { self.stash = Vector.empty } // load failed
    case _ => // delegate is no longer the current one, discarding loaded value
  }(using ec)

  override def onWire(): Unit = {
    stash = Vector.empty
    sourceStream.subscribe(this) // important to subscribe before starting to load
    startLoading(loadId.incrementAndGet())
  }

  override def onUnwire(): Unit = {
    loadId.set(0)
    sourceStream.unsubscribe(this)
  }
}
