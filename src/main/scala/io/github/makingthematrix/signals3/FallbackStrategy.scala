package io.github.makingthematrix.signals3

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}
import scala.math.max

import io.github.makingthematrix.signals3.FallbackStrategy.SideEffect

enum FallbackStrategy(val retryTimes: Int, val sideEffects: List[SideEffect]) {
  case Rethrow(override val retryTimes: Int = 0, override val sideEffects: List[SideEffect] = Nil) extends FallbackStrategy(retryTimes, sideEffects)
  case Ignore(override val retryTimes: Int = 0, override val sideEffects: List[SideEffect] = Nil) extends FallbackStrategy(retryTimes, sideEffects)
  case Close(override val retryTimes: Int = 0, override val sideEffects: List[SideEffect] = Nil) extends FallbackStrategy(retryTimes, sideEffects)
  case UseDefault[T](defValue: T, override val retryTimes: Int = 0, override val sideEffects: List[SideEffect] = Nil) extends FallbackStrategy(retryTimes, sideEffects)

  inline def triggerSideEffects(ex: Throwable): Unit = sideEffects.foreach(f => f(ex))
}

enum FallbackDecision {
  case RETHROW(t: Throwable)
  case IGNORE, CLOSE
}

object FallbackStrategy {
  import FallbackDecision.*

  val rethrow: FallbackStrategy = Rethrow()
  val ignore: FallbackStrategy = Ignore()
  val close: FallbackStrategy = Close()
  def useDefault[T](defValue: T): FallbackStrategy = UseDefault(defValue)

  type SideEffect = Throwable => Unit

  @tailrec @unchecked
  def eval[V](f: () => V, fs: FallbackStrategy, retry: Int = 0): Either[FallbackDecision, V] = (Try(f()), fs, retry) match {
    case (Success(value), _, _)                    => Right(value)
    case (Failure(ex), fs, n) if fs.retryTimes > n => fs.triggerSideEffects(ex); eval(f, fs, n + 1)
    case (Failure(ex), fs: Rethrow, _)             => fs.triggerSideEffects(ex); Left(RETHROW(ex))
    case (Failure(ex), fs: Ignore, _)              => fs.triggerSideEffects(ex); Left(IGNORE)
    case (Failure(ex), fs: Close, _)               => fs.triggerSideEffects(ex); Left(CLOSE)
    case (Failure(ex), fs: UseDefault[V], _)       => fs.triggerSideEffects(ex); Right(fs.defValue)
  }

  // UseDefault > Ignore > Close > Rethrow
  def merge(fs: Seq[FallbackStrategy]): FallbackStrategy = fs.reduce {
    case (a, b) if a == b                      => a
    case (a: Rethrow, b: Rethrow)              => a.copy(retryTimes = max(a.retryTimes, b.retryTimes), sideEffects = a.sideEffects ++ b.sideEffects)
    case (a: Rethrow, b: Ignore)               => b.copy(sideEffects = a.sideEffects ++ b.sideEffects)
    case (a: Rethrow, b: Close)                => b.copy(sideEffects = a.sideEffects ++ b.sideEffects)
    case (a: Rethrow, b: UseDefault[_])        => b.copy(sideEffects = a.sideEffects ++ b.sideEffects)
    case (a: Close, b: Close)                  => a.copy(retryTimes = max(a.retryTimes, b.retryTimes), sideEffects = a.sideEffects ++ b.sideEffects)
    case (a: Close, b: Rethrow)                => a.copy(sideEffects = a.sideEffects ++ b.sideEffects)
    case (a: Close, b: Ignore)                 => b.copy(sideEffects = a.sideEffects ++ b.sideEffects)
    case (a: Close, b: UseDefault[_])          => b.copy(sideEffects = a.sideEffects ++ b.sideEffects)
    case (a: Ignore, b: Ignore)                => a.copy(retryTimes = max(a.retryTimes, b.retryTimes), sideEffects = a.sideEffects ++ b.sideEffects)
    case (a: Ignore, b: Rethrow)               => a.copy(sideEffects = a.sideEffects ++ b.sideEffects)
    case (a: Ignore, b: Close)                 => a.copy(sideEffects = a.sideEffects ++ b.sideEffects)
    case (a: Ignore, b: UseDefault[_])         => b.copy(sideEffects = a.sideEffects ++ b.sideEffects)
    case (a: UseDefault[_], b: UseDefault[_])  => a.copy(retryTimes = max(a.retryTimes, b.retryTimes), sideEffects = a.sideEffects ++ b.sideEffects)
    case (a: UseDefault[_], b: Rethrow)        => a.copy(sideEffects = a.sideEffects ++ b.sideEffects)
    case (a: UseDefault[_], b: Ignore)         => a.copy(sideEffects = a.sideEffects ++ b.sideEffects)
    case (a: UseDefault[_], b: Close)          => a.copy(sideEffects = a.sideEffects ++ b.sideEffects)
  }
}
