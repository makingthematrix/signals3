package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Signal
import scala.annotation.static

private[signals3] object ZipSignal {

  @static class Zip2Signal[A, B](a: Signal[A], b: Signal[B]) extends ProxySignal[(A, B)](a, b) {
    override protected def computeValue(current: Option[(A, B)]): Option[(A, B)] =
      for a <- a.value; b <- b.value yield (a, b)
  }

  @static class Zip3Signal[A, B, C](a: Signal[A], b: Signal[B], c: Signal[C])
    extends ProxySignal[(A, B, C)](a, b, c) {
    override protected def computeValue(current: Option[(A, B, C)]): Option[(A, B, C)] =
      for {
        a <- a.value
        b <- b.value
        c <- c.value
      }
      yield (a, b, c)
  }

  @static class Zip4Signal[A, B, C, D](a: Signal[A], b: Signal[B], c: Signal[C], d: Signal[D])
    extends ProxySignal[(A, B, C, D)](a, b, c, d) {
    override protected def computeValue(current: Option[(A, B, C, D)]): Option[(A, B, C, D)] =
      for {
        a <- a.value
        b <- b.value
        c <- c.value
        d <- d.value
      }
      yield (a, b, c, d)
  }

  @static class Zip5Signal[A, B, C, D, E](a: Signal[A], b: Signal[B], c: Signal[C], d: Signal[D], e: Signal[E])
    extends ProxySignal[(A, B, C, D, E)](a, b, c, d, e) {
    override protected def computeValue(current: Option[(A, B, C, D, E)]): Option[(A, B, C, D, E)] =
      for {
        a <- a.value
        b <- b.value
        c <- c.value
        d <- d.value
        e <- e.value
      }
      yield (a, b, c, d, e)
  }

  @static class Zip6Signal[A, B, C, D, E, F](a: Signal[A], b: Signal[B], c: Signal[C], d: Signal[D], e: Signal[E], f: Signal[F])
    extends ProxySignal[(A, B, C, D, E, F)](a, b, c, d, e, f) {
    override protected def computeValue(current: Option[(A, B, C, D, E, F)]): Option[(A, B, C, D, E, F)] = for {
      a <- a.value
      b <- b.value
      c <- c.value
      d <- d.value
      e <- e.value
      f <- f.value
    }
    yield (a, b, c, d, e, f)
  }
}
