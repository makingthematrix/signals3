package io.github.makingthematrix.signals3

import testutils.*

import scala.concurrent.Promise

class AggregatingSignalSpec extends munit.FunSuite:
  import Threading.defaultContext

  test("new aggregator, no subscribers") {
    val promise = Promise[Seq[Int]]()
    val updater = EventStream[String]()

    val as = new AggregatingSignal[String, Seq[Int]](
      () => promise.future,
      updater,
      (b, a) => b :+ a.length
    )

    assertEquals(as.value, None)

    promise.success(Seq(42))
    updater ! "meep"

    assertEquals(as.value, None)
  }

  test("one subscriber") {
    val promise = Promise[Seq[Int]]()
    val updater = EventStream[String]()

    val as = new AggregatingSignal[String, Seq[Int]](
      () => promise.future,
      updater,
      (b, a) => b :+ a.length
    )

    val value = Signal(Seq.empty[Int])

    as.foreach { value ! _ }
    waitForResult(value, Seq.empty)
    assertEquals(as.value, None)

    promise.success(Seq(42))

    waitForResult(value, Seq(42))
    waitForResult(as, Seq(42))


    updater ! "meep"

    waitForResult(value, Seq(42, 4))
    waitForResult(as, Seq(42, 4))

    as.unsubscribeAll()

    updater ! "yay"
    awaitAllTasks

    waitForResult(value, Seq(42, 4))
    waitForResult(as, Seq(42, 4))
  }

  test("events while subscribed but still loading") {
    val promise = Promise[Seq[Int]]()
    def loader() = promise.future
    val updater = EventStream[String]()

    val as = new AggregatingSignal[String, Seq[Int]](
      loader _,
      updater,
      (b, a) => b :+ a.length
    )

    val value = Signal(Seq.empty[Int])

    as.foreach { value ! _ }
    waitForResult(value, Seq.empty)
    assertEquals(as.value, None)

    updater ! "meep"
    updater ! "moop"
    updater ! "eek"

    waitForResult(value, Seq.empty)
    assertEquals(as.value, None)

    updater ! "!"
    promise.success(Seq(42))
    updater ! "supercalifragilisticexpialidocious"

    waitForResult(value, Seq(42, 4, 4, 3, 1, 34))
    waitForResult(as, Seq(42, 4, 4, 3, 1, 34))
  }

  // this test is a special case - we don't want to use `waitForResult` here because it wires the signal and we want to keep it un-wired on purpose
  test("reload on re-wire"){
    var promise = Promise[Seq[Int]]()
    def loader() = promise.future
    val updater = EventStream[String]()

    val as = new AggregatingSignal[String, Seq[Int]](
      loader _,
      updater,
      (b, a) => b :+ a.length
    )

    var value = Seq.empty[Int]

    as.foreach { value = _ }
    promise.success(Seq(42))

    updater ! "wow"
    updater ! "such"
    updater ! "publish"

    withDelay {
      assertEquals(value, Seq(42, 3, 4, 7))
      assertEquals(result(as.future), Seq(42, 3, 4, 7))
    }

    as.unsubscribeAll()

    withDelay {
      // still holds to the last computed value after unsubscribing
      assertEquals(value, Seq(42, 3, 4, 7))
      assertEquals(result(as.future), Seq(42, 3, 4, 7))
    }
    // triggers reload
    updater ! "publisher"

    withDelay {
      // still the old value
      assertEquals(value, Seq(42, 3, 4, 7))
      // a new value after reload
      assertEquals(result(as.future), Seq(42, 9))
    }

    promise = Promise[Seq[Int]]()
    as.foreach { value = _ }

    withDelay {
      assertEquals(value, Seq(42, 3, 4, 7))
      assertEquals(result(as.future), Seq(42, 9))
    }

    updater ! "much amaze"

    withDelay {
      assertEquals(value, Seq(42, 9, 10))
      assertEquals(result(as.future), Seq(42, 9, 10))
    }

    promise.success(Seq(42, 3, 4, 7, 9))

    withDelay {
      assertEquals(value, Seq(42, 3, 4, 7, 9, 10))
      assertEquals(result(as.future), Seq(42, 3, 4, 7, 9, 10))
    }

    updater ! "much"
    updater ! "amaze"

    withDelay {
      assertEquals(value, Seq(42, 3, 4, 7, 9, 10, 4, 5))
      assertEquals(result(as.future), Seq(42, 3, 4, 7, 9, 10, 4, 5))
    }
  }
