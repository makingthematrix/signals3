package io.makingthematrix.signals3

import java.util.concurrent.atomic.AtomicReference

import testutils._

final case class Follower[A](signal: Signal[A]) {
  private val receivedValues = new AtomicReference(Vector.empty[A])

  def received: Vector[A] = receivedValues.get

  def lastReceived: Option[A] = received.lastOption

  def receive(a: A): Unit = compareAndSet(receivedValues)(_ :+ a)

  def subscribed(implicit ec: EventContext = EventContext.Global): Follower[A] = {
    signal.onCurrent { receive }
    this
  }
}
