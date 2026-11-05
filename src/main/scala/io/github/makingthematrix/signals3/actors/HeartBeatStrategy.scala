package io.github.makingthematrix.signals3.actors

import scala.concurrent.duration.{DurationLong, FiniteDuration}
/**
	* Represents a strategy for configuring the heartbeat of an actor.
	*
	* This enum defines various approaches to managing heartbeat intervals, suitable for
	* different scenarios based on the requirements of responsiveness.
	*/

sealed abstract class HeartBeatStrategy(val timeout: FiniteDuration)

object HeartBeatStrategy {
	sealed trait ReactiveTrait

	final case class Linear(ms: FiniteDuration, override val timeout: FiniteDuration = 5.second) extends HeartBeatStrategy(timeout)
	final case class Reactive(shortMs: FiniteDuration, longMs: FiniteDuration, override val timeout: FiniteDuration = 5.second)
		extends HeartBeatStrategy(timeout) with ReactiveTrait
	final case class CustomLinear(interval: () => FiniteDuration, override val timeout: FiniteDuration = 5.second) extends HeartBeatStrategy(timeout)
	final case class CustomReactive(interval: (hasMsg: Boolean) => FiniteDuration, override val timeout: FiniteDuration = 5.second)
		extends HeartBeatStrategy(timeout) with ReactiveTrait
}
