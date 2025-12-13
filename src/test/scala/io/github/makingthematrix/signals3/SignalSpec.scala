package io.github.makingthematrix.signals3

import io.github.makingthematrix.signals3.Closeable.CloseableSignal

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, CyclicBarrier, TimeUnit}
import testutils.*

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import scala.concurrent.*
import scala.concurrent.duration.*
import scala.language.postfixOps

class SignalSpec extends munit.FunSuite {
  private val eventContext = EventContext()
  import Threading.defaultContext

  override def beforeEach(context: BeforeEach): Unit =
    eventContext.start()

  override def afterEach(context: AfterEach): Unit =
    eventContext.stop()

  test("Receive initial value") {
    val received = Signal(Seq.empty[Int])
    val capture: Int => Unit = { value => received.mutate(_ :+ value) }

    val s = Signal(1)
    s.foreach(capture)

    waitForResult(received, Seq(1))
  }

  test("Basic subscriber lifecycle") {
    val s = Signal(1)
    assert(!s.hasSubscribers)
    val sub = s.foreach { _ => () }
    assert(s.hasSubscribers)
    sub.destroy()
    assert(!s.hasSubscribers)
  }

  test("Don't receive events after unregistering a single subscriber") {
    val received = Signal(Seq.empty[Int])
    val capture: Int => Unit = { value => received.mutate(_ :+ value) }

    val s = Signal(1)
    val sub = s.foreach(capture)

    waitForResult(received, Seq(1))

    s ! 2

    waitForResult(received, Seq(1, 2))

    sub.destroy()
    s ! 3

    waitForResult(received, Seq(1, 2))
    capture(4) // to ensure '3' doesn't just come late
    waitForResult(received, Seq(1, 2, 4))
  }

  test("Don't receive events after unregistering all subscribers") {
    val received = Signal(Seq.empty[Int])
    val capture: Int => Unit = { value => received.mutate(_ :+ value) }

    val s = Signal(1)
    s.foreach(capture)

    waitForResult(received, Seq(1))

    s ! 2
    waitForResult(received, Seq(1, 2))

    s.unsubscribeAll()
    s ! 3

    waitForResult(received, Seq(1, 2))
    capture(4) // to ensure '3' doesn't just come late
    waitForResult(received, Seq(1, 2, 4))
  }

  test("Signal mutation") {
    val received = Signal(Seq.empty[Int])
    val capture: Int => Unit = { value => received.mutate(_ :+ value) }

    val s = Signal(42)
    s.foreach(capture)
    waitForResult(received, Seq(42))
    s.mutate(_ + 1)
    waitForResult(received, Seq(42, 43))
    s.mutate(_ - 1)
    waitForResult(received, Seq(42, 43, 42))
  }

  test("Don't send the same value twice") {
    val received = Signal(Seq.empty[Int])
    val capture: Int => Unit = { value => received.mutate(_ :+ value) }

    val s = Signal(1)
    s.foreach(capture)
    Seq(1, 2, 2, 1).foreach { n =>
      s ! n
      waitForResult(s, n)
    }
    waitForResult(received, Seq(1, 2, 1))
  }

  test("Idempotent signal mutation") {
    val received = Signal(Seq.empty[Int])
    val capture: Int => Unit = { value => received.mutate(_ :+ value) }

    val s = Signal(42)
    s.foreach(capture)
    waitForResult(received, Seq(42))
    s.mutate(_ + 1 - 1)
    waitForResult(received, Seq(42))
  }

  test("Simple for comprehension") {
    val received = Signal(Seq.empty[Int])
    val capture: Int => Unit = { value => received.mutate(_ :+ value) }

    val s = Signal(0)
    val s1 = Signal.const(1)
    val s2 = Signal.const(2)
    val r = for {
      x <- s
      y <- Seq(s1, s2)(x)
    }
    yield y * 2
    r.foreach(capture)
    s ! 1
    waitForResult(received, Seq(2, 4))
  }

