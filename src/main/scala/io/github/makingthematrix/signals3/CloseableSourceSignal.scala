package io.github.makingthematrix.signals3

import scala.concurrent.ExecutionContext

class CloseableSourceSignal[V](v: Option[V]) extends SourceSignal[V](v) with Closeable with Pausable {
	override def publish(value: V, ec: ExecutionContext): Unit = 
		if (!isClosed && !isPaused) super.publish(value, ec)
		
	override def publish(value: V): Unit = 
		if (!isClosed && !isPaused) super.publish(value)

	override protected[signals3] def update(f: Option[V] => Option[V], currentContext: Option[ExecutionContext]): Boolean = 
		if (!isClosed && !isPaused) super.update(f, currentContext) else false

	override protected[signals3] def updateWith(v: Option[V], currentContext: Option[ExecutionContext]): Boolean =
		if (!isClosed && !isPaused) super.updateWith(v, currentContext) else false
}

object CloseableSourceSignal {
	def apply[V](v: V): CloseableSourceSignal[V] = new CloseableSourceSignal[V](Option(v)) 
}