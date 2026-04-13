package io.github.makingthematrix.signals3

type SideEffect = Option[Throwable => Unit]

enum FallbackStrategy(val retryTimes: Int, val sideEffect: SideEffect = None) {
  case Rethrow(override val retryTimes: Int = 0, override val sideEffect: SideEffect = None) extends FallbackStrategy(retryTimes, sideEffect)
  case Ignore(override val retryTimes: Int = 0, override val sideEffect: SideEffect = None) extends FallbackStrategy(retryTimes, sideEffect)
  case Close(override val retryTimes: Int = 0, override val sideEffect: SideEffect = None) extends FallbackStrategy(retryTimes, sideEffect)
  case UseDefault[T](defValue: T, override val retryTimes: Int = 0, override val sideEffect: SideEffect = None) extends FallbackStrategy(retryTimes, sideEffect)

  inline def triggerSideEffect(ex: Throwable): Unit = sideEffect.foreach(_(ex))
}

enum FallbackDecision {
  case RETHROW(t: Throwable)
  case IGNORE, CLOSE
}

object FallbackStrategy {
}
