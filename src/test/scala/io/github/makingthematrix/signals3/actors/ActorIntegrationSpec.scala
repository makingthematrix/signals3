package io.github.makingthematrix.signals3.actors

import io.github.makingthematrix.signals3.actors.Actor.{HeartBeatStrategy, PF}
import io.github.makingthematrix.signals3.testutils.*
import io.github.makingthematrix.signals3.{Closeable, CloseableFuture, EventContext, Pausable, Signal, SourceStream, Stream, Threading}
import munit.FunSuite

import scala.concurrent.duration.*

class ActorIntegrationSpec extends FunSuite {
  private val eventContext = EventContext()
  import Threading.defaultContext

  given Timeout: FiniteDuration = 2.seconds

  override def beforeEach(context: BeforeEach): Unit =
    eventContext.start()

  override def afterEach(context: AfterEach): Unit =
    eventContext.stop()

  private def close(actor: Actor[?, ?, ?] & Closeable): Unit = {
    actor.close()
    waitFor(actor.isClosedSignal, true)
  }

  private def create[Msg, Rsp, State](state: State, pf: PF[Msg, Rsp, State]): Actor[Msg, Rsp, State] & Closeable & Pausable =
    Actor[Msg, Rsp, State](state, pf).asInstanceOf[ActorImpl[Msg, Rsp, State]]

  private def create[Msg, Rsp, State](state: State, pf: PF[Msg, Rsp, State], hbs: HeartBeatStrategy): Actor[Msg, Rsp, State] & Closeable & Pausable =
    Actor[Msg, Rsp, State](state, pf, hbs).asInstanceOf[ActorImpl[Msg, Rsp, State]]

  // ==================== Actor-to-Actor Communication ====================

  test("Actor pipeline: message flows through multiple actors") {
    val inputStream: SourceStream[Int] = Stream()
    val outputSignal = Signal(Seq.empty[String])

    // Actor1: adds 10 to input
    val actor1 = create[Int, Int, Unit]((), { case (msg, _) => Some(msg + 10) })
    // Actor2: converts to string
    val actor2 = create[Int, String, Unit]((), { case (msg, _) => Some(s"Result: $msg") })

    // Pipe: inputStream -> actor1.in -> actor1.out -> actor2.in -> actor2.out -> outputSignal
    inputStream.pipeTo(actor1.in)
    actor1.out.pipeTo(actor2.in)
    actor2.out.foreach { result => outputSignal.mutate(_ :+ result) }

    // Send test data
    inputStream ! 5
    inputStream ! 20

    // Verify end-to-end result
    waitForResult(outputSignal, Seq("Result: 15", "Result: 30"))

    close(actor1)
    close(actor2)
  }

  // ==================== Bidirectional Actor Communication ====================

  test("Request-response between actors") {
    val requestor = create[String, String, Unit]((), { case (msg, _) => Some(s"Request: $msg") })
    val responder = create[String, String, Unit]((), { case (msg, _) => Some(s"Response to: $msg") })

    val finalResponse = Signal("")

    // Send request to requestor, get response, then send to responder
    val request = requestor ? "test"
    request.foreach { response =>
      responder ! response
    }

    // Capture responder's output
    responder.out.foreach { rsp => finalResponse ! rsp }

    waitFor(finalResponse, "Response to: Request: test")

    close(requestor)
    close(responder)
  }

  // ==================== Stream Processing with Actors ====================

  test("Actor as stream processor in a reactive pipeline") {
    val source = Stream[Int]()
    val processed = Signal(Seq.empty[String])
    val sink: SourceStream[String] = Stream()

    // Actor processes integers to strings with state
    val processor = create[Int, String, Int](0, { case (msg, actor) =>
      actor.state = actor.state + msg
      Some(s"Processed-${actor.state}")
    })

    // Build pipeline: source -> processor.in -> processor.out -> sink
    source.pipeTo(processor.in)
    processor.out.pipeTo(sink)

    // Subscribe to sink
    sink.foreach { msg => processed.mutate(_ :+ msg) }

    // Emit values
    source ! 1
    source ! 2
    source ! 3

    waitForResult(processed, Seq("Processed-1", "Processed-3", "Processed-6"))

    close(processor)
  }

  // ==================== Stateful Multi-Actor System ====================

  test("Coordinated state across multiple actors") {
    val counterActor = create[Unit, Int, Int](0, { case (_, actor) =>
      actor.state = actor.state + 1
      Some(actor.state)
    })

    val aggregatorActor = create[Int, Int, Int](0, { case (msg, actor) =>
      actor.state = actor.state + msg
      Some(actor.state)
    })

    val finalSum = Signal(0)

    // Send 5 increment messages to counter, collect results in aggregator
    val futures = (1 to 5).map { _ =>
      val cf = counterActor ? ()
      cf.foreach { result => aggregatorActor ! result }
      cf
    }

    // Get final aggregated sum
    (aggregatorActor ? 0).pipeTo(finalSum)

    CloseableFuture.sequence(futures)
    waitFor(finalSum, 15) // 1+2+3+4+5 = 15

    close(counterActor)
    close(aggregatorActor)
  }

  // ==================== Fan-out: One-to-Many Actor Communication ====================

  test("Fan-out: single producer to multiple consumer actors") {
    val producer = Stream[Int]()
    val consumer1Results = Signal(Seq.empty[String])
    val consumer2Results = Signal(Seq.empty[String])

    val consumer1 = create[Int, String, Unit]((), { case (msg, _) => Some(s"C1-$msg") })
    val consumer2 = create[Int, String, Unit]((), { case (msg, _) => Some(s"C2-$msg") })

    // Fan out: producer -> both consumers
    producer.pipeTo(consumer1.in)
    producer.pipeTo(consumer2.in)

    consumer1.out.foreach { msg => consumer1Results.mutate(_ :+ msg) }
    consumer2.out.foreach { msg => consumer2Results.mutate(_ :+ msg) }

    // Produce values
    producer ! 100
    producer ! 200

    waitForResult(consumer1Results, Seq("C1-100", "C1-200"))
    waitForResult(consumer2Results, Seq("C2-100", "C2-200"))

    close(consumer1)
    close(consumer2)
  }

  // ==================== Fan-in: Many-to-One Actor Communication ====================

  test("Fan-in: multiple producers to single consumer actor") {
    val producer1 = Stream[Int]()
    val producer2 = Stream[Int]()
    val combinedResults = Signal(Seq.empty[String])

    val consumer = create[Int, String, Unit]((), { case (msg, _) => Some(s"Combined-$msg") })

    // Fan in: both producers -> consumer
    producer1.pipeTo(consumer.in)
    producer2.pipeTo(consumer.in)

    consumer.out.foreach { msg => combinedResults.mutate(_ :+ msg) }

    // Produce from both sources
    producer1 ! 1
    producer2 ! 2
    producer1 ! 3
    producer2 ! 4

    waitForResult(combinedResults, Seq("Combined-1", "Combined-2", "Combined-3", "Combined-4"))

    close(consumer)
  }

  // ==================== Actor with Dynamic Behavior in Pipeline ====================

  test("Actor with dynamic behavior changes in a pipeline") {
    val source = Stream[String]()
    val results = Signal(Seq.empty[String])

    val processor = create[String, String, Unit]((), { case (msg, _) => Some(s"default-$msg") })

    source.pipeTo(processor.in)
    processor.out.foreach { msg => results.mutate(_ :+ msg) }

    // Initially uses default behavior
    source ! "first"
    waitForResult(results, Seq("default-first"))

    // Add special behavior for "special" messages via system message
    import processor.SystemMsg
    val specialBehavior: PF[String, String, Unit] = { case ("special", _) => Some("SPECIAL") }
    processor ! SystemMsg.AddBehavior("special", specialBehavior)
    Thread.sleep(200) // Wait for behavior to be added

    source ! "special"
    source ! "second"

    waitForResult(results, Seq("default-first", "SPECIAL", "default-second"))

    close(processor)
  }

