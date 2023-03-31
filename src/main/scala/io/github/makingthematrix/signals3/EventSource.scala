package io.github.makingthematrix.signals3

import scala.concurrent.ExecutionContext
import scala.collection.immutable.Set

abstract class EventSource[E, S]:
  private object subscribersMonitor

  private[this] var autowiring = true
  @volatile private[this] var subscribers = Set.empty[S]

  /** This method will be called on creating the first subscription or on `disableAutoWiring`.
    * If the implementing class stashes intermediate computation, this should trigger their execution.
    */
  protected def onWire(): Unit

  /** This method will be called on removing the last subscription if `disableAutoWiring` was not called.
    */
  protected def onUnwire(): Unit

  /** Creates a [[Subscription]] to a function which will consume events in the given `ExecutionContext`.
    * In simpler terms: A subscriber is a function which will receive events from the event source. For every event,
    * the function will be executed in the given execution context - not necessarily the same as the one used for
    * emitting the event. This allows for easy communication between parts of the program working in different
    * execution contexts, e.g. the user interface and the database.
    *
    * The [[Subscription]] will be automatically enabled ([[Subscription.enable]]).
    *
    * @param ec An `ExecutionContext` in which the given function will be executed.
    * @param body A function which consumes the event
    * @param eventContext An [[EventContext]] which will register the [[Subscription]] for further management (optional)
    * @return A [[Subscription]] representing the created connection between the event source and the body function.
    */
  def on(ec: ExecutionContext)(body: E => Unit)(using eventContext: EventContext = EventContext.Global): Subscription

  /** Creates a [[Subscription]] to a function which will consume events in the same `ExecutionContext` as
    * the one in which the events are being emitted.
    *
    * @see [[EventSource.on]]
    *
    * The [[Subscription]] will be automatically enabled ([[Subscription.enable]]).
    * @param body A function which consumes the event
    * @param eventContext an [[EventContext]] which will register the [[Subscription]] for further management (optional)
    * @return a [[Subscription]] representing the created connection between the [[EventSource]] and the body function
    */
  def onCurrent(body: E => Unit)(using eventContext: EventContext = EventContext.Global): Subscription

  /** An alias for the `on` method with the default [[ExecutionContext]]. */
  inline final def foreach(body: E => Unit)
                          (using executionContext: ExecutionContext = Threading.defaultContext,
                           eventContext: EventContext = EventContext.Global): Subscription =
    on(executionContext)(body)

  /** Adds a new subscriber instance. The implementing class should handle notifying this subscriber
    * when a new event arrives. If this is the first subscriber, and `disableAutowiring` wasn't called previous,
    * this will trigger a call to `onWire`.
    *
    * @param subscriber An instance of a subscriber class, known to the class implementing this `EventRelay`
    */
  def subscribe(subscriber: S): Unit = subscribersMonitor.synchronized {
    val wiredAlready = wired
    subscribers += subscriber
    if !wiredAlready then onWire()
  }

  /** Removes a previously registered subscriber instance.
    * If this is the last subscriber, and `disableAutowiring` wasn't called previously, this will trigger a call to `onUnwire`.
    *
    * @param subscriber An instance of a subscriber class, known to the class implementing this `EventRelay`
    */
  def unsubscribe(subscriber: S): Unit = subscribersMonitor.synchronized {
    subscribers -= subscriber
    if autowiring && !hasSubscribers then onUnwire()
  }

  /** The class which implements this `EventRelay` can use this method to notify all the subscribers that a new event
    * arrived.
    *
    * @param call A function that will perform some action on each subscriber
    */
  protected def notifySubscribers(call: S => Unit): Unit = subscribers.foreach(call)

  /** Checks if there are any subscribers registered in this `EventRelay`.
    *
    * @return true if any subscribers are registered, false otherwise
    */
  inline final def hasSubscribers: Boolean = subscribers.nonEmpty

  /** Empties the set of subscribers and calls `unWire` if `disableAutowiring` wasn't called before.
    */
  def unsubscribeAll(): Unit = subscribersMonitor.synchronized {
    subscribers = Set.empty
    if autowiring then onUnwire()
  }

  /** Typically, a newly created event streams and signals are lazy in the sense that till there are no subscriptions to them,
    * they will not execute any intermediate computations (e.g. assembled to it through maps, flatMaps, etc). After all,
    * those computations would be ignored at the end. Only when a subscription is created, the computations are performed
    * for the first time.
    * `disableAutowiring` enforces those computations even if there are no subscribers. It can be useful if e.g. the computations
    * perform side-effects or if it's important from the performance point of view to have the intermediate results ready
    * when the subscriber is created.
    *
    * @return The current instance, so that `disableAutoworing` can be chained with other method calls.
    */
  def disableAutowiring(): this.type = subscribersMonitor.synchronized {
    autowiring = false
    if subscribers.isEmpty then onWire()
    this
  }

  inline final def wired: Boolean = hasSubscribers || !autowiring

/** By default, a new event source is initialized lazily, i.e. only when the first subscriber function is registered in it.
  * You can decorate it with `NoAutowiring` to enforce initialization.
  *
  * @see [[EventSource]]
  */
trait NoAutowiring { self: EventSource[_, _] =>
  self.disableAutowiring()
}

