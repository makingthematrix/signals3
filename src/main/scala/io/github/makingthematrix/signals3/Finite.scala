package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Finite.Source
import scala.concurrent.Future

trait Finite[T, M <: Source[T]]:
  @volatile private var forceClose = false

  protected def closeAndCheck(): Boolean =
    forceClose = true
    true

  protected def close(): Unit = closeAndCheck()

  def isClosed: Boolean = forceClose

  def last: Future[T]
  def init: M
  
object Finite:
  type Source[T] = Stream[T] | Signal[T]
