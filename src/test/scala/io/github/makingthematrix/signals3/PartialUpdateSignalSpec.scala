package io.github.makingthematrix.signals3

import testutils.waitForResult

class PartialUpdateSignalSpec extends munit.FunSuite {

  import EventContext.Implicits.global
  import PartialUpdateSignalSpec._

  test("Basic") {

    val original = Signal(Data(0, 0))

    val updates = Signal(Seq.empty[Data])
    original.onPartialUpdate(_.value1).onCurrent { d =>
      updates.mutate(_ :+ d)
    }

    original ! Data(0, 1)

    original ! Data(0, 2)

    original ! Data(1, 2)

    original ! Data(1, 3)

    original ! Data(2, 3)

    waitForResult(updates, Seq(Data(0, 0), Data(1, 2), Data(2, 3)))
  }

  test("New subscribers get latest value even if the select doesn't match") {
    val original = Signal(Data(0, 0))

    original.onPartialUpdate(_.value1).onCurrent { d =>
      assertEquals(d, Data(0, 0))
    }

    original ! Data(0, 1)

    original.onPartialUpdate(_.value1).onCurrent { d =>
      assertEquals(d, Data(0, 1))
    }
  }
}

object PartialUpdateSignalSpec {
  final case class Data(value1: Int, value2: Int)
}
