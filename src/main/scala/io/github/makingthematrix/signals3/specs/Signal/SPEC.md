# Signal Class

## Overview

A signal is a stream with a cache. It keeps the last value it received and notifies subscribers of changes.

## Key Features

- Event streaming with internal state
- Signals maintain their last value and provide it to new subscribers
- Functional programming patterns for transformations (map, flatMap, filter, etc.)
- Support for combining multiple signals
- Error handling and recovery mechanisms
- Throttling and grouping capabilities
- Integration with Scala futures
- Thread-safe operations and execution context management

## Key Features

- Event streaming
- Signals: event streams with internal values
- Abstractions for easy data transfer between execution contexts
- An implementation of (sometimes) closeable futures
- Methods to work with event streams and signals in a way similar to standard Scala collections
- Generators: streams that can generate events and signals that can compute their new updates in regular (or variable) intervals

## Usage

```scala
// Create a signal with an initial value
val intSignal = Signal(1) // SourceSignal[Int] with the initial value 1

// Create an empty signal
val strSignal = Signal[String]() // initially empty SourceSignal[String]

// Subscribe to value changes
intSignal.foreach { value => println(s"Value changed to: $value") }

// Update signal value
intSignal ! 2 // Updates the value to 2 and notifies subscribers
```

## Core Methods

