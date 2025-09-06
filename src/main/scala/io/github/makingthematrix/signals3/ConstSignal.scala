package io.github.makingthematrix.signals3

import Signal.SignalSubscriber
import EventSource.NoAutowiring
import scala.concurrent.ExecutionContext

/** A signal holding an immutable value.
  * Using const signals in flatMap chains should have better performance compared to source signals with the same value.
  * Since the value never changes, the subscriber function will be called only in the moment of subscription, but never
  * after that, so there's no need to keep the subscription.
  */
final private[signals3] class ConstSignal[V] (private val v: Option[V])
  extends Signal[V](v) with NoAutowiring with CanBeClosed:
  override inline def subscribe(subscriber: SignalSubscriber): Unit = {}
  override inline def unsubscribe(subscriber: SignalSubscriber): Unit = {}
  override inline protected[signals3] def update(f: Option[V] => Option[V], ec: Option[ExecutionContext]): Boolean = false
  override inline protected[signals3] def set(v: Option[V], ec: Option[ExecutionContext]): Boolean = false
  override inline def isClosed: Boolean = true