  // ==================== Actor System Messages Through Pipeline ====================

  test("System messages work correctly in pipelined actors") {
    val source = Stream[Int]()
    val results = Signal(Seq.empty[String])

    // Actor that sends responses to out stream
    val actor = create[Int, String, Unit]((), { case (msg, actor) =>
      actor.out ! s"Processed-$msg"
      None
    })
    import actor.SystemMsg

    source.pipeTo(actor.in)
    actor.out.foreach { msg => results.mutate(_ :+ msg) }

    // Send a message
    source ! 1
    waitForResult(results, Seq("Processed-1"))

    // Pause the actor
    actor ! SystemMsg.Pause
    waitFor(actor.isPausedSignal, true)

    // Send while paused - should queue
    source ! 2
    Thread.sleep(100)
    // Result should still be just the first message
    assertEquals(results.currentValue, Some(Seq("Processed-1")))

    // Unpause
    actor ! SystemMsg.Unpause
    waitFor(actor.isPausedSignal, false)

    // Now the queued message should be processed
    waitForResult(results, Seq("Processed-1", "Processed-2"))

    close(actor)
  }

  // ==================== Complex: Worker Pool Pattern ====================

  test("Worker pool pattern with multiple actors") {
    val tasks = Stream[Int]()
    val completedTasks = Signal(Seq.empty[Int])

    // Create 3 worker actors
    val workers = (1 to 3).map { _ =>
      create[Int, Int, Int](0, { case (msg, actor) =>
        actor.state = actor.state + msg
        Some(actor.state)
      })
    }

    // Simple round-robin distribution
    var workerIndex = 0

    tasks.foreach { task =>
      val worker = workers(workerIndex)
      workerIndex = (workerIndex + 1) % workers.length
      val cf = worker ? task
      cf.foreach { result => completedTasks.mutate(_ :+ result) }
    }

    // Send tasks
    tasks ! 1
    tasks ! 2
    tasks ! 3
    tasks ! 4
    tasks ! 5
    tasks ! 6

    // All tasks should complete (order may vary due to concurrency)
    // Wait for at least 6 results
    val expectedCount = 6
    val checkCount = () => completedTasks.currentValue.exists(_.length >= expectedCount)
    val offset = System.currentTimeMillis()
    while (System.currentTimeMillis() - offset < 3000 && !checkCount()) {
      Thread.sleep(100)
    }
    assert(checkCount(), s"Expected at least $expectedCount results, got ${completedTasks.currentValue.map(_.length).getOrElse(0)}")

    workers.foreach(close)
  }

  // ==================== Actor with Different Heartbeat Strategies in Pipeline ====================

  test("Pipeline with different heartbeat strategies") {
    val source = Stream[Int]()
    val results = Signal(Seq.empty[String])

    // Actor with Linear heartbeat
    val linearActor = create[Int, String, Unit]((), { case (msg, _) => Some(s"Linear-$msg") }, HeartBeatStrategy.Linear(50))
    // Actor with Reactive heartbeat
    val reactiveActor = create[String, String, Unit]((), { case (msg, _) => Some(s"Reactive-$msg") }, HeartBeatStrategy.Reactive(100, 2))

    // Chain: source -> linear -> reactive -> results
    source.pipeTo(linearActor.in)
    linearActor.out.pipeTo(reactiveActor.in)
    reactiveActor.out.foreach { msg => results.mutate(_ :+ msg) }

    source ! 1
    source ! 2

    waitForResult(results, Seq("Reactive-Linear-1", "Reactive-Linear-2"))

    close(linearActor)
    close(reactiveActor)
  }
}