  test("Many concurrent subscriber changes") {
    given executionContext: ExecutionContext = UnlimitedDispatchQueue()
    val barrier = new CyclicBarrier(50)
    val num = new AtomicInteger(0)
    val s = Signal(0)

    def add(barrier: CyclicBarrier): Future[Subscription] = Future(blocking {
      barrier.await()
      s.onCurrent { _ => num.incrementAndGet() }
    })

    val subs = Await.result(Future.sequence(Seq.fill(50)(add(barrier))), 10.seconds)
    assert(s.hasSubscribers)
    assertEquals(num.getAndSet(0), 50)

    s ! 42
    assertEquals(num.getAndSet(0), 50)

    val chaosBarrier = new CyclicBarrier(75)
    val removals = Future.traverse(subs.take(25))(sub => Future(blocking {
      chaosBarrier.await()
      sub.destroy()
    }))
    val adding = Future.sequence(Seq.fill(25)(add(chaosBarrier)))
    val sending = Future.traverse((1 to 25).toList)(n => Future(blocking {
      chaosBarrier.await()
      s ! n
    }))

    val moreSubs = Await.result(adding, 10.seconds)
    Await.result(removals, 10.seconds)
    Await.result(sending, 10.seconds)

    assert(num.get <= 75 * 25)
    assert(num.get >= 25 * 25)
    assert(s.hasSubscribers)

    barrier.reset()
    Await.result(Future.traverse(moreSubs ++ subs.drop(25))(sub => Future(blocking {
      barrier.await()
      sub.destroy()
    })), 10.seconds)
    assert(!s.hasSubscribers)
  }

  test("Concurrent updates with incremental values") {
    incrementalUpdates((s, r) => s.onCurrent {
      r.add
    })
  }

  test("Concurrent updates with incremental values with serial dispatch queue") {
    val dispatcher = SerialDispatchQueue()
    incrementalUpdates((s, r) => s.on(dispatcher) {
      r.add
    })
  }

  test("Concurrent updates with incremental values and onChanged subscriber") {
    incrementalUpdates((s, r) => s.onChanged.onCurrent {
      r.add
    })
  }

  test("Concurrent updates with incremental values and onChanged subscriber with serial dispatch queue") {
    val dispatcher = SerialDispatchQueue()
    incrementalUpdates((s, r) => s.onChanged.on(dispatcher) {
      r.add
    })
  }

  private def incrementalUpdates(onUpdate: (Signal[Int], ConcurrentLinkedQueue[Int]) => Unit): Unit = {
    given defaultContext: DispatchQueue = Threading.defaultContext
    100 times {
      val signal = Signal(0)
      val received = new ConcurrentLinkedQueue[Int]()

      onUpdate(signal, received)

      val send = new AtomicInteger(0)
      val done = new CountDownLatch(10)
      (1 to 10).foreach(_ => Future {
        for _ <- 1 to 100 do {
          val v = send.incrementAndGet()
          signal.mutate(_ max v)
        }
        done.countDown()
      })

      done.await(DefaultTimeout.toMillis, TimeUnit.MILLISECONDS)

      assertEquals(signal.currentValue.get, send.get())

      val arr = received.asScala.toVector
      assertEquals(arr, arr.sorted)
    }
  }

  test("Two concurrent dispatches (global event and background execution contexts)") {
    given defaultContext: DispatchQueue = Threading.defaultContext
    concurrentDispatches(2, 1000, EventContext.Global, Some(defaultContext), defaultContext)()
  }

  test("Several concurrent dispatches (global event and background execution contexts)") {
    given defaultContext: DispatchQueue = Threading.defaultContext
    concurrentDispatches(10, 200, EventContext.Global, Some(defaultContext), defaultContext)()
  }

  test("Many concurrent dispatches (global event and background execution contexts)") {
    given defaultContext: DispatchQueue = Threading.defaultContext
    concurrentDispatches(100, 200, EventContext.Global, Some(defaultContext), defaultContext)()
  }

  test("Two concurrent dispatches (subscriber on UI eventcontext)") {
    given defaultContext: DispatchQueue = Threading.defaultContext
    concurrentDispatches(2, 1000, eventContext, Some(defaultContext), defaultContext)()
  }

  test("Several concurrent dispatches (subscriber on UI event context)") {
    given defaultContext: DispatchQueue = Threading.defaultContext
    concurrentDispatches(10, 200, eventContext, Some(defaultContext), defaultContext)()
  }

  test("Many concurrent dispatches (subscriber on UI event context)") {
    given defaultContext: DispatchQueue = Threading.defaultContext
    concurrentDispatches(100, 100, eventContext, Some(defaultContext), defaultContext)()
  }

  test("Several concurrent dispatches (global event context, no source context)") {
    given defaultContext: DispatchQueue = Threading.defaultContext
    concurrentDispatches(10, 200, EventContext.Global, None, defaultContext)()
  }

  test("Several concurrent dispatches (subscriber on UI context, no source context)") {
    given defaultContext: DispatchQueue = Threading.defaultContext
    concurrentDispatches(10, 200, eventContext, None, defaultContext)()
  }

