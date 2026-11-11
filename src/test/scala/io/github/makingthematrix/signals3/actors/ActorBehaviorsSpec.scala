package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.Threading
import io.github.makingthematrix.signals3.actors.{ActorBuilder, MutableActor}
import io.github.makingthematrix.signals3.Behaviors.*
import munit.FunSuite

/**
	* Tests of the behavior library: registering, unregistering, and retrieving partial and total behaviors by id.
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
		getTBeh[String, Int]("spec-tbeh").foreach(_.tf(actor))
		assertEquals(actor.state, 1)
	}

	test("registering a total behavior with a taken id fails and keeps the original") {
		val tf1: TF[Int, Unit] = _ => ()
		val tf2: TF[Int, Unit] = _ => ()

		assert(registerTBeh("spec-tbeh-dup" -> tf1))
		assert(!registerTBeh("spec-tbeh-dup" -> tf2))
		assertEquals(getTBeh[Int, Unit]("spec-tbeh-dup").map(_.tf), Some(tf1))
	}

	test("a partial and a total behavior can share the same id") {
		val pf: PF[Int, Unit] = { case (n, _) => Some(n) }
		val tf: TF[Int, Unit] = _ => ()

		assert(registerPBeh("spec-shared" -> pf))
		assert(registerTBeh("spec-shared" -> tf))
		assertEquals(getPBeh[Int, Unit]("spec-shared").map(_.pf), Some(pf))
		assertEquals(getTBeh[Int, Unit]("spec-shared").map(_.tf), Some(tf))
	}

	test("unregister removes behaviors of both kinds and reports if nothing was removed") {
		val pf: PF[Int, Unit] = { case (n, _) => Some(n) }
		val tf: TF[Int, Unit] = _ => ()

		assert(registerPBeh("spec-unreg" -> pf))
		assert(registerTBeh("spec-unreg" -> tf))

		assert(unregister("spec-unreg"))
		// both the partial and the total behavior are gone
		assert(getPBeh[Int, Unit]("spec-unreg").isEmpty)
		assert(getTBeh[Int, Unit]("spec-unreg").isEmpty)

		// nothing is registered under this id any more
		assert(!unregister("spec-unreg"))
		assert(!unregister("spec-never-registered"))
	}
}
