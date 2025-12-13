package io.github.makingthematrix.signals3

import testutils.{awaitAllTasks, result, waitForResult}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*
import scala.concurrent.{Future, Promise}
import scala.language.postfixOps

class StreamSpec extends munit.FunSuite {
  import EventContext.Implicits.global
  import Threading.defaultContext

  test("unsubscribe from source and current mapped signal on onUnwire") {
    val a: SourceStream[Int] = Stream()
    val b: SourceStream[Int] = Stream()

    val subscription = a.flatMap(_ => b).onCurrent { _ => }
    a ! 1

    assert(b.hasSubscribers, "mapped stream should have subscriber after element emitting from source stream")

    subscription.unsubscribe()

    assert(!a.hasSubscribers, "source stream should have no subscribers after onUnwire was called on FlatMapLatestEventStream")
    assert(!b.hasSubscribers, "mapped stream should have no subscribers after onUnwire was called on FlatMapLatestEventStream")
  }

  test("discard old mapped stream when new element emitted from source stream") {
    val a: SourceStream[String] = Stream()
    val b: SourceStream[String] = Stream()
    val c: SourceStream[String] = Stream()

    var flatMapCalledCount = 0
    var lastReceivedElement: Option[String] = None
    val subscription = a.flatMap { _ =>
      val count = if flatMapCalledCount == 0 then b else c
      flatMapCalledCount += 1
      count
    }.onCurrent {
      elem => lastReceivedElement = Some(elem)
    }

    a ! "a"

    assert(b.hasSubscribers, "mapped stream 'b' should have subscriber after first element emitting from source stream")

    b ! "b"

    assertEquals(lastReceivedElement, Some("b"), "flatMapLatest stream should provide events emitted from mapped signal 'b'")

    a ! "a"

    assert(!b.hasSubscribers, "mapped stream 'b' should have no subscribers after second element emitting from source stream")

    assert(c.hasSubscribers, "mapped stream 'c' should have subscriber after second element emitting from source stream")

    c ! "c"
    b ! "b"

    assertEquals(lastReceivedElement, Some("c")) // flatMapLatest stream should provide events emitted from mapped signal 'c'

    subscription.unsubscribe()
  }

  test("emit an event when a future is successfully completed") {
    given dq: DispatchQueue = SerialDispatchQueue()
    val promise = Promise[Int]()
    val resPromise = Promise[Int]()

    Stream.from(promise.future).onCurrent { event =>
      assertEquals(event, 1)
      resPromise.success(event)
    }

    testutils.withDelay(promise.success(1))

    assertEquals(testutils.result(resPromise.future), 1)
  }

  test("don't emit an event when a future is completed with a failure") {
    val promise = Promise[Int]()
    val resPromise = Promise[Int]()

    Stream.from(promise.future).onCurrent { event => resPromise.success(event) }

    promise.failure(new IllegalArgumentException)

    assert(testutils.tryResult(resPromise.future)(using 1 seconds).isFailure)
  }

  test("Join two streams and emit an event coming from either of them") {
    val stream1 = Stream[Int]()
    val stream2 = Stream[Int]()
    val join = Stream.join(stream1, stream2)

    val expected = Signal(0)
    val eventReceived = Signal(false)
    join.foreach { n =>
      eventReceived ! true
      waitForResult(expected, n)
    }

    def test(n: Int, source: SourceStream[Int]): Unit = {
      eventReceived ! false
      expected ! n
      source ! n
      waitForResult(eventReceived, true)
    }

    test(1, stream1)
    test(2, stream2)
    test(3, stream1)
    test(4, stream2)
  }

  test("Join the first stream with another and emit an event coming from either of them") {
    val stream1 = Stream[Int]()
    val stream2 = Stream[Int]()
    val join = stream1 ::: stream2

    val expected = Signal(0)
    val eventReceived = Signal(false)
    join.foreach { n =>
      eventReceived ! true
      waitForResult(expected, n)
    }

    def test(n: Int, source: SourceStream[Int]): Unit = {
      eventReceived ! false
      expected ! n
      source ! n
      waitForResult(eventReceived, true)
    }

    test(1, stream1)
    test(2, stream2)
    test(3, stream1)
    test(4, stream2)
  }

  test("Join a stream with a future and emit an event coming from either of them") {
    val stream = Stream[Int]()
    val promise = Promise[Int]()
    val future = promise.future
    val join = future :: stream

    val expected = Signal(0)
    val eventReceived = Signal(false)
    join.foreach { n =>
      eventReceived ! true
      waitForResult(expected, n)
    }

    def tests(n: Int, source: SourceStream[Int]): Unit = {
      eventReceived ! false
      expected ! n
      source ! n
      waitForResult(eventReceived, true)
    }

    def testp(n: Int, promise: Promise[Int]): Unit = {
      eventReceived ! false
      expected ! n
      promise.success(n)
      waitForResult(eventReceived, true)
    }

    tests(1, stream)
    testp(2, promise)
    tests(3, stream)
  }

  test("pipe events from the first stream to another") {
    val stream1 = Stream[Int]()
    val stream2 = Stream[Int]()

    val expected = Signal(0)
    val eventReceived = Signal(false)
    stream1.pipeTo(stream2)
    stream2.foreach { n =>
      eventReceived ! true
      waitForResult(expected, n)
    }

    def test(n: Int): Unit = {
      eventReceived ! false
      expected ! n
      stream1 ! n
      waitForResult(eventReceived, true)
    }

    test(1)
    test(2)
    test(3)
    test(4)
  }

  test("pipe events with the | operator from the first stream to another") {
    val stream1 = Stream[Int]()
    val stream2 = Stream[Int]()

    val expected = Signal(0)
    val eventReceived = Signal(false)
    stream1 | stream2
    stream2.foreach { n =>
      eventReceived ! true
      waitForResult(expected, n)
    }

    def test(n: Int): Unit = {
      eventReceived ! false
      expected ! n
      stream1 ! n
      waitForResult(eventReceived, true)
    }

    test(1)
    test(2)
    test(3)
    test(4)
  }

  test("create a stream from a signal") {
    val signal = Signal[Int]()
    val stream = Stream.from(signal)

    val expected = Signal(0)
    val eventReceived = Signal(false)
    stream.foreach { n =>
      eventReceived ! true
      waitForResult(expected, n)
    }

    def test(n: Int): Unit = {
      eventReceived ! false
      expected ! n
      signal ! n
      waitForResult(eventReceived, true)
    }

    test(1)
    test(2)
    test(3)
    test(4)
  }

  test("create a stream from a future") {
    val promise = Promise[Int]()
    val stream = Stream.from(promise.future)

    val received = Signal(0)
    stream.foreach { n =>
      received ! n
    }

    promise.success(1)
    waitForResult(received, 1)
  }

  test("create a stream from a future on a separate execution context") {
    given dq: DispatchQueue = SerialDispatchQueue()
    val promise = Promise[Int]()
    val stream = Stream.from(promise.future, dq)

    val received = Signal(0)
    stream.foreach { n =>
      received ! n
    }

    promise.success(1)
    waitForResult(received, 1)
  }

  test("ensure mapSync maintains the order of mapped events") {
    given dq: DispatchQueue = UnlimitedDispatchQueue()

    val source = Stream[Int]()

    val mappedSync = source.mapSync { n =>
      if n % 2 == 0 then Future {
        Thread.sleep(500)
        n + 100
      } else Future {
        n + 100
      }
    }

    val resultsSync = Signal(Seq.empty[Int])

    mappedSync.foreach { n =>
      resultsSync.mutate(_ :+ n)
    }

    source ! 2
    source ! 3
    source ! 4
    source ! 5

    waitForResult(resultsSync, Seq(102, 103, 104, 105))
  }

  test("filter numbers to even and odd") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val numbers = Seq(1, 2, 3, 4, 5, 6, 7, 8, 9)

    val source = Stream[Int]()
    val evenEvents = source.filter(_ % 2 == 0)
    val oddEvents = source.filter(_ % 2 != 0)

    var evenResults = List[Int]()
    var oddResults = List[Int]()
    val waitForMe = Promise[Unit]()

    def add(n: Int, toEven: Boolean) = {
      if toEven then evenResults :+= n else oddResults :+= n
      if evenResults.length + oddResults.length == numbers.length then waitForMe.success(())
    }

    evenEvents.foreach(add(_, toEven = true))
    oddEvents.foreach(add(_, toEven = false))

    numbers.foreach(source ! _)

    result(waitForMe.future)

    assertEquals(evenResults, List(2, 4, 6, 8))
    assertEquals(oddResults, List(1, 3, 5, 7, 9))
  }

  test("collect only odd numbers and add 100 to them") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val numbers = Seq(1, 2, 3, 4, 5, 6, 7, 8, 9)

    val source = Stream[Int]()
    val oddEvents = source.collect { case n if n % 2 != 0 => n + 100 }

    var oddResults = List[Int]()
    val waitForMe = Promise[Unit]()

    oddEvents.foreach { n =>
      oddResults :+= n
      if oddResults.length == 5 then waitForMe.success(())
    }

    numbers.foreach(source ! _)

    result(waitForMe.future)

    assertEquals(oddResults, List(101, 103, 105, 107, 109))
  }

  test("scan the numbers to create their multiplication") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val numbers = Seq(1, 2, 3, 4, 5, 6, 7, 8, 9)

    val source = Stream[Int]()
    val scanned = source.scan(1)(_ * _)

    var oddResults = List[Int]()
    val waitForMe = Promise[Unit]()

    scanned.foreach { n =>
      oddResults :+= n
      if oddResults.length == numbers.length then waitForMe.success(())
    }

    numbers.foreach(source ! _)

    result(waitForMe.future)

    assertEquals(oddResults, List(1, 2, 6, 24, 120, 720, 5040, 40320, 362880))
  }

  test("Take the next event in the stream as a closeable future") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val source = Stream[Int]()
    var results = List[Int]()

    var intercepted = -1
    source.foreach(results :+= _)

    source ! 1
    awaitAllTasks
    source.next.foreach(intercepted = _)

    source ! 2
    awaitAllTasks

    assertEquals(intercepted, 2)
    assertEquals(results, List(1, 2))

    source ! 3
    awaitAllTasks

    assertEquals(intercepted, 2)
    assertEquals(results, List(1, 2, 3))
  }

  test("Turn a stream of booleans to a stream of units") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val booleans = List(true, false, true, false, true)

    val source = Stream[Boolean]()
    var howMuchTrue = 0
    var howMuchFalse = 0

    source.ifTrue.foreach { _ => howMuchTrue += 1 }
    source.ifFalse.foreach { _ => howMuchFalse += 1 }

    booleans.foreach(source ! _)
    awaitAllTasks

    assertEquals(howMuchTrue, 3)
    assertEquals(howMuchFalse, 2)
  }

  test("Flip the boolean stream with the .not method") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val booleans = List(true, false, true, false, true)

    val source = Stream[Boolean]()
    val flipped = source.not
    
    var howMuchTrue = 0
    var howMuchFalse = 0

    flipped.ifTrue.foreach { _ => howMuchTrue += 1 }
    flipped.ifFalse.foreach { _ => howMuchFalse += 1 }

    booleans.foreach(source ! _)
    awaitAllTasks

    assertEquals(howMuchTrue, 2)
    assertEquals(howMuchFalse, 3)
  }

  test("Group the stream") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val a: SourceStream[Int] = Stream()
    val b: Stream[Seq[Int]] = a.grouped(2)

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

  test("Group the stream by the first letter") {
    given dq: DispatchQueue = SerialDispatchQueue()

    val a: SourceStream[String] = Stream()
    var lastFirstLetter: Option[Char] = None
    val b: Stream[Seq[String]] = a.groupBy { str =>
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
  }}
