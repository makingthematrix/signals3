package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.*

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

final class GeneratorSignal[V](init    : V,
                               generate: V => V,
                               interval: Either[FiniteDuration, V => Long],
                               paused  : () => Boolean)
                              (using ec: ExecutionContext) 
  extends Signal[V](Some(init)) with NoAutowiring:
  private var closed = false

  private val beat =
    (interval match
       case Left(intv)          => CancellableFuture.repeat(intv) 
       case Right(calcInterval) => CancellableFuture.repeatWithMod(() => calcInterval(currentValue.getOrElse(init)))
    ) {
      if !paused() then head.foreach(v => set(Option(generate(v)), Some(ec)))
    }.onCancel {
      closed = true
    }

  inline def close(): Unit = beat.cancel()

  inline def isClosed: Boolean = closed

object GeneratorSignal:
  def apply[V](init    : V,
               generate: V => V,
               interval: FiniteDuration,
               paused  : () => Boolean = () => false)
              (using ec: ExecutionContext = Threading.defaultContext): GeneratorSignal[V] =
    new GeneratorSignal[V](init, generate, Left(interval), paused)

  inline def unfold[V](init: V, interval: FiniteDuration)(body: V => V)
                      (using ec: ExecutionContext = Threading.defaultContext): GeneratorSignal[V] =
    new GeneratorSignal[V](init, body, Left(interval), () => false)

  inline def unfoldWithMod[V](init: V, interval: V => FiniteDuration)(body: V => V)
                             (using ec: ExecutionContext = Threading.defaultContext): GeneratorSignal[V] =
    new GeneratorSignal[V](init, body, Right(v => interval(v).toMillis), () => false)