- `update(f: Option[V] => Option[V], currentContext: Option[ExecutionContext] = None)`: Updates the current value of the signal by applying a function to it.
- `setValue(v: Option[V], currentContext: Option[ExecutionContext] = None)`: Sets the value of the signal to the new one.
- `notifySubscribers(currentContext: Option[ExecutionContext] = None)`: Notifies all subscribers that the value has changed.
- `currentValue: Option[V]`: Returns the current value of the signal as an Option.
- `empty: Boolean`: Checks if the signal is currently empty (has no value).
- `future: Future[V]`: Returns a future that completes with the current value or the next value if empty.
- `future`: Returns a future with the current value of the signal.
- `head: Future[V]`: An alias to the `future` method.
- `tail: Signal[V]`: Returns a new signal that drops the first value (equivalent to `drop(1)`).
- `contains(value: V)(using ExecutionContext): Future[Boolean]`: Checks if the signal contains the given value.
- `exists(f: V => Boolean)(using ExecutionContext): Future[Boolean]`: Checks if the current value fulfills the given predicate.
- `onUpdated: Stream[(Option[V], V)]`: Returns a stream of tuples containing the old value (as Option) and the new value whenever the signal changes.
- `onChanged: Stream[V]`: Returns a stream that emits only the new value whenever the signal changes (old value is discarded).
- `onTrue(using V <:< Boolean): Future[Unit]`: Returns a future that completes when the signal's value becomes true.
- `onFalse(using V <:< Boolean): Future[Unit]`: Returns a future that completes when the signal's value becomes false.
- `recover(f: Throwable => V): Signal[V]`: Creates a new signal that recovers from exceptions by applying the recovery function.
- `ignoreExceptions: Signal[V]`: Creates a new signal that silently ignores any exceptions in further transformations.
- `ignoreExceptions(f: Throwable => Unit): Signal[V]`: Creates a new signal that ignores exceptions but allows for side effects.
- `withDefault(value: V): Signal[V]`: Creates a new signal that uses a default value when exceptions occur.
- `recoverWith(pf: PartialFunction[Throwable, V]): Signal[V]`: Creates a new signal that recovers using a partial function.
- `ignoreExceptionsWith(pf: PartialFunction[Throwable, Unit]): Signal[V]`: Creates a new signal that ignores specific exceptions with side effects.
- `recoverWith`: Creates a new signal which, if a further transformation fails with an exception that is handled by a provided partial function, will use a recovery value instead.
- `ignoreExceptionsWith`: Creates a new signal which, if a further transformation fails with an exception that is handled by a provided partial function, will ignore the exception and allow for a side-effect to take place.
- `zip[Z](other: Signal[Z]): Signal[(V, Z)]`: Combines this signal with another signal, emitting tuples when either signal changes.
- `map[Z](f: V => Z): Signal[Z]`: Creates a new signal by applying a function to each value of this signal.
- `filter(predicate: V => Boolean): Signal[V]`: Creates a new signal that only emits values satisfying the predicate.
- `withFilter(predicate: V => Boolean): Signal[V]`: Alias for `filter` used in for-comprehensions.
- `collect[Z](pf: PartialFunction[V, Z]): Signal[Z]`: Creates a new signal by applying a partial function to values.
- `onTrue`: Assuming that the value of the signal can be interpreted as a boolean, this method returns a future of type `Unit` which will finish with success when the value of the original signal is true.
- `onFalse`: Assuming that the value of the signal can be interpreted as a boolean, this method returns a future of type `Unit` which will finish with success when the value of the original signal is false.
- `collect`: Creates a new signal of values of the type `Z` by applying a partial function which maps the original value of the type `V` to a value of the type `Z`.
- `flatMap[Z](f: V => Signal[Z]): Signal[Z]`: Creates a new signal by mapping each value to a new signal and flattening the result.
- `flatten[Z](using V <:< Signal[Z]): Signal[Z]`: Flattens a signal of signals into a single signal.
- `scan[Z](zero: Z)(f: (Z, V) => Z): Signal[Z]`: Creates a new signal that maintains state by applying a function to the current state and new value.
- `combine[Z, Y](other: Signal[Z])(f: (V, Z) => Y): Signal[Y]`: Combines values from this signal and another signal using a function.
- `combine`: Combines the current values of this and another signal of the same or different types `V` and `Z` to produce a signal with the value of yet another type `Y`.
- `throttle(delay: FiniteDuration): Signal[V]`: Creates a new signal that limits updates to once per specified time interval.
- `orElse(fallback: Signal[V]): Signal[V]`: Creates a new signal that falls back to another signal's value when this signal is empty.
- `either[Z](fallback: Signal[Z]): Signal[Either[Z, V]]`: Creates a new signal that provides either this signal's value or a fallback signal's value.
- `pipeTo(sourceSignal: SourceSignal[V])(using ec: EventContext = EventContext.Global)`: Pipes this signal's values to another source signal.
- `|(sourceSignal: SourceSignal[V])(using ec: EventContext = EventContext.Global)`: Alias for `pipeTo`.
- `pipeTo`: A shorthand for registering a subscriber function in this signal whose only purpose is to publish changes to the value of this signal in another `SourceSignal`.
- `|`: An alias for `pipeTo`.
- `grouped(n: Int): Signal[Seq[V]]`: Groups values into sequences of the specified size.
- `groupBy(p: V => Boolean): Signal[Seq[V]]`: Groups values based on a predicate function.
- `onPartialUpdate[Z](select: V => Z): Signal[V]`: Creates a new signal that only updates when the result of the select function changes.
- `onWire(): Unit`: Lifecycle method called when the signal is wired to its source.
- `onUnwire(): Unit`: Lifecycle method called when the signal is unwired from its source.
- `onWire`: Called when the signal is wired.
- `onUnwire`: Called when the signal is unwired.
- `onPriv(ec: ExecutionContext)(body: V => Unit)(using eventContext: EventContext = EventContext.Global): Subscription`: Registers a subscriber in a specified execution context.
- `onCurrentPriv(body: V => Unit)(using eventContext: EventContext = EventContext.Global): Subscription`: Registers a subscriber that runs in the same context as the publisher.
- `publish(value: V): Unit`: Sets the value of the signal and notifies subscribers if the value changed.
- `publish(value: V, currentContext: ExecutionContext): Unit`: Sets the value with a specific execution context for notifications.
- `sameAs[Z](other: Signal[Z]): Signal[Boolean]`: Creates a boolean signal that compares values from this signal and another signal.
- `===[Z](other: Signal[Z]): Signal[Boolean]`: Alias for `sameAs`.
- `not(using V <:< Boolean): Signal[Boolean]`: Creates a boolean signal with the negated value of this signal.
- `and[Z](other: Signal[Z])(using V <:< Boolean, Z <:< Boolean): Signal[Boolean]`: Logical AND between this signal and another boolean signal.
- `&&[Z](other: Signal[Z])(using V <:< Boolean, Z <:< Boolean): Signal[Boolean]`: Alias for `and`.
- `and`: Assuming that both the value of `this` signal and the value of the `other` signal can be interpreted as a boolean, this method creates a new signal of type `Boolean` by applying logical AND.
- `&&`: An alias to `and`.
- `or[Z](other: Signal[Z])(using V <:< Boolean, Z <:< Boolean): Signal[Boolean]`: Logical OR between this signal and another boolean signal.
- `||[Z](other: Signal[Z])(using V <:< Boolean, Z <:< Boolean): Signal[Boolean]`: Alias for `or`.
- `xor[Z](other: Signal[Z])(using V <:< Boolean, Z <:< Boolean): Signal[Boolean]`: Logical XOR between this signal and another boolean signal.
- `^^[Z](other: Signal[Z])(using V <:< Boolean, Z <:< Boolean): Signal[Boolean]`: Alias for `xor`.
- `nor[Z](other: Signal[Z])(using V <:< Boolean, Z <:< Boolean): Signal[Boolean]`: Logical NOR between this signal and another boolean signal.
- `nand[Z](other: Signal[Z])(using V <:< Boolean, Z <:< Boolean): Signal[Boolean]`: Logical NAND between this signal and another boolean signal.
- `nor`: Assuming that both the value of `this` signal and the value of the `other` signal can be interpreted as a boolean, this method creates a new signal of type `Boolean` by applying logical NOR.
- `nand`: Assuming that both the value of `this` signal and the value of the `other` signal can be interpreted as a boolean, this method creates a new signal of type `Boolean` by applying logical NAND.
- `indexed: IndexedSignal[V]`: Adds indexing functionality to track how many times the signal has changed.
- `closeable: CloseableSignal[V]`: Creates a closeable wrapper around this signal.
- `drop(n: Int): Signal[V]`: Ignores the first n values from the signal.
- `dropWhile(p: V => Boolean): Signal[V]`: Ignores values while the predicate is true, then emits all subsequent values.
- `take(n: Int): TakeSignal[V]`: Takes the first n values from the signal, then closes.
- `takeWhile(p: V => Boolean): FiniteSignal[V]`: Takes values while the predicate is true, then closes.
- `take`: Updates the value a given number of times and then closes.
- `takeWhile`: Updates the value while it fulfills the condition `p`. The first update that fails closes the signal.
- `splitAt`: Splits the signal into a finite signal that updates the given number of values and closes, and another signal that picks up updating its value after the first stops.