  test("Several concurrent mutations (subscriber on global event context)") {
    given defaultContext: DispatchQueue = Threading.defaultContext
    concurrentMutations(10, 200, EventContext.Global, defaultContext)()
  }

  test("Several concurrent mutations (subscriber on UI event context)") {
    given defaultContext: DispatchQueue = Threading.defaultContext
    concurrentMutations(10, 200, eventContext, defaultContext)()
  }

  private def concurrentDispatches(dispatches: Int,
                                   several: Int,
                                   eventContext: EventContext,
                                   dispatchExecutionContext: Option[ExecutionContext],
                                   actualExecutionContext: ExecutionContext
                                  )(subscribe: Signal[Int] => (Int => Unit) => Subscription = s => g => s.onCurrent(g)(using eventContext)): Unit =
    concurrentUpdates(dispatches, several, (s, n) => s.setValue(Some(n), dispatchExecutionContext), actualExecutionContext, subscribe)

  private def concurrentMutations(dispatches: Int,
                                  several: Int,
                                  eventContext: EventContext,
                                  actualExecutionContext: ExecutionContext
                                 )(subscribe: Signal[Int] => (Int => Unit) => Subscription = s => g => s.onCurrent(g)(using eventContext)): Unit =
    concurrentUpdates(dispatches, several, (s, n) => s.mutate(_ + n), actualExecutionContext, subscribe, _.currentValue.get == 55)

  private def concurrentUpdates(dispatches: Int,
                                several: Int,
                                f: (SourceSignal[Int], Int) => Unit,
                                actualExecutionContext: ExecutionContext,
                                subscribe: Signal[Int] => (Int => Unit) => Subscription,
                                additionalAssert: Signal[Int] => Boolean = _ => true): Unit =
    several times {
      val signal = Signal(0)

      @volatile var lastSent = 0
      val received = new AtomicInteger(0)
      val p = Promise[Unit]()

      val subscriber = subscribe(signal) { i =>
        lastSent = i
        if received.incrementAndGet() == dispatches + 1 then p.trySuccess({})
      }

      (1 to dispatches).foreach(n => Future(f(signal, n))(using actualExecutionContext))

      result(p.future)

      assert(additionalAssert(signal))
      assertEquals(signal.currentValue.get, lastSent)
    }

  test("An uninitialized signal should not contain any value") {
    given defaultContext: DispatchQueue = Threading.defaultContext
    val s = Signal.empty[Int]
    assert(!result(s.contains(1)))
  }

  test("An initialized signal should contain the value it's initialized with") {
    given defaultContext: DispatchQueue = Threading.defaultContext
    val s = Signal.const(2)
    assert(!result(s.contains(1)))
    assert(result(s.contains(2)))
  }

  test("The 'exists' check for an uninitialized signal should always return false") {
    given defaultContext: DispatchQueue = Threading.defaultContext
    val s = Signal.empty[FiniteDuration]
    assert(!result(s.exists(d => d.toMillis == 5)))
  }

  test("The 'exists' check for an initialized signal should work accordingly") { //
    given defaultContext: DispatchQueue = Threading.defaultContext// I know, stupid name
    val s = Signal.const(5.millis)
    assert(!result(s.exists(d => d.toMillis == 2)))
    assert(result(s.exists(d => d.toMillis == 5)))
  }

  test("After a signal is initialized, onUpdated should return the new value, and no old value") {
    val s = Signal[Int]()
    val p = Promise[(Option[Int], Int)]()

    s.onUpdated.onCurrent { case (oldValue, newValue) => p.success((oldValue, newValue)) }

    s ! 1

    assertEquals(result(p.future), (None, 1))
  }

  test("After the value is changed, onUpdated should return the new value, and the old value") {
    given ec: ExecutionContext = SerialDispatchQueue()
    val s = Signal[Int](0)
    var p = Promise[(Option[Int], Int)]()

    s.onUpdated.onCurrent { case (oldValue, newValue) => p.success((oldValue, newValue)) }

    s ! 1

    assertEquals(result(p.future), (Some(0), 1))
    p = Promise()

    s ! 2

    assertEquals(result(p.future), (Some(1), 2))
  }

  test("After a signal is initialized, onChanged should return the new value") {
    val s = Signal[Int]()
    val p = Promise[Int]()

    s.onChanged.onCurrent { newValue => p.success(newValue) }

    s ! 1

    assertEquals(result(p.future), 1)
  }

  test("After the value is changed, onChanged should return only the new value") {
    given ec: ExecutionContext = SerialDispatchQueue()
    val s = Signal[Int](0)
    var p = Promise[Int]()

    var sub = s.onChanged.onCurrent(p.success)

    s ! 1

    assertEquals(result(p.future), 1)
    sub.destroy()
    p = Promise()
    sub = s.onChanged.onCurrent(p.success)

    s ! 2

    assertEquals(result(p.future), 2)
  }

  test("After zipping two signals, the zipped signal updates from both sources") {
    val s1 = Signal(0)
    val s2 = Signal("")
    val zipped = Signal.zip(s1, s2)

    assert(waitForResult(zipped, (0, "")))

    s1 ! 1

    assert(waitForResult(zipped, (1, "")))

    s2 ! "a"

    assert(waitForResult(zipped, (1, "a")))
  }

  test("Zip one signal with another and assert that the zipped signal updates from both sources") {
    val s1 = Signal(0)
    val s2 = Signal("")
    val zipped = s1.zip(s2)

    assert(waitForResult(zipped, (0, "")))

    s1 ! 1

    assert(waitForResult(zipped, (1, "")))

    s2 ! "a"

    assert(waitForResult(zipped, (1, "a")))
  }

  test("Map one signal to another") {
    val s1 = Signal(0)
    val mapped = s1.map(n => s"number: $n")

    assert(waitForResult(mapped, "number: 0"))

    s1 ! 1

    assert(waitForResult(mapped, "number: 1"))
  }

  test("Index a signal") {
    val a = Signal(0)
    val b = a.indexed

    assertEquals(b.counter, 0)

    a ! -1
    assert(waitForResult(b, -1))
    assertEquals(b.counter, 1)


    a ! -2
    assert(waitForResult(b, -2))
    assertEquals(b.counter, 2)
  }

  test("Only real changes are counted") {
    val a = Signal(0)
    val b = a.indexed

    assertEquals(b.counter, 0)

    a ! 0
    assert(waitForResult(b, 0))
    assertEquals(b.counter, 0)

    a ! 1
    assert(waitForResult(b, 1))
    assertEquals(b.counter, 1)

    a ! 1
    assert(waitForResult(b, 1))
    assertEquals(b.counter, 1)
  }

  test("Drop one change") {
    val a = Signal(0)
    val b = a.drop(1)
    assert(waitForResult(a, 0))
    assert(waitForResult(b, 0))

    a ! 1
    assert(waitForResult(a, 1))
    assert(waitForResult(b, 0))

    a ! 2
    assert(waitForResult(a, 2))
    assert(waitForResult(b, 2))
  }

  test("Drop one change when starting from an empty signal") {
    val a = Signal[Int]()
    val b = a.drop(1)

    assert(b.empty)

    a ! 1
    assert(waitForResult(a, 1))
    assert(b.empty)

    a ! 2
    assert(waitForResult(a, 2))
    assert(waitForResult(b, 2))
  }

  test("Drop and map") {
    val a = Signal[Int]()
    val b = a.drop(2).map(_.toString)

    val buffer = mutable.ArrayBuilder.make[String]
    b.foreach { str =>
      buffer.addOne(str)
    }

    a ! 1
    assert(waitForResult(a, 1))
    a ! 2
    assert(waitForResult(a, 2))
    a ! 3
    assert(waitForResult(a, 3))
    a ! 4
    assert(waitForResult(a, 4))

    val seq = buffer.result().toSeq
    assertEquals(seq, Seq("3", "4"))
  }

  test("Close a signal manually") {
    val a = Signal[Int]()
    val b: CloseableSignal[Int] = a.closeable

    val buffer = mutable.ArrayBuilder.make[Int]
    b.foreach { n =>
      buffer.addOne(n)
    }

    a ! 1
    assert(waitForResult(a, 1))
    a ! 2
    assert(waitForResult(a, 2))

    b.close()
    assert(b.isClosed)

    a ! 3
    assert(waitForResult(a, 3))
    a ! 4
    assert(waitForResult(a, 4))

    val seq = buffer.result().toSeq
    assertEquals(seq, Seq(1, 2))
  }

  test("Drop value changes until a condition") {
    given DispatchQueue = SerialDispatchQueue()
    val a = Signal[String]()

    val b = a.dropWhile(str => str.toInt < 3)

    val buffer = mutable.ArrayBuilder.make[Int]
    b.foreach { str => buffer.addOne(str.toInt) }

    a ! "1"
    awaitAllTasks
    a ! "2"
    awaitAllTasks
    a ! "3"
    awaitAllTasks
    a ! "4"
    awaitAllTasks

    assertEquals(buffer.result().toSeq, Seq(3, 4))
  }



