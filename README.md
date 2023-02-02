# Signals3

![Scala CI](https://github.com/makingthematrix/signals3/workflows/Scala%20CI/badge.svg)

This is a lightweight event streaming library wfor Scala. It's based on [Wire Signals](https://github.com/wireapp/wire-signals). 
Wire Signals was used extensively in the Wire Android client app - the biggest Scala project for Android, as far as I know - in everything from 
[fetching and decoding data from another device](https://github.com/wireapp/wire-android-sync-engine/blob/develop/zmessaging/src/main/scala/com/waz/service/push/PushService.scala) 
to [updating the list of messages displayed in a conversation](https://github.com/wireapp/wire-android/blob/develop/app/src/main/scala/com/waz/zclient/messages/MessagesController.scala).

This new version of Wire Signals starts as a humble copy, just rewritten in Scala 3, but I have big plans for it.  

### How to use
At the moment, `signals3` is not yet on Maven Central. If you want to try it out, you can just copy the code.

#### Syntax

In short, you can create a `SourceSignal` somewhere in the code:
```
val intSignal = Signal(1) // SourceSignal[Int] with the initial value 1
val strSignal = Signal[String]() // initially empty SourceSignal[String]
```

and subscribe it in another place:
```
intSignal.foreach { number => println("number: $number") }
strSignal.foreach { str => println("str: $str") }
```

Now every time you publish something to the signals, the functions you provided above will be executed, just as in case of a regular event stream...
```
scala> intSignal ! 2
number: 2
```
... but if you happen to subscribe to a signal after an event was published, the subscriber will still have access to that event. On the moment of subscription the provided function will be executed with the last event in the signal if there is one. So at this point in the example subscribing to `intSignal` will result in the number being displayed:
```
> intSignal.foreach { number => println("number: $number") }
number: 2
```
but subscribing to `strSignal` will not display anything, because `strSignal` is still empty. Or, if you simply don't need that functionality, you can use a standard `EventStream` instead.

You can also of course `map` and `flatMap` signals, `zip` them, `throttle`, `fold`, or make any future or an event stream into one. With a bit of Scala magic you can even do for-comprehensions:
```
val fooSignal = for {
 number <- intSignal
 str    <- if (number % 3 == 0) Signal.const("Foo") else strSignal
} yield str
```

#### If you want to know more:
* [Wire Signals - Yet Another Event Streams Library](https://youtu.be/IgKjd_fhM0M) - a video based on a talk from [Functional Scala 2020](https://www.functionalscala.com/). It discusses the whole theory behind event streams and provides examples from the Wire Signals library.
* [An article on Medium.com](https://makingthematrix.medium.com/wire-signals-81918bbcc07f?source=friends_link&sk=948c6f03e507e6f0188737711511a4b0) - basically a transcript of the video above.
* Another article on [how Scala futures and Wire Signals interact](https://github.com/wireapp/wire-signals/wiki/Futures-in-the-context-of-Wire-Signals)
* ... and a slightly outdated video about how we use it at Wire Android: [Scala on Wire](https://www.youtube.com/watch?v=dnsyd-h5piI)

### Contributors 

This library is a result of work of many programmers who developed Wire Android over the years. 
My thanks go especially to:
* Dean Cook (https://github.com/deanrobertcook)
* Zbigniew Szymański (https://github.com/zbsz)
Thanks, guys. I couldn't do it without you.