## Subclasses

The Signal class has several specialized subclasses that provide additional functionality:

- `SourceSignal[V]`: A signal that can receive values from external sources (the main entry point for signal networks).
- `ConstSignal[V]`: A signal with an immutable value that never changes.
- `ThrottledSignal[V]`: A signal that limits how often it updates.
- `FoldLeftSignal[V, Z]`: A signal that folds multiple source signals into one using a function.
- `CombineSignal[V, Z, Y]`: A signal that combines values from two signals using a function.
- `ZipSignal` variants (`Zip2Signal` to `Zip6Signal`): Signals that combine multiple signals into tuples.
- `RecoverSignal[V]` and `RecoverWithSignal[V]`: Signals that handle exceptions in transformations.
- `MapSignal[V, Z]`: A signal that applies a function to transform values.
- `FilterSignal[V]`: A signal that filters values based on a predicate.
- `CollectSignal[V, Z]`: A signal that applies a partial function to transform values.
- `FlatMapSignal[V, Z]`: A signal that maps each value to a new signal and flattens the result.
- `ScanSignal[V, Z]`: A signal that maintains state by applying a function to current state and new values.
- `ProxySignal[V]`: A signal that delegates to other signals.
- `PartialUpdateSignal[V, Z]`: A signal that only updates when a specific aspect of the value changes.
- `GroupedSignal[V]` and `GroupBySignal[V]`: Signals that group values into sequences.
- `TakeSignal[V]` and `TakeWhileSignal[V]`: Signals that limit the number of values emitted.
- `DropSignal[V]` and `DropWhileSignal[V]`: Signals that skip initial values.
- `DoneSignal[V]`: A signal that completes after emitting a value.
- `SequenceSignal[V]`: A signal that emits values from a sequence.
- `StreamSignal[V]`: A signal that wraps a stream.
- `CloseableSignal[V]`: A signal that can be closed to stop emitting values.
- `IndexedSignal[V]`: A signal that tracks how many times it has changed.

- `SourceSignal`
- `ConstSignal`
- `ThrottledSignal`
- `FoldLeftSignal`
- `CombineSignal`
- `Zip2Signal`
- `Zip3Signal`
- `Zip4Signal`
- `Zip5Signal`
- `Zip6Signal`
- `RecoverSignal`
- `RecoverWithSignal`
- `MapSignal`
- `FilterSignal`
- `CollectSignal`
- `FlatMapSignal`
- `ScanSignal`
- `ProxySignal`
- `PartialUpdateSignal`
- `GroupedSignal`
- `GroupBySignal`
- `TakeSignal`
- `TakeWhileSignal`
- `DropSignal`
- `DropWhileSignal`
- `DoneSignal`
- `SequenceSignal`
- `StreamSignal`
- `CloseableSignal`
- `IndexedSignal`

## Dependencies

- Scala 3 (with braces syntax)
- Scala concurrent library for Future support
- ExecutionContext for thread management

## Example Usage

```scala
// Create signals
val counter = Signal(0)  // Signal with initial value
val name = Signal[String]()  // Empty signal

// Subscribe to changes
counter.foreach { value => println(s"Counter: $value") }
name.foreach { n => println(s"Name: $n") }

// Update values
counter ! 1  // Update using ! operator
counter.mutate(_ + 1)  // Update using mutation
name ! "Alice"  // Set name value

// Transform signals
val doubled = counter.map(_ * 2)
val greeting = name.map(n => s"Hello, $n!")

// Combine signals
val status = counter.combine(name) { (count, n) =>
  s"$n has clicked $count times"
}

// For-comprehension
val complexSignal = for {
  count <- counter
  n <- name
  if count > 0
} yield s"$n clicked $count times"
```

## Syntax

In short, you can create a `SourceSignal` somewhere in the code:
```scala
val intSignal = Signal(1) // SourceSignal[Int] with the initial value 1
val strSignal = Signal[String]() // initially empty SourceSignal[String]
```

and subscribe it in another place:
```scala
import io.github.makingthematrix.signals3.Threading.defaultContext

intSignal.foreach { number => println(s"number: $number") }
strSignal.foreach { str => println(s"str: $str") }
```

Now every time you publish something to the signals, the functions you provided above will be executed, just as in case of a regular stream...
```scala
scala> intSignal ! 2
number: 2
```

... but if you happen to subscribe to a signal after an event was published, the subscriber will still have access to that event. On the moment of subscription the provided function will be executed with the last event in the signal if there is one. So at this point in the example subscribing to `intSignal` will result in the number being displayed:
```scala
> intSignal.foreach { number => println(s"number: $number") }
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