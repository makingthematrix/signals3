package io.github.makingthematrix.signals3

import scala.concurrent.ExecutionContext
import Signal.SignalSubscriber

abstract private[signals3] class ProxySignal[V](sources: Signal[?]*) extends Signal[V] with SignalSubscriber:
  override def onWire(): Unit =
    sources.foreach(_.subscribe(this))
    value = computeValue(value)

  override def onUnwire(): Unit = sources.foreach(_.unsubscribe(this))

  override def changed(ec: Option[ExecutionContext]): Unit = update(computeValue, ec)

  protected def computeValue(current: Option[V]): Option[V] // this method needs to be overriden in subclasses

private[signals3] object ProxySignal:
  final class ScanSignal[V, Z](source: Signal[V], zero: Z, f: (Z, V) => Z) extends ProxySignal[Z](source):
    value = Some(zero)

    override protected def computeValue(current: Option[Z]): Option[Z] =
      source.value.map { v => f(current.getOrElse(zero), v) }.orElse(current)

  class FilterSignal[V](source: Signal[V], predicate: V => Boolean) extends ProxySignal[V](source):
    override protected def computeValue(current: Option[V]): Option[V] = source.value.filter(predicate)

  class MapSignal[V, Z](source: Signal[V], f: V => Z) extends ProxySignal[Z](source):
    override protected def computeValue(current: Option[Z]): Option[Z] = source.value.map(f)

  class IndexedSignal[V](source: Signal[V]) extends ProxySignal[V](source) with Indexed[V]:
    value = source.value

    override protected def computeValue(current: Option[V]): Option[V] =
      if source.value != current then inc()
      source.value

  final class CloseableSignal[V](source: Signal[V]) extends ProxySignal[V](source) with Closeable:
    @volatile private var closed = false

    override def closeAndCheck(): Boolean =
      closed = true
      true

    override def isClosed: Boolean = closed

    override protected def computeValue(current: Option[V]): Option[V] =
      if !closed then source.value else current
  
  class CollectSignal[V, Z](source: Signal[V], pf: PartialFunction[V, Z]) extends ProxySignal[Z](source):
    override protected def computeValue(current: Option[Z]): Option[Z] =
      source.value.flatMap { v =>
        pf.andThen(Option(_)).applyOrElse(v, { (_: V) => Option.empty[Z] })
      }

  class Zip2Signal[A, B](a: Signal[A], b: Signal[B]) extends ProxySignal[(A, B)](a, b):
    override protected def computeValue(current: Option[(A, B)]): Option[(A, B)] =
      for a <- a.value; b <- b.value yield (a, b)

  class Zip3Signal[A, B, C](a: Signal[A], b: Signal[B], c: Signal[C])
    extends ProxySignal[(A, B, C)](a, b, c):
    override protected def computeValue(current: Option[(A, B, C)]): Option[(A, B, C)] =
      for
        a <- a.value
        b <- b.value
        c <- c.value
      yield (a, b, c)

  class Zip4Signal[A, B, C, D](a: Signal[A], b: Signal[B], c: Signal[C], d: Signal[D])
    extends ProxySignal[(A, B, C, D)](a, b, c, d):
    override protected def computeValue(current: Option[(A, B, C, D)]): Option[(A, B, C, D)] =
      for
        a <- a.value
        b <- b.value
        c <- c.value
        d <- d.value
      yield (a, b, c, d)

  class Zip5Signal[A, B, C, D, E](a: Signal[A], b: Signal[B], c: Signal[C], d: Signal[D], e: Signal[E])
    extends ProxySignal[(A, B, C, D, E)](a, b, c, d, e):
    override protected def computeValue(current: Option[(A, B, C, D, E)]): Option[(A, B, C, D, E)] =
      for
        a <- a.value
        b <- b.value
        c <- c.value
        d <- d.value
        e <- e.value
      yield (a, b, c, d, e)

  class Zip6Signal[A, B, C, D, E, F](a: Signal[A], b: Signal[B], c: Signal[C], d: Signal[D], e: Signal[E], f: Signal[F])
    extends ProxySignal[(A, B, C, D, E, F)](a, b, c, d, e, f):
    override protected def computeValue(current: Option[(A, B, C, D, E, F)]): Option[(A, B, C, D, E, F)] = for
      a <- a.value
      b <- b.value
      c <- c.value
      d <- d.value
      e <- e.value
      f <- f.value
    yield (a, b, c, d, e, f)

  final class FoldLeftSignal[V, Z](sources: Signal[V]*)(v: Z)(f: (Z, V) => Z) extends ProxySignal[Z](sources*):
    override protected def computeValue(current: Option[Z]): Option[Z] =
      sources.foldLeft(Option(v))((mv, signal) => for a <- mv; b <- signal.value yield f(a, b))

  final class PartialUpdateSignal[V, Z](source: Signal[V])(select: V => Z) extends ProxySignal[V](source):
    private object updateMonitor

    override protected[signals3] def update(f: Option[V] => Option[V], currentContext: Option[ExecutionContext]): Boolean =
      val changed = updateMonitor.synchronized {
        val next = f(value)
        if value.map(select) != next.map(select) then
          value = next
          true
        else false
      }
      if changed then notifySubscribers(currentContext)
      changed

    override protected def computeValue(current: Option[V]): Option[V] = source.value

  class StreamSignal[V](source: Stream[V], v: Option[V] = None) extends Signal[V](v):
    private lazy val subscription = source.onCurrent(publish)(using EventContext.Global)
    override protected def onWire(): Unit = subscription.enable()
    override protected def onUnwire(): Unit = subscription.disable()

  class SequenceSignal[V](sources: Signal[V]*) extends ProxySignal[Seq[V]](sources*):
    override protected def computeValue(current: Option[Seq[V]]): Option[Seq[V]] =
      val res = sources.map(_.value)
      if res.exists(_.isEmpty) then None else Some(res.flatten)
      
  class CombineSignal[V, Z, Y](vSignal: Signal[V], zSignal: Signal[Z], f: (V, Z) => Y) 
    extends ProxySignal[Y](vSignal, zSignal):
    override protected def computeValue(current: Option[Y]): Option[Y] = 
      for 
        v <- vSignal.value 
        z <- zSignal.value 
      yield f(v, z)