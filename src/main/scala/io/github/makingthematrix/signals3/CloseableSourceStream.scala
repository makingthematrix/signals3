package io.github.makingthematrix.signals3

import scala.concurrent.ExecutionContext

class CloseableSourceStream[E] extends SourceStream[E] with Closeable with Pausable {
	override def pause(): Boolean = if (!isClosed) super.pause() else false
	override def unpause(): Boolean = if (!isClosed) super.unpause() else false

	override def publish(event: E): Unit = 
		if (!isClosed && !isPaused) super.publish(event)
		
	override def publish(event: E, ec: ExecutionContext): Unit = 
		if (!isClosed && !isPaused) super.publish(event, ec)
}