  test("Split events with a condition") {
    given DispatchQueue = SerialDispatchQueue()
    val a = Signal[String]()
    val (b, c) = a.splitAt(_.toInt < 3)

    val bBuffer = mutable.ArrayBuilder.make[Int]
    b.foreach { str => bBuffer.addOne(str.toInt) }
    val cBuffer = mutable.ArrayBuilder.make[Int]
    c.foreach { str => cBuffer.addOne(str.toInt) }

    a ! "1"
    awaitAllTasks
    a ! "2"
    awaitAllTasks
    a ! "3"
    awaitAllTasks
    a ! "4"
    awaitAllTasks

    assertEquals(bBuffer.result().toSeq, Seq(1, 2))
    assertEquals(cBuffer.result().toSeq, Seq(3, 4))
  }

  test("A signal mapped from an empty signal stays empty") {
    val s1 = Signal.empty[Int]
    val mapped = s1.map(n => s"number: $n")

    assert(!waitForResult(mapped, "number: 0", 1.second))
  }

  test("filter numbers to even and odd") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val numbers = Seq(1, 2, 3, 4, 5, 6, 7, 8, 9)

    val source = Signal[Int]()
    val evenNumbers = source.filter(_ % 2 == 0)
    val oddNumbers = source.filter(_ % 2 != 0)

    var evenResults = List[Int]()
    var oddResults = List[Int]()
    val waitForMe = Promise[Unit]()

    def add(n: Int, toEven: Boolean) = {
      if toEven then evenResults :+= n else oddResults :+= n
      if evenResults.length + oddResults.length == numbers.length then waitForMe.success(())
    }

    evenNumbers.foreach(add(_, toEven = true))
    oddNumbers.foreach(add(_, toEven = false))

    numbers.foreach(source ! _)

    result(waitForMe.future)

