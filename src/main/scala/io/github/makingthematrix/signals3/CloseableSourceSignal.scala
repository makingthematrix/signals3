package io.github.makingthematrix.signals3

import scala.concurrent.ExecutionContext

class CloseableSourceSignal[V](v: Option[V]) extends SourceSignal[V](v) with Closeable {
	override def publish(value: V, ec: ExecutionContext): Unit = 
		if (!isClosed && !isPaused) super.publish(value, ec)
	override def publish(value: V): Unit = 
		if (!isClosed && !isPaused) super.publish(value)
}

object CloseableSourceSignal {
	def apply[V](v: V): CloseableSourceSignal[V] = new CloseableSourceSignal[V](Option(v)) 
}