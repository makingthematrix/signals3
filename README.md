# Signals3

![Scala CI](https://github.com/makingthematrix/signals3/workflows/Scala%20CI/badge.svg)
[![signals3 Scala version support](https://index.scala-lang.org/makingthematrix/signals3/signals3/latest-by-scala-version.svg?platform=jvm)](https://index.scala-lang.org/makingthematrix/signals3/signals3)

[Signals3 API documentation](https://makingthematrix.github.io/signals3)

This is a lightweight event streaming library for Scala. It's based on [Wire Signals](https://github.com/wireapp/wire-signals). 
Wire Signals was used extensively in the Wire Android client app - the biggest Scala project for Android, as far as I know - in everything from 
[fetching and decoding data from another device](https://github.com/wireapp/wire-android-sync-engine/blob/develop/zmessaging/src/main/scala/com/waz/service/push/PushService.scala) 
to [updating the list of messages displayed in a conversation](https://github.com/wireapp/wire-android/blob/develop/app/src/main/scala/com/waz/zclient/messages/MessagesController.scala).
In new apps, it can be of use for the same tasks, as well as - if you write an Android app in Scala by chance, or maybe a desktop app in JavaFX/ScalaFX - Signals3 may help you to communicate between the UI and the background thread. Details below. 

## Main features

* Event streams
* Signals: event streams with internal values
* Abstractions for easy data transfer between execution contexts
* An implementation of (sometimes) closeable futures
* Methods to work with event streams and signals in a way similar to standard Scala collections
* Generators: streams that can generate events and signals that can compute their new updates in regular (or variable) intervals. 

## How to use

SBT:
```sbt
  libraryDependencies += "io.github.makingthematrix" %% "signals3" % "1.1.1"
```

Maven:
```xml
<dependency>
    <groupId>io.github.makingthematrix</groupId>
    <artifactId>signals3_3</artifactId>
    <version>1.1.1</version>
</dependency>
```

Mill:
```
ivy"io.github.makingthematrix::signals3:1.1.1"
```

Gradle:
```
compile group: 'io.github.makingthematrix', name: 'signals3_3', version: '1.1.1'
```

### Syntax

In short, you can create a `SourceSignal` somewhere in the code:
```scala
val intSignal = Signal(1) // SourceSignal[Int] with the initial value 1
val strSignal = Signal[String]() // initially empty SourceSignal[String]
```

and subscribe it in another place:
```scala
intSignal.foreach { number => println("number: $number") }
strSignal.foreach { str => println("str: $str") }
```

Now every time you publish something to the signals, the functions you provided above will be executed, just as in case of a regular stream...
```scala
scala> intSignal ! 2
number: 2
```
... but if you happen to subscribe to a signal after an event was published, the subscriber will still have access to that event. On the moment of subscription the provided function will be executed with the last event in the signal if there is one. So at this point in the example subscribing to `intSignal` will result in the number being displayed:
```scala
> intSignal.foreach { number => println("number: $number") }
number: 2
```
but subscribing to `strSignal` will not display anything, because `strSignal` is still empty. Or, if you simply don't need that functionality, you can use a standard `Stream` instead.

You can also of course `map` and `flatMap` signals, `zip` them, `throttle`, `fold`, or make any future or a stream into one. With a bit of Scala magic you can even do for-comprehensions:
```scala
val fooSignal = for {
 number <- intSignal
 str    <- if (number % 3 == 0) Signal.const("Foo") else strSignal
} yield str
```

### Communication between execution contexts

Every time you define a `foreach` method on a stream or a signal, you can specify the execution context in which it will work.
Since Signals3 started as an event streaming library for Android, it implements a utility class `UiDispatchQueue` to help you set up 
communication between the default execution context (`Threading.defaultContext`) and a secondary one, usually associated with GUI. 

On platforms such as Android or JavaFX, a `Runnable` task that involves changes to the app's GUI requires to be run 
in that secondary, special execution context - otherwise it will either not work, will result in errors, or may even crash the app.
Your platform should provide you with a method which you can call with the given task to execute it properly.
If that method is of the type `Runnable => Unit` (or if you can wrap it in a function of this type) you can pass it
to `UiDispatchQueue` in the app's initialization code. Later, `UiDispatchQueue` will let you use extension methods
`.onUi` on event streams and signals. They work exactly like `.foreach` but they will run the subscriber code in
your GUI platform execution context.

#### Example Android initialization

```scala
import android.os.{Handler, Looper}
import io.github.makingthematrix.signal3.ui.UiDispatchQueue

val handler = new Handler(Looper.getMainLooper)
UiDispatchQueue.setUi(handler.post)
```

#### Example JavaFX initialization

```scala
import javafx.application.Platform
import io.github.makingthematrix.signal3.ui.UiDispatchQueue

UiDispatchQueue.setUi(Platform.runLater)
```

#### Usage in all cases:

```scala
import io.github.makingthematrix.signal3.ui.UiDispatchQueue.*

val signal = Signal(false) // create a new source signal
...
signal.onUi { value => ... } // define a block of code that will run on the UI thread
...
signal ! true // update the value of the signal from any other place in the code
...
// or this is how you can run any piece of code on the UI thread
Future { ... }(Ui)
```

## If you want to know more
* [Wire Signals - Yet Another Event Streams Library](https://youtu.be/IgKjd_fhM0M) - a video based on a talk from [Functional Scala 2020](https://www.functionalscala.com/). It discusses the whole theory behind event streams and provides examples from the Wire Signals library.
* [An article on Medium.com](https://makingthematrix.medium.com/wire-signals-81918bbcc07f?source=friends_link&sk=948c6f03e507e6f0188737711511a4b0) - basically a transcript of the video above.
* Another article on [how Scala futures and Wire Signals interact](https://github.com/wireapp/wire-signals/wiki/Futures-in-the-context-of-Wire-Signals)
* ... and a slightly outdated video about how we use it at Wire Android: [Scala on Wire](https://www.youtube.com/watch?v=dnsyd-h5piI)

## Contributors 

This library is a result of work of many programmers who developed Wire Android over the years. 
My thanks go especially to:
* Dean Cook (https://github.com/deanrobertcook)
* Zbigniew Szymański (https://github.com/zbsz)

Thanks, guys. I couldn't do it without you.
