package io.github.makingthematrix.signals3

import testutils.{awaitAllTasks, waitForResult}

import scala.concurrent.duration.*

class GeneratorsSpec extends munit.FunSuite:
  import EventContext.Implicits.global
  import Threading.defaultContext

  test("test heartbeat event stream") {
    var counter = 0
    val isSuccess = Signal(false)
    val stream = GeneratorEventStream.heartbeat(100.millis)
    stream.foreach { _ =>
      counter += 1
      if counter == 3 then isSuccess ! true
    }
    waitForResult(isSuccess, true)
    stream.stop()
    awaitAllTasks
    assert(stream.isStopped)
  }
