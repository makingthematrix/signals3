package io.github.makingthematrix.signals3

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

final class GeneratorEventStream[E] private (generate: () => E,
                                             interval: FiniteDuration,
                                             pause   : () => Boolean)
                                            (using ec: ExecutionContext) extends EventStream[E] with NoAutowiring:
  private var stopped = false

  private val beat = CancellableFuture.repeat(interval) {
    if !pause() then publish(generate())
  }.onCancel {
    stopped = true
  }

  inline def stop(): Unit = beat.cancel()

  inline def isStopped: Boolean = stopped

object GeneratorEventStream:
  def apply[E](generate: () => E,
               interval: FiniteDuration,
               pause   : () => Boolean = () => false)
              (using ec: ExecutionContext = Threading.defaultContext): GeneratorEventStream[E] =
    new GeneratorEventStream[E](generate, interval, pause)

  inline def repeat[E](event: E, interval: FiniteDuration)(using ec: ExecutionContext = Threading.defaultContext): GeneratorEventStream[E] =
    GeneratorEventStream(generate = () => event, interval = interval, pause = () => false)

  inline def heartbeat(interval: FiniteDuration)(using ec: ExecutionContext = Threading.defaultContext): GeneratorEventStream[Unit] =
    repeat((), interval)

