package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.{Behaviors, Threading}
import io.github.makingthematrix.signals3.actors.{ActorBuilder, MutableActor}
import io.github.makingthematrix.signals3.Behaviors.*
import io.github.makingthematrix.signals3.Behaviors.PBeh.{orElse, andThen, isDefinedAt}
import munit.FunSuite

import scala.collection.mutable

/**
	* Tests of the behavior library: registering, unregistering, and retrieving behaviors by id,
	* and composing partial behaviors into composite ones (`PBeh.or`/`PBeh.and` and their by-id variants).
	* This covers the partial and total behaviors (PBeh/TBeh) and the total and failure behaviors
	* of the lifecycle events (Beh/Fail).
	*
	* The retrieved functions are applied to a real (uninitialized) actor to verify that the type parameters
	* survive the round trip through the registry.
	*/
class ActorBehaviorsSpec extends FunSuite {
	import Threading.defaultContext
	
	override def beforeEach(context: BeforeEach): Unit = {
		Behaviors.reset()
	}

	override def afterEach(context: AfterEach): Unit = {
		Behaviors.reset()
	}
	
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

		assert(unregister("spec-unreg-beh"))
		assert(unregister("spec-unreg-fail"))

		assert(getBeh("spec-unreg-beh").isEmpty)
		assert(getFail("spec-unreg-fail").isEmpty)

