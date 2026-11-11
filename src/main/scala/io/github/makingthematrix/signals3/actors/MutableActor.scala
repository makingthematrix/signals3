package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.Behaviors.{Fail, TBeh, TF}
import io.github.makingthematrix.signals3.{Behaviors, IdGenerator, SourceStream}

/**
	* A trait representing a mutable actor, which is an extension of the `Actor` trait. This actor
	* allows dynamic modification of its internal state and behavior at runtime. It introduces methods
	* to update the actor's final behavior, heartbeat strategy, and state, as well as to add or remove
	* behaviors dynamically.
	*
	* Mainly used by the `Behavior` functions.
	*
	* @tparam Msg   The type of the incoming message
	* @tparam State The type of the internal state
	*/
trait MutableActor[Msg, State] extends Actor[Msg, State]{
	/**
		* Enables the behavior method to alter the actor's state
		*
		* @param newState the new state of the actor
		*/
	def state_=(newState: State): Unit

	/** The **optional** output stream that may be used by the behaviors to push out a new response.
		*
		* "Optional" is a keyword here. It's totally up to a behavior if it decides to send a response to `out`.
		* You may build your actor in such a way that it operates solely on the `in` and `out` streams, you can forget
		* about them, or you can do anything in-between.
		*
		* In `MutableActor` the type of `out` changes to `SourceStream[Msg]` so that the behavior may send a response to it.
		*/
	override def out: SourceStream[Msg]

	private var _onBeat: List[TBeh[Msg, State]] = Nil

	// Registers a function that should be called exactly once when the actor is initialized
	def onBeat(beh: TBeh[Msg, State]): Unit =
		_onBeat ::= beh

	inline final def onBeat(tf: TF[Msg, State]): Unit =
		onBeat((IdGenerator.generate("onBeat"), tf))

	inline final def onBeat(id: String, tf: TF[Msg, State]): Unit = onBeat(id -> tf)

	final def onBeat(id: String): Boolean = Behaviors.getTBeh(id) match {
		case Some((id, tf)) => onBeat(id -> tf.asInstanceOf[TF[Msg, State]]); true
		case None => false
	}

	private var _onBeatFailed: List[Fail] = Nil

	// Registers a function that should be called exactly once when the actor is initialized
	def onBeatFailed(fail: Fail): Unit =
		_onBeatFailed ::= fail

	inline final def onBeatFailed(f: Throwable => Unit): Unit =
		onBeatFailed((IdGenerator.generate("onBeatFailed"), f))

	inline final def onBeatFailed(id: String, f: Throwable => Unit): Unit = onBeatFailed(id -> f)

	final def onBeatFailed(id: String): Boolean = Behaviors.getFail(id) match {
		case Some((id, f)) => onBeatFailed(id -> f); true
		case None => false
	}

	inline final def runOnBeat(): Unit = try {
		_onBeat.foreach(_.tf(this))
	} catch {
		case t: Throwable => _onBeatFailed.foreach(_.f(t))
	}
}
