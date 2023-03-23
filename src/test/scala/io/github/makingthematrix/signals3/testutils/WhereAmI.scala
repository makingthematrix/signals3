package io.github.makingthematrix.signals3.testutils

import java.io.{PrintWriter, StringWriter}

object WhereAmI:
  class WhereAmI extends Exception("Where am I?")

  def whereAmI: String =
    val where = new WhereAmI
    val result = new StringWriter
    val printWriter = new PrintWriter(result)
    where.printStackTrace(printWriter)
    result.toString

  def whereAmI(throwable: Throwable): String =
    val result = new StringWriter
    val printWriter = new PrintWriter(result)
    throwable.printStackTrace(printWriter)
    result.toString
