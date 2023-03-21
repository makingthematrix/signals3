package io.github.makingthematrix.signals3.generators

import io.github.makingthematrix.signals3.*

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

final class GeneratorEventStream[E](generate: () => E,
                                    interval: Either[FiniteDuration, () => Long],
                                    paused  : () => Boolean)
                                   (using ec: ExecutionContext) extends EventStream[E] with NoAutowiring:
  private var stopped = false

  private val beat =
    interval match
      case Left(intv) =>
        CancellableFuture.repeat(intv) {
          if !paused() then publish(generate())
        }.onCancel {
          stopped = true
        }
      case Right(calcInterval) =>
        CancellableFuture.repeatWithMod(calcInterval) {
          if !paused() then publish(generate())
        }.onCancel {
          stopped = true
        }

  inline def stop(): Unit = beat.cancel()

  inline def isStopped: Boolean = stopped

object GeneratorEventStream:
  def apply[E](generate: () => E,
               interval: FiniteDuration,
               paused  : () => Boolean = () => false)
              (using ec: ExecutionContext = Threading.defaultContext): GeneratorEventStream[E] =
    new GeneratorEventStream[E](generate, Left(interval), paused)

  inline def generate[E](interval: FiniteDuration)(body: => E)(using ec: ExecutionContext = Threading.defaultContext): GeneratorEventStream[E] =
    new GeneratorEventStream[E](() => body, Left(interval), () => false)

  inline def generateWithMod[E](interval: () => FiniteDuration)(body: => E)(using ec: ExecutionContext = Threading.defaultContext): GeneratorEventStream[E] =
    new GeneratorEventStream[E](() => body, Right(() => interval().toMillis), () => false)

  inline def repeat[E](event: E, interval: FiniteDuration)(using ec: ExecutionContext = Threading.defaultContext): GeneratorEventStream[E] =
    new GeneratorEventStream[E](() => event, Left(interval), () => false)

  inline def repeatWithMod[E](event: E, interval: () => FiniteDuration)(using ec: ExecutionContext = Threading.defaultContext): GeneratorEventStream[E] =
    new GeneratorEventStream[E](() => event, Right(() => interval().toMillis), () => false)

  inline def heartbeat(interval: FiniteDuration)(using ec: ExecutionContext = Threading.defaultContext): GeneratorEventStream[Unit] =
    repeat((), interval)

