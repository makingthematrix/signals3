package io.makingthematrix.signals3.ui

import io.makingthematrix.signals3.{DispatchQueue, EventContext, EventStream, Signal, Subscription}

/** This is a utility class to help you set up wire-signals to transport events between the default execution context
  * (`Threading.defaultContext`) and other custom contexts, and a secondary execution context usually associated with GUI.
  * On platforms such as Android or JavaFX, a `Runnable` task that involves changes to the app's GUI requires to be run
  * in a special execution context - otherwise it will either not work, will result in errors, or may even crash the app.
  * Your platform should provide you with a method which you can call with the given task to execute it properly.
  * If that method is of the type `Runnable => Unit` (or if you can wrap it in a function of this type) you can pass it
  * to `UiDispatchQueue` in the app's initialization code. Later, `UiDispatchQueue` will let you use extension methods
  * `.onUi` on event streams and signals. They work exactly like `.foreach` but they will run the subscriber code in
  * your GUI platform execution context.
  *
  * Examples:
  *
  * ### Android initialization:
  *
  * ```
  * import android.os.{Handler, Looper}
  * import com.wire.signals.ui.UiDispatchQueue
  *
  * val handler = new Handler(Looper.getMainLooper)
  * UiDispatchQueue.setUi(handler.post)
  * ```
  *
  * ### JavaFX initialization:
  *
  * ```
  * import javafx.application.Platform
  * import com.wire.signals.ui.UiDispatchQueue
  *
  * UiDispatchQueue.setUi(Platform.runLater)
  *```
  *
  * ### Usage in all cases:
  *
  * ```
  * import com.wire.signals.ui.UiDispatchQueue._
  *
  * signal ! true
  * signal.onUi { value => ... }
  * Future { ... }(Ui)
  * ```
  *
  * @param runUiWith A function from the GUI platform you're working with that will execute a given task in the GUI context
  */
final class UiDispatchQueue(private val runUiWith: Runnable => Unit) extends DispatchQueue {
  override def execute(runnable: Runnable): Unit = runUiWith(runnable)
}

object UiDispatchQueue {
  private val Empty: DispatchQueue = new UiDispatchQueue(_ => ())
  private var _ui: DispatchQueue = Empty

  object Implicits {
    /** Import this into a block of code if you want to use the Ui dispatch queue as the implicit argument in subscriptions.
      * ```
      * import UiDispatchQueue.Implicit.Ui
      * ```
      *
      * @return the Ui dispatch queue
      */
    implicit def Ui: DispatchQueue = _ui
  }

  /** The current Ui dispatch queue. This is a method, not a value, because in theory it's possible to replace an UI dispatch queue
    * to another while the program is running. In practice, you will usually start the program without the UI initialized, and
    * only later call `UiDispatchQueue.setUi` to do it, but you probably won't change it again.
    *
    * @return the Ui dispatch queue
    */
  def Ui: DispatchQueue = _ui

  /** Sets the dispatch queue for the UI. In practice, you will usually start the program without the Ui initialized, and
    * only later call `UiDispatchQueue.setUi` to do it, but you probably won't change it again.
    *
    * @param ui The dispatch queue for the UI
    */
  def setUi(ui: DispatchQueue): Unit = this._ui = ui

  /** Sets the `Runnable` that will be run by an instance of `UiDispatchQueue` which will be the UI dispatch queue from now on.
    * In practice, you will usually start the program without the Ui initialized, and
    * only later call `UiDispatchQueue.setUi` to do it, but you probably won't change it again.
    * @see the examples in the overview of `UiDispatchQueue`
    *
    * @param runUiWith a `Runnable` run by the UI dispatch queue
    */
  def setUi(runUiWith: Runnable => Unit): Unit =
    this._ui = new UiDispatchQueue(runUiWith)

  /** Resets the UI dispatch queue to an empty one. An empty UI dispatch queue ignores all tasks run on it.
    * You probably shouldn't need to use this method.
    */
  def clearUi(): Unit =
    this._ui = Empty

  /** An extension method to the `EventStream` class. You can use `eventStream.onUi { event => ... }` instead of
    * `eventStream.foreach { event => ... }` to enforce the subscription to be run on the UI dispatch queue when
    * the default dispatch queue in the given code block is different.
    *
    * @param subscriber A subscriber function which consumes the event.
    * @param context The event context the subscription is assigned to.
    * @return A new `Subscription` to the signal.
    */
  extension [E](stream: EventStream[E]) {
    def onUi(subscriber: E => Unit)(implicit context: EventContext): Subscription =
      stream.on(_ui)(subscriber)(context)
  }

  /** An extension method to the `Signal` class. You can use `signal.onUi { value => ... }` instead of
    * `signal.foreach { value => ... }` to enforce the subscription to be run on the UI dispatch queue when
    * the default dispatch queue in the given code block is different.
    *
    * @param subscriber A subscriber function which consumes the value.
    * @param context    The event context the subscription is assigned to.
    * @return A new `Subscription` to the signal.
    */
  extension [V](signal: Signal[V]) {
    def onUi(subscriber: V => Unit)(implicit context: EventContext): Subscription =
      signal.on(_ui)(subscriber)(context)
  }
}