    assertEquals(evenResults, List(2, 4, 6, 8))
    assertEquals(oddResults, List(1, 3, 5, 7, 9))
  }

  test("Call onTrue when a boolean signal becomes true for the first time") {
    given defaultContext: DispatchQueue = Threading.defaultContext

    val s = Signal[Boolean]()
    var res = false
    s.onTrue.foreach { _ => res = true }

    assert(!res)

    s ! false

    assert(waitForResult(s, false))
    assert(!res)

    s ! true

    assert(waitForResult(s, true))
    assert(res)
  }

  test("Don't call onTrue again when a boolean signal becomes true") {
    given defaultContext: DispatchQueue = Threading.defaultContext

    val s = Signal[Boolean]()
    var res = 0
    s.onTrue.foreach { _ => res += 1 }

    assertEquals(res, 0)

    s ! false

    assert(waitForResult(s, false))
    assertEquals(res, 0)

    s ! true

    assert(waitForResult(s, true))
    assertEquals(res, 1)

    s ! false

    assert(waitForResult(s, false))
    assertEquals(res, 1)

    s ! true

    assert(waitForResult(s, true))
    assertEquals(res, 1)
  }

  test("Call onFalse when a boolean signal becomes false for the first time") {
    given defaultContext: DispatchQueue = Threading.defaultContext

    val s = Signal[Boolean]()
    var res = true
    s.onFalse.foreach { _ => res = false }

    assert(res)

    s ! true

    assert(waitForResult(s, true))
    assert(res)

    s ! false

    assert(waitForResult(s, false))
    assert(!res)
  }

  test("Don't call onFalse again when a boolean signal becomes false") {
    given defaultContext: DispatchQueue = Threading.defaultContext

    val s = Signal[Boolean]()
    var res = 0
    s.onFalse.foreach { _ => res += 1 }

    assertEquals(res, 0)

    s ! true

    assert(waitForResult(s, true))
    assertEquals(res, 0)

    s ! false

    assert(waitForResult(s, false))
    assertEquals(res, 1)

    s ! true

    assert(waitForResult(s, true))
    assertEquals(res, 1)

    s ! false

    assert(waitForResult(s, false))
    assertEquals(res, 1)
  }


  test("Collect one signal to another") {
    val s1 = Signal(0)
    val collected = s1.collect { case n if n % 2 == 0 => s"number: $n" }

    assert(waitForResult(collected, "number: 0", 500.millis))

    s1 ! 1

    assert(!waitForResult(collected, "number: 1", 500.millis))

    s1 ! 2

    assert(waitForResult(collected, "number: 2", 500.millis))

    s1 ! 3

    assert(!waitForResult(collected, "number: 3", 500.millis))

    s1 ! 4

    assert(waitForResult(collected, "number: 4", 500.millis))
  }

  test("Combining two signals") {
    val signalA = Signal(1)
    val signalB = Signal(true)
    val chain = signalA.combine(signalB) { case (n, b) => s"$n:$b" }
    assert(waitForResult(chain, "1:true"))

    signalA ! 2

    assert(waitForResult(chain, "2:true"))

    signalB ! false

    assert(waitForResult(chain, "2:false"))

    signalB ! true

    assert(waitForResult(chain, "2:true"))

    signalA ! 42
    signalB ! false

    assert(waitForResult(chain, "42:false"))
  }

  test("Fallback to another signal if the original one is empty") {
    val s1 = Signal[Int]()
    val s2 = Signal[Int](2)

    val res = s1.orElse(s2)

    assert(waitForResult(res, 2))

    s1 ! 1

    assert(waitForResult(res, 1))
  }

  test("Fallback to another signal if the original one switch empty") {
    val s1 = Signal[Boolean](true)
    val s2 = Signal[Int](2)
    val s3 = s1.flatMap {
      case true  => Signal.const(1)
      case false => Signal.empty[Int]
    }

    val res = s3.orElse(s2)

    assert(waitForResult(res, 1))

    s1 ! false

    assert(waitForResult(res, 2))

    s2 ! 3

    assert(waitForResult(res, 3))

    s1 ! true

    assert(waitForResult(res, 1))

    s2 ! 4

    assert(waitForResult(res, 1))
  }

  test("Fallback to a signal of another type if the original one is empty") {
    val s1 = Signal[Int]()
    val s2 = Signal[String]("a")

    val res = s1.either(s2)

    assert(waitForResult(res, Left("a")))

    s1 ! 1

    assert(waitForResult(res, Right(1)))
  }

  test("Fallback to a signal of another type if the original one switch empty") {
    val s1 = Signal[Boolean](true)
    val s2 = Signal[String]("a")
    val s3 = s1.flatMap {
      case true  => Signal.const(1)
      case false => Signal.empty[Int]
    }

    val res = s3.either(s2)

    assert(waitForResult(res, Right(1)))

    s1 ! false

    assert(waitForResult(res, Left("a")))

    s2 ! "b"

    assert(waitForResult(res, Left("b")))

    s1 ! true

    assert(waitForResult(res, Right(1)))

    s2 ! "c"

    assert(waitForResult(res, Right(1)))
  }

  test("Pipe events from one signal to another") {
    val s1 = Signal(1)
    val s2 = Signal[Int]()

    s1.pipeTo(s2)

    assert(waitForResult(s2, 1))

    s2 ! 2

    assert(waitForResult(s2, 2))

    s1 ! 3

    assert(waitForResult(s2, 3))
  }

  test("Pipe events from one signal to another with the operator |") {
    val s1 = Signal(1)
    val s2 = Signal[Int]()

    s1 | s2

    assert(waitForResult(s2, 1))

    s2 ! 2

    assert(waitForResult(s2, 2))

    s1 ! 3

    assert(waitForResult(s2, 3))
  }

  test("Compare values of two signals and create a boolean signal") {
    val s1 = Signal(1)
    assert(!s1.empty)
    val s2 = Signal[Int]()
    assert(s2.empty)

    val res: Signal[Boolean] = s1 === s2
    assert(res.empty)

    s2 ! 1
    assert(waitForResult(s2, 1))
    assert(waitForResult(res, true))

    s1 ! 2
    assert(waitForResult(s1, 2))
    assert(waitForResult(res, false))
    
    s2 ! 2
    assert(waitForResult(s2, 2))
    assert(waitForResult(res, true))
  }

  test("Compare values of two boolean signals with AND and create a new boolean signal") {
    val s1 = Signal[Boolean]()
    assert(s1.empty)
    val s2 = Signal[Boolean]()
    assert(s2.empty)

    val res: Signal[Boolean] = s1 && s2
    assert(res.empty)

    s1 ! true
    assert(waitForResult(s1, true))
    assert(res.empty)

    s2 ! true
    assert(waitForResult(s2, true))
    assert(waitForResult(res, true)) // true && true => true

    s1 ! false
    assert(waitForResult(s1, false))
    assert(waitForResult(res, false)) // false && true => false

    s2 ! false
    assert(waitForResult(s2, false))
    assert(waitForResult(res, false)) // false && false => false

    s1 ! true
    assert(waitForResult(s1, true))
    assert(waitForResult(res, false)) // true && false => false

    s2 ! true
    assert(waitForResult(s2, true))
    assert(waitForResult(res, true)) // true && true => true
  }

  test("Compare values of three boolean signals with AND and create a new boolean signal") {
    val s1 = Signal[Boolean](true)
    val s2 = Signal[Boolean](true)
    val s3 = Signal[Boolean](true)

    val res: Signal[Boolean] = Signal.and(s1, s2, s3)
    assert(waitForResult(res, true)) // true && true && true => true

    s1 ! false
    assert(waitForResult(res, false)) // false && true && true => false
    s1 ! true
    assert(waitForResult(res, true)) // true && true && true => true

    s2 ! false
    assert(waitForResult(res, false)) // true && false && true => false
    s2 ! true
    assert(waitForResult(res, true)) // true && true && true => true

    s3 ! false
    assert(waitForResult(res, false)) // true && true && false => false
    s3 ! true
    assert(waitForResult(res, true)) // true && true && true => true
  }

  test("Compare values of two boolean signals with OR and create a new boolean signal") {
    val s1 = Signal[Boolean]()
    assert(s1.empty)
    val s2 = Signal[Boolean]()
    assert(s2.empty)

    val res: Signal[Boolean] = s1 || s2
    assert(res.empty)

    s1 ! true
    assert(waitForResult(s1, true))
    assert(res.empty)

    s2 ! true
    assert(waitForResult(s2, true))
    assert(waitForResult(res, true)) // true || true => true

    s1 ! false
    assert(waitForResult(s1, false))
    assert(waitForResult(res, true)) // false || true => true

    s2 ! false
    assert(waitForResult(s2, false))
    assert(waitForResult(res, false)) // false || false => false

    s1 ! true
    assert(waitForResult(s1, true))
    assert(waitForResult(res, true)) // true || false => true

    s2 ! true
    assert(waitForResult(s2, true))
    assert(waitForResult(res, true)) // true || true => true
  }

  test("Compare values of three boolean signals with OR and create a new boolean signal") {
    val s1 = Signal[Boolean](false)
    val s2 = Signal[Boolean](false)
    val s3 = Signal[Boolean](false)

    val res: Signal[Boolean] = Signal.or(s1, s2, s3)
    assert(waitForResult(res, false)) // false && false && false => false

    s1 ! true
    assert(waitForResult(res, true)) // true && false && false => true
    s1 ! false
    assert(waitForResult(res, false)) // false && false && false => false

    s2 ! true
    assert(waitForResult(res, true)) // false && true && false => true
    s2 ! false
    assert(waitForResult(res, false)) // false && false && false => false

    s3 ! true
    assert(waitForResult(res, true)) // false && false && true => true
    s3 ! false
    assert(waitForResult(res, false)) // false && false && false => false
  }

  test("Compare values of two boolean signals with XOR and create a new boolean signal") {
    val s1 = Signal[Boolean]()
    assert(s1.empty)
    val s2 = Signal[Boolean]()
    assert(s2.empty)

    val res: Signal[Boolean] = s1 ^^ s2
    assert(res.empty)

    s1 ! true
    assert(waitForResult(s1, true))
    assert(res.empty)

    s2 ! true
    assert(waitForResult(s2, true))
    assert(waitForResult(res, false)) // true ^^ true => false

    s1 ! false
    assert(waitForResult(s1, false))
    assert(waitForResult(res, true)) // false ^^ true => true

    s2 ! false
    assert(waitForResult(s2, false))
    assert(waitForResult(res, false)) // false ^^ false => false

    s1 ! true
    assert(waitForResult(s1, true))
    assert(waitForResult(res, true)) // true ^^ false => true

    s2 ! true
    assert(waitForResult(s2, true))
    assert(waitForResult(res, false)) // true ^^ true => false
  }

  test("Compare values of two boolean signals with NOR and create a new boolean signal") {
    val s1 = Signal[Boolean]()
    assert(s1.empty)
    val s2 = Signal[Boolean]()
    assert(s2.empty)

    val res: Signal[Boolean] = s1 `nor` s2
    assert(res.empty)

    s1 ! true
    assert(waitForResult(s1, true))
    assert(res.empty)

    s2 ! true
    assert(waitForResult(s2, true))
    assert(waitForResult(res, false)) // true nor true => false

    s1 ! false
    assert(waitForResult(s1, false))
    assert(waitForResult(res, false)) // false nor true => false

    s2 ! false
    assert(waitForResult(s2, false))
    assert(waitForResult(res, true)) // false nor false => true

    s1 ! true
    assert(waitForResult(s1, true))
    assert(waitForResult(res, false)) // true nor false => false

    s2 ! true
    assert(waitForResult(s2, true))
    assert(waitForResult(res, false)) // true nor true => false
  }

  test("Compare values of two boolean signals with NAND and create a new boolean signal") {
    val s1 = Signal[Boolean]()
    assert(s1.empty)
    val s2 = Signal[Boolean]()
    assert(s2.empty)

    val res: Signal[Boolean] = s1 `nand` s2
    assert(res.empty)

    s1 ! true
    assert(waitForResult(s1, true))
    assert(res.empty)

    s2 ! true
    assert(waitForResult(s2, true))
    assert(waitForResult(res, false)) // true nand true => false

    s1 ! false
    assert(waitForResult(s1, false))
    assert(waitForResult(res, true)) // false nand true => true

    s2 ! false
    assert(waitForResult(s2, false))
    assert(waitForResult(res, true)) // false nand false => true

    s1 ! true
    assert(waitForResult(s1, true))
    assert(waitForResult(res, true)) // true nand false => true

    s2 ! true
    assert(waitForResult(s2, true))
    assert(waitForResult(res, false)) // true nand true => false
  }

  test("Flip the boolean signal with the .not method") {
    val source = Signal[Boolean]()
    assert(source.empty)
    val flipped = source.not
    assert(flipped.empty)

    source ! true
    assert(waitForResult(source, true))
    assert(waitForResult(flipped, false))

    source ! false
    assert(waitForResult(source, false))
    assert(waitForResult(flipped, true))
  }

  test("update the signal when a future is successfully completed") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val promise = Promise[Int]()
    val resPromise = Promise[Int]()

    Signal.from(promise.future).onCurrent { event =>
      assertEquals(event, 1)
      resPromise.success(event)
    }

    testutils.withDelay(promise.success(1))

    assertEquals(testutils.result(resPromise.future), 1)
  }
  
  test("don't update the signal when a future is completed with a failure") {
    val promise = Promise[Int]()
    val resPromise = Promise[Int]()

    Signal.from(promise.future).onCurrent { event => resPromise.success(event) }

    promise.failure(new IllegalArgumentException)

    assert(testutils.tryResult(resPromise.future)(using 1 seconds).isFailure)
  }

  test("Don't send updates through a closed signal") {
    val received = Signal(Seq.empty[Int])
    val capture: Int => Unit = { value => received.mutate(_ :+ value) }

    val s = Signal(1)
    val c = s.closeable
    c.foreach(capture)
    Seq(1, 2, 0, 1).foreach { n =>
      if n == 0 then c.close()
      s ! n
      waitForResult(s, n)
    }
    waitForResult(received, Seq(1, 2))
  }

  test("Group the signal") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val a: SourceSignal[Int] = Signal[Int]()
    val b: Signal[Seq[Int]] = a.grouped(2)

    val buffer = ArrayBuffer[Seq[Int]]()
    b.foreach(seq => buffer += seq)

    a ! 1
    awaitAllTasks
    a ! 2
    awaitAllTasks
    a ! 3
    awaitAllTasks
    a ! 4
    awaitAllTasks

    val res = buffer.toSeq
    assertEquals(res.size, 2)
    assertEquals(res(0), Seq(1, 2))
    assertEquals(res(1), Seq(3, 4))
  }

  test("Group the signal by the first letter") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val a: SourceSignal[String] = Signal[String]()
    var lastFirstLetter: Option[Char] = None
    val b: Signal[Seq[String]] = a.groupBy { str =>
      val res = if lastFirstLetter.isEmpty then false else !lastFirstLetter.contains(str.head)
      lastFirstLetter = Some(str.head)
      res
    }

    val buffer = ArrayBuffer[Seq[String]]()
    b.foreach(seq => buffer += seq)

    a ! "aa"
    awaitAllTasks
    a ! "ab"
    awaitAllTasks
    a ! "ba"
    awaitAllTasks
    a ! "bb"
    awaitAllTasks
    a ! "ac"
    awaitAllTasks

    val res = buffer.toSeq
    assertEquals(res.size, 2)
    assertEquals(res(0), Seq("aa", "ab"))
    assertEquals(res(1), Seq("ba", "bb")) // "ac" is never released
  }
}
