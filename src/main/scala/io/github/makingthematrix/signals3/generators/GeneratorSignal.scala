package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.*

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

final class GeneratorSignal[V] private (init    : Option[V],
                                        generate: V => V,
                                        interval: FiniteDuration,
                                        pause   : () => Boolean)
                                       (using ec: ExecutionContext) extends Signal[V](init) with NoAutowiring:
  private var stopped = false

  private val beat = CancellableFuture.repeat(interval) {
    if !pause() then head.foreach(v => set(Option(generate(v)), Some(ec)))
  }.onCancel {
    stopped = true
  }

  inline def stop(): Unit = beat.cancel()

  inline def isStopped: Boolean = stopped

object GeneratorSignal:
  def apply[V](generate: V => V,
               interval: FiniteDuration,
               init    : Option[V] = None,
               pause   : () => Boolean = () => false)
              (using ec: ExecutionContext = Threading.defaultContext): GeneratorSignal[V] =
    new GeneratorSignal[V](init, generate, interval, pause)

  inline def unfold[V](interval: FiniteDuration, init: V)(body: V => V)(using ec: ExecutionContext = Threading.defaultContext): GeneratorSignal[V] =
    GeneratorSignal(generate = body, interval = interval, Some(init), pause = () => false)