		// both registries report that there was nothing to remove
		assert(!unregister("spec-unreg-beh"))
		assert(!unregister("spec-unreg-fail"))
		assert(!unregister("spec-never-registered"))
		assert(!unregister("spec-never-registered"))
	}

	test("a Beh and a Fail cannot share the same id (globally unique ids)") {
		val beh: Beh = "spec-shared-kind" -> (() => ())
		val fail: Fail = "spec-shared-kind" -> (_ => ())

		assert(registerBeh(beh))
		assert(!registerFail(fail))
		// the first registration wins; the Fail is not registered anywhere
		assertEquals(getBeh("spec-shared-kind"), Some(beh))
		assert(getFail("spec-shared-kind").isEmpty)
	}

	// ==================== Behavior Composition ====================

	test("or composes partial behaviors - the first matching behavior handles the message") {
		val actor = newActor("behaviors-spec-or-1")
		val pfA: PF[String, Int] = { case ("a", _) => Some("A:a") }
		val pfB: PF[String, Int] = { case ("a", _) => Some("B:a")
		                              case ("b", _) => Some("B:b") }

		val composite = PBeh.or("spec-or", "or-a" -> pfA, "or-b" -> pfB)
		assert(composite.isDefined)
		// the first matching behavior handles the message
		assertEquals(composite.get.pf(("a", actor)), Some("A:a"))
		// a message the first behavior does not handle falls through to the next one
		assertEquals(composite.get.pf(("b", actor)), Some("B:b"))
		// the composite is registered under its id and retrievable with the types intact
		assertEquals(getPBeh[String, Int]("spec-or").map(_.pf(("a", actor))), Some(Some("A:a")))
		// the composite stays partial: a message nobody handles is not defined
		assert(!composite.get.pf.isDefinedAt("z", actor))
	}

	test("and composes partial behaviors - every matching behavior runs, the last non-empty response wins") {
		val actor = newActor("behaviors-spec-and-1")
		val log = mutable.ArrayBuffer[String]()
		val pfC: PF[String, Int] = { case ("m", _) => log += "C"; Some("C") }
		val pfD: PF[String, Int] = { case ("m", _) => log += "D"; Some("D")
		                              case ("n", _) => log += "D2"; Some("D2") }

		val composite = PBeh.and("spec-and", "and-c" -> pfC, "and-d" -> pfD)
		assert(composite.isDefined)
		// both behaviors run, in order; the last response wins
		assertEquals(composite.get.pf(("m", actor)), Some("D"))
		assertEquals(log.toList, List("C", "D"))
		// a behavior which is not defined at the message does not run
		assertEquals(composite.get.pf(("n", actor)), Some("D2"))
		assertEquals(log.toList, List("C", "D", "D2"))
		// the composite stays partial: a message nobody handles is not defined
		assert(!composite.get.pf.isDefinedAt("z", actor))
	}

	test("and lets an earlier response through when a later behavior returns None") {
		val actor = newActor("behaviors-spec-and-2")
		val pfNone: PF[String, Int] = { case ("m", _) => None }
		val pfX: PF[String, Int] = { case ("m", _) => Some("X") }

		val composite = PBeh.and("spec-and-none", "and-n" -> pfNone, "and-x" -> pfX)
		assertEquals(composite.get.pf(("m", actor)), Some("X"))
	}

	test("a composite with a taken id is not registered and the original stays") {
		val actor = newActor("behaviors-spec-composite-dup-1")
		val pfA: PF[String, Int] = { case ("a", _) => Some("A:a") }
		val pfB: PF[String, Int] = { case ("b", _) => Some("B:b") }
		val pfC: PF[String, Int] = { case ("c", _) => Some("C:c") }
		assert(registerPBeh("spec-composite-taken" -> pfA))

		assert(PBeh.or("spec-composite-taken", "dup-b" -> pfB).isEmpty)
		assert(PBeh.and("spec-composite-taken", "dup-c" -> pfC).isEmpty)
		// the behavior registered first is kept
		assertEquals(getPBeh[String, Int]("spec-composite-taken").map(_.pf(("a", actor))), Some(Some("A:a")))
	}

	test("orElseId composes the behaviors registered under the given ids") {
		val actor = newActor("behaviors-spec-or-id-1")
		val pfA: PF[String, Int] = { case ("a", _) => Some("A:a") }
		val pfD: PF[String, Int] = { case ("n", _) => Some("D:n") }
		assert(registerPBeh("spec-id-a" -> pfA))
		assert(registerPBeh("spec-id-d" -> pfD))

		val composite = PBeh.orElseId[String, Int]("spec-or-by-id", "spec-id-a", "spec-id-d")
		assert(composite.isDefined)
		assertEquals(composite.get.pf(("a", actor)), Some("A:a"))
		assertEquals(composite.get.pf(("n", actor)), Some("D:n"))
	}

	test("andThenId composes the behaviors registered under the given ids") {
		val actor = newActor("behaviors-spec-and-id-1")
		val pfA: PF[String, Int] = { case ("a", _) => Some("A:a") }
		val pfD: PF[String, Int] = { case ("n", _) => Some("D:n") }
		assert(registerPBeh("spec-id-a" -> pfA))
		assert(registerPBeh("spec-id-d" -> pfD))

		val composite = PBeh.andThenId[String, Int]("spec-and-by-id", "spec-id-a", "spec-id-d")
		assert(composite.isDefined)
		// a message only the first behavior handles gets its response; so does a message only the second handles
		assertEquals(composite.get.pf(("a", actor)), Some("A:a"))
		assertEquals(composite.get.pf(("n", actor)), Some("D:n"))
		// a message nobody handles is not defined - the composite stays partial
		assert(!composite.get.pf.isDefinedAt("z", actor))
	}

	test("composing by ids fails if any id is invalid") {
		val pfA: PF[String, Int] = { case ("a", _) => Some("A:a") }
		assert(registerPBeh("spec-id-a" -> pfA))

		// an invalid id mixed with valid ones fails the whole composition
		assert(PBeh.orElseId[String, Int]("spec-bad-1", "spec-id-a", "spec-no-such").isEmpty)
		assert(PBeh.andThenId[String, Int]("spec-bad-2", "spec-id-a", "spec-no-such").isEmpty)
		// a single invalid id fails too, without crashing
		assert(PBeh.orElseId[String, Int]("spec-bad-3", "spec-no-such").isEmpty)
		assert(PBeh.andThenId[String, Int]("spec-bad-4", "spec-no-such").isEmpty)
	}

	test("extension methods orElse and andThen compose behaviors under generated ids") {
		val actor = newActor("behaviors-spec-ext-1")
		val pfA: PF[String, Int] = { case ("a", _) => Some("A:a") }
		val pfB: PF[String, Int] = { case ("b", _) => Some("B:b") }
		val pfC: PF[String, Int] = { case ("c", _) => Some("C:c") }
		val pfD: PF[String, Int] = { case ("c", _) => Some("D:c") }

		// infix syntax is available for the extension methods
		val extOr = ("ext-a" -> pfA) orElse ("ext-b" -> pfB)
		assertEquals(extOr.pf(("a", actor)), Some("A:a"))
		assertEquals(extOr.pf(("b", actor)), Some("B:b"))
		// the composite is registered under a generated id
		assert(extOr.id.nonEmpty)
		assertEquals(getPBeh[String, Int](extOr.id).map(_.pf(("b", actor))), Some(Some("B:b")))

		val extAnd = ("ext-c" -> pfC) andThen ("ext-d" -> pfD)
		assertEquals(extAnd.pf(("c", actor)), Some("D:c"))
		assertEquals(getPBeh[String, Int](extAnd.id).map(_.pf(("c", actor))), Some(Some("D:c")))

		// isDefinedAt reflects the composition: both composites are partial
		assert(extOr.isDefinedAt("a", actor))
		assert(!extOr.isDefinedAt("z", actor))
		assert(extAnd.isDefinedAt("c", actor))
		assert(!extAnd.isDefinedAt("z", actor))
	}
}
