package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.Threading
import io.github.makingthematrix.signals3.actors.{ActorBuilder, MutableActor}
import io.github.makingthematrix.signals3.Behaviors.*
import munit.FunSuite

/**
	* Tests of the behavior library: registering, unregistering, and retrieving behaviors by id.
	* This covers the partial and total behaviors (PBeh/TBeh) and the total and failure behaviors
	* of the lifecycle events (Beh/Fail).
	*
	* The retrieved functions are applied to a real (uninitialized) actor to verify that the type parameters
	* survive the round trip through the registry.
	*/
class ActorBehaviorsSpec extends FunSuite {
	import Threading.defaultContext

	private def newActor(id: String): MutableActor[String, Int] = ActorBuilder(id, 0).build().asInstanceOf[MutableActor[String,Int]]

	test("register and retrieve a partial behavior") {
		val actor = newActor("behaviors-spec-pbeh-1")
		val pf: PF[String, Int] = { case (msg, a) => Some(s"$msg:${a.state}") }
		val pbeh = "spec-pbeh" -> pf

		assert(registerPBeh(pbeh))
		assertEquals(getPBeh[String, Int]("spec-pbeh"), Some(pbeh))
		// the retrieved function is the one registered, with the types intact
		assertEquals(getPBeh[String, Int]("spec-pbeh").map(_.pf.apply(("hello", actor))), Some(Some("hello:0")))
	}

	test("registering a partial behavior with a taken id fails and keeps the original") {
		val pf1: PF[Int, Unit] = { case (n, _) => Some(n + 1) }
		val pf2: PF[Int, Unit] = { case (n, _) => Some(n + 2) }

		assert(registerPBeh("spec-pbeh-dup" -> pf1))
		assert(!registerPBeh("spec-pbeh-dup" -> pf2))
		assertEquals(getPBeh[Int, Unit]("spec-pbeh-dup").map(_.pf), Some(pf1))
	}

	test("register and retrieve a total behavior") {
		val actor = newActor("behaviors-spec-tbeh-1")
		val tf: TF[String, Int] = a => a.state += 1
		val tbeh = "spec-tbeh" -> tf

		assert(registerTBeh(tbeh))
		assertEquals(getTBeh[String, Int]("spec-tbeh"), Some(tbeh))
		// the retrieved function runs and can mutate the actor
		getTBeh[String, Int]("spec-tbeh").foreach(_.f(actor))
		assertEquals(actor.state, 1)
	}

	test("registering a total behavior with a taken id fails and keeps the original") {
		val tf1: TF[Int, Unit] = _ => ()
		val tf2: TF[Int, Unit] = _ => ()

		assert(registerTBeh("spec-tbeh-dup" -> tf1))
		assert(!registerTBeh("spec-tbeh-dup" -> tf2))
		assertEquals(getTBeh[Int, Unit]("spec-tbeh-dup").map(_.f), Some(tf1))
	}

	test("a partial and a total behavior can't share the same id") {
		val pf: PF[Int, Unit] = { case (n, _) => Some(n) }
		val tf: TF[Int, Unit] = _ => ()

		assert(registerPBeh("spec-shared" -> pf))
		assertEquals(registerTBeh("spec-shared" -> tf), false)
		assertEquals(getPBeh[Int, Unit]("spec-shared").map(_.pf), Some(pf))
	}

	test("unregister removes behaviors of both kinds and reports if nothing was removed") {
		val pf: PF[Int, Unit] = { case (n, _) => Some(n) }
		val tf: TF[Int, Unit] = _ => ()

		assert(registerPBeh("spec-unreg" -> pf))
		assert(unregister("spec-unreg"))
		assert(registerTBeh("spec-unreg" -> tf))
		assert(unregister("spec-unreg"))

		// both the partial and the total behavior are gone
		assert(getPBeh[Int, Unit]("spec-unreg").isEmpty)
		assert(getTBeh[Int, Unit]("spec-unreg").isEmpty)

		// nothing is registered under this id any more
		assert(!unregister("spec-unreg"))
		assert(!unregister("spec-never-registered"))
	}

	test("register and retrieve a Beh - a total behavior of a lifecycle event") {
		var ran = false
		val f: () => Unit = () => { ran = true }
		val beh: Beh = "spec-beh" -> f

		assert(registerBeh(beh))
		assertEquals(getBeh("spec-beh"), Some(beh))
		// the retrieved function is the one registered
		getBeh("spec-beh").foreach(_.f())
		assert(ran)

		// the Beh registry is separate from the partial/total behavior registries
		assert(getPBeh[Int, Unit]("spec-beh").isEmpty)
		assert(getTBeh[Int, Unit]("spec-beh").isEmpty)
	}

	test("registering a Beh with a taken id fails and keeps the original") {
		val f1: () => Unit = () => ()
		val f2: () => Unit = () => ()
		val beh1: Beh = "spec-beh-dup" -> f1
		val beh2: Beh = "spec-beh-dup" -> f2

		assert(registerBeh(beh1))
		assert(!registerBeh(beh2))
		assertEquals(getBeh("spec-beh-dup"), Some(beh1))
	}

	test("register and retrieve a Fail - a behavior run when a lifecycle event fails") {
		var received: Option[Throwable] = None
		val f: Throwable => Unit = t => received = Some(t)
		val fail: Fail = "spec-fail" -> f
		val ex = new RuntimeException("spec failure")

		assert(registerFail(fail))
		assertEquals(getFail("spec-fail"), Some(fail))
		// the retrieved function is the one registered, with the exception passed on
		getFail("spec-fail").foreach(_.f(ex))
		assert(received.contains(ex))

		// the Fail registry is separate from the Beh registry
		assert(getBeh("spec-fail").isEmpty)
	}

	test("registering a Fail with a taken id fails and keeps the original") {
		val f1: Throwable => Unit = _ => ()
		val f2: Throwable => Unit = _ => ()
		val fail1: Fail = "spec-fail-dup" -> f1
		val fail2: Fail = "spec-fail-dup" -> f2

		assert(registerFail(fail1))
		assert(!registerFail(fail2))
		assertEquals(getFail("spec-fail-dup"), Some(fail1))
	}

	test("unregisterBeh and unregisterFail remove the behaviors and report if nothing was removed") {
		val beh: Beh = "spec-unreg-beh" -> (() => ())
		val fail: Fail = "spec-unreg-fail" -> (_ => ())

		assert(registerBeh(beh))
		assert(registerFail(fail))

		assert(unregisterBeh("spec-unreg-beh"))
		assert(unregisterFail("spec-unreg-fail"))

		assert(getBeh("spec-unreg-beh").isEmpty)
		assert(getFail("spec-unreg-fail").isEmpty)

		// both registries report that there was nothing to remove
		assert(!unregisterBeh("spec-unreg-beh"))
		assert(!unregisterFail("spec-unreg-fail"))
		assert(!unregisterBeh("spec-never-registered"))
		assert(!unregisterFail("spec-never-registered"))
	}

	test("a Beh and a Fail can share the same id") {
		val beh: Beh = "spec-shared-kind" -> (() => ())
		val fail: Fail = "spec-shared-kind" -> (_ => ())

		assert(registerBeh(beh))
		assert(registerFail(fail))
		// each registry returns its own kind of behavior
		assertEquals(getBeh("spec-shared-kind"), Some(beh))
		assertEquals(getFail("spec-shared-kind"), Some(fail))
	}
}
