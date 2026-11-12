package io.github.makingthematrix.signals3.priv

import io.github.makingthematrix.signals3.Behaviors.{Beh, Fail}
import io.github.makingthematrix.signals3.{Behaviors, IdGenerator}
import scala.collection.mutable

/**
	* A registry of behaviors of a single lifecycle event, with their failure counterparts.
	*
	* The traits which implement the lifecycle events - `CanBeClosed`, `CanBePaused`, and `CanBeInitialized`,
	* - keep one instance of this registry per event:  for onClose`,
	* `onPause`, `onUnpause`, and `onInit`.
	*
	* The `Fail` behaviors are stored in the same registry as the behaviors of the event they guard: if one
	* of the behaviors throws an exception, the remaining behaviors are not run and all the `Fail` behaviors
	* are invoked with the exception instead.
	*
	* The behaviors run in the reverse order of registration - the last added behavior runs first.
	*/
private[signals3] final class BehaviorRegistry(name: String) {
	private val behs = mutable.ArrayBuffer[Beh]()
	private val fails = mutable.ArrayBuffer[Fail]()
	/**
		* Adds a behavior which will run when the lifecycle event fires.
		*
		* @param beh The behavior to add.
		*/
	def add(beh: Beh): Unit = behs.addOne(beh)

	inline def add(body: => Any): Unit = add((IdGenerator.generate(s"on$name"), () => body): Beh)
	inline def add(id: String, f: () => Any): Unit = add((id -> f): Beh)
	def addById(id: String): Boolean = Behaviors.getBeh(id) match {
		case Some(beh) => add(beh); true
		case None => false
	}

	/**
		* Adds a failure behavior which will run if one of the behaviors of this registry throws an exception.
		*
		* @param fail The failure behavior to add.
		*/
	def addFail(fail: Fail): Unit = fails.addOne(fail)

	inline def addFail(f: Throwable => Any): Unit = addFail((IdGenerator.generate(s"on${name}Failed"), f): Fail)
	inline def addFail(id: String, f: Throwable => Any): Unit = addFail((id -> f): Fail)
	def addFailById(id: String): Boolean = Behaviors.getFail(id) match {
		case Some(fail) => addFail(fail); true
		case None => false
	}

	/**
		* Runs all the behaviors of this registry, in the reverse order of registration, inside a single
		* try-catch. The first behavior that throws prevents the remaining ones from running.
		* @return `true` if all the behaviors run successfully; `false` if one of them threw an exception -
		*         in that case all the failure behaviors of the registry are run with the exception.
		*/
	def run(): Boolean = try {
		behs.foreach(_.f()); true
	} catch {
		case t: Throwable => fails.foreach(_.f(t)); false
	}

	/**
		* Removes all the behaviors and all the failure behaviors from this registry.
		*/
	def reset(): Unit = {
		behs.remove(0, behs.size)
		fails.remove(0, behs.size)
	}
}
