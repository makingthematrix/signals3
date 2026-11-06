package io.github.makingthematrix.signals3.actors

import scala.concurrent.duration.{DurationLong, FiniteDuration}
/**
	* Represents a strategy for configuring the heartbeat of an actor.
	*
	* This enum defines various approaches to managing heartbeat intervals, suitable for
	* different scenarios based on the requirements of responsiveness.
	*/
enum HeartBeatStrategy(val timeout: FiniteDuration) {
	case Linear(ms: FiniteDuration, tm: FiniteDuration = 5.second)                                    extends HeartBeatStrategy(tm)
	case Reactive(shortMs: FiniteDuration, longMs: FiniteDuration, tm: FiniteDuration = 5.second)     extends HeartBeatStrategy(tm)
	case CustomLinear(interval: () => FiniteDuration, tm: FiniteDuration = 5.second)                  extends HeartBeatStrategy(tm)
	case CustomReactive(interval: (hasMsg: Boolean) => FiniteDuration, tm: FiniteDuration = 5.second) extends HeartBeatStrategy(tm)
}
