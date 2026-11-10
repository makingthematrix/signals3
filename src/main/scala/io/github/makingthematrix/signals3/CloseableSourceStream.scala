package io.github.makingthematrix.signals3

import scala.concurrent.ExecutionContext

class CloseableSourceStream[E] extends SourceStream[E] with Closeable with Pausable {
	override def pause()(using ExecutionContext): Unit = if (!isClosed) super.pause()

	override def unpause()(using ExecutionContext): Unit = if (!isClosed) super.unpause()

	override def publish(event: E): Unit = 
		if (!isClosed && !isPaused) super.publish(event)
		
	override def publish(event: E, ec: ExecutionContext): Unit = 
		if (!isClosed && !isPaused) super.publish(event, ec)
}
