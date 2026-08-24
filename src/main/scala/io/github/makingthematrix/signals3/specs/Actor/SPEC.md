# Actor Class

## Overview

The Actor class in Signals3 provides a lightweight implementation of the Actor model for concurrent and distributed programming. It encapsulates state and behavior, communicating through message passing, making it suitable for both traditional actor use cases (as gateways to components) and modeling small independent entities like NPCs, neurons, or distributed system nodes.

## Key Features

- **Lightweight Implementation**: Designed for performance and simplicity
- **Message Passing**: Communication through asynchronous message passing
- **State Management**: Internal mutable state that can be modified in response to messages
- **Behavior Composition**: Support for multiple behaviors that can be added and removed dynamically
- **Heartbeat Strategies**: Configurable processing intervals for different use cases
- **Thread Safety**: Built-in thread safety for concurrent message processing
- **Lifecycle Management**: Support for pausing, unpausing, and closing actors
- **Response Handling**: Support for both fire-and-forget and request-response patterns for both regular messages and system messages

## Core Concepts

### Actor Model

The Actor model is a mathematical model of concurrent computation that treats "actors" as the universal primitives of concurrent computation. In response to a message, an actor can:

- Make local decisions
- Create more actors
- Send more messages
- Determine how to respond to the next message received

### Heartbeat Strategies

The Actor class supports different heartbeat strategies to control how often messages are processed:

1. **Linear**: Processes messages at fixed intervals
2. **Agitated**: Adjusts processing intervals based on message load - interval grows exponentially when idle, resets when messages arrive
3. **Reactive**: Processes messages immediately when a threshold is reached (either max messages queued or max time elapsed)

### Behaviors

Behaviors define how an actor responds to messages. An actor can have:

- A default behavior that handles all unmatched messages
- Multiple custom behaviors (partial functions) that handle specific message types
- Behaviors are evaluated in order until one matches
- Behaviors can be added and removed dynamically both through method calls and system messages

### Message Processing Flow

1. Messages are enqueued in either `msgs` (regular messages) or `systemMsgs` (system messages)
2. On heartbeat tick or Reactive threshold, `processMessages()` is called
3. `processMessages()` first processes all system messages via `processSystemMessages()`
4. Then processes all regular messages via `processRegularMessages()`
5. System messages are processed even if the actor is paused (allowing unpause/close)

### System Messages with Response

System messages can now be sent via both:
- `actor ! SystemMsg.Pause` (fire-and-forget)
- `actor ? SystemMsg.Pause` (request-response, returns `CloseableFuture[Unit]`)

For Close specifically, when sent via `?`, the future completes only after:
1. Heartbeat is closed
2. All pending messages are processed
3. The `isClosedSignal` confirms closure
4. Parent `closeAndCheck()` completes

## Usage

```scala
// Create an actor with initial state and default behavior
val counterActor = Actor[Int, String, Int](0, (msg, actor) => {
  actor.state += msg
  Some(s"Current count: ${actor.state}")
})

// Send a message (fire-and-forget)
counterActor ! 5

// Send a message and get a response
val response = counterActor ? 3
response.foreach(println) // Prints "Current count: 8"

// Access SystemMsg through an actor instance (it's now a path-dependent type)
import counterActor.SystemMsg

// Send a system message with fire-and-forget
counterActor ! SystemMsg.Pause

// Send a system message and wait for confirmation
val pauseFuture = counterActor ? SystemMsg.Pause
pauseFuture.foreach(_ => println("Actor paused"))

// Add a custom behavior
counterActor.addBehavior("reset", {
  case (0, actor) =>
    actor.state = 0
    Some("Counter reset")
})

// Add behavior via system message
counterActor ! SystemMsg.AddBehavior("special", { case (42, _) => Some("Special!") })

// Remove behavior via system message
counterActor ! SystemMsg.RemoveBehavior("special")

// Close the actor with response - future completes only when fully closed
val closeFuture = counterActor ? SystemMsg.Close
closeFuture.foreach(_ => println("Actor fully closed"))

// Pipe messages from a stream to the actor's input
sourceStream.pipeTo(counterActor.in)
```

## Methods

### Core Methods

- `!(msg: Msg)`: Send a regular message to the actor (fire-and-forget)
- `?(msg: Msg): CloseableFuture[Rsp]`: Send a regular message and get a future response
- `!(msg: SystemMsg)`: Send a system message (fire-and-forget)
- `?(msg: SystemMsg): CloseableFuture[Unit]`: Send a system message and get a future response (completes when processed)
- `addBehavior(id: String, pf: PF[Msg, Rsp, State])`: Add a custom behavior with a specific ID
- `addBehavior(behavior: (id: String, pf: PF[Msg, Rsp, State]))`: Add a behavior tuple (id, partial function)
- `addBehavior(pf: PF[Msg, Rsp, State]): String`: Add a behavior with an auto-generated ID
- `+(pf: PF[Msg, Rsp, State]): String`: Operator alias for addBehavior with auto-generated ID
- `removeBehavior(id: String)`: Remove a behavior by ID
- `removeBehavior(pf: PF[Msg, Rsp, State])`: Remove a behavior by partial function reference
- `-(id: String)`: Operator alias for removeBehavior by ID
- `-(pf: PF[Msg, Rsp, State])`: Operator alias for removeBehavior by reference
- `getBehavior(id: String): Option[PF[Msg, Rsp, State]]`: Get a behavior by ID

### Lifecycle Methods

- `closeAndCheck(): Boolean`: Close the actor and check if all messages are processed
- `pause()`: Pause message processing (from Pausable trait)
- `unpause()`: Resume message processing (from Pausable trait)
- `isPausedSignal: Signal[Boolean]`: Signal indicating if actor is paused
- `isClosedSignal: Signal[Boolean]`: Signal indicating if actor is closed

### Input Stream

- `in: SourceStream[Msg]`: Input stream for piping messages to the actor

## Companion Object

The Actor companion object provides factory methods for creating actors:

- `apply(state: State, defBehavior: F[Msg, Rsp, State], beat: HeartBeatStrategy)(using ExecutionContext)`: Create an actor with custom behavior and heartbeat
- `serial(state: State, defBehavior: F[Msg, Rsp, State], beat: HeartBeatStrategy)`: Create an actor with a serial dispatch queue
- `apply(state: State, defBehavior: F[Msg, Rsp, State])(using ExecutionContext)`: Create an actor with default heartbeat
- `serial(state: State, defBehavior: F[Msg, Rsp, State])`: Create an actor with serial dispatch queue and default heartbeat
- `apply(state: State, pfs: List[PF[Msg, Rsp, State]], beat: HeartBeatStrategy)(using ExecutionContext)`: Create an actor with multiple behaviors
- `serial(state: State, pfs: List[PF[Msg, Rsp, State]], beat: HeartBeatStrategy)`: Create an actor with serial dispatch queue and multiple behaviors
- `apply(state: State, pfs: List[PF[Msg, Rsp, State]])(using ExecutionContext)`: Create an actor with multiple behaviors and default heartbeat
- `serial(state: State, pfs: List[PF[Msg, Rsp, State]])`: Create an actor with serial dispatch queue, multiple behaviors, and default heartbeat

## Type Aliases

- `F[Msg, Rsp, State]`: Default behavior function type - `(Msg, Actor[Msg, Rsp, State]) => Option[Rsp]`
- `PF[Msg, Rsp, State]`: Partial function type for custom behaviors - `PartialFunction[(Msg, Actor[Msg, Rsp, State]), Option[Rsp]]`
- Behavior is now represented as a tuple: `(id: String, pf: PF[Msg, Rsp, State])`

## Classes and Enums

### SystemMsg (Inner Enum of Actor Class)

**IMPORTANT**: SystemMsg is now defined **inside** the Actor class (not in the companion object), making it a path-dependent type that has access to the actor's type parameters `[Msg, Rsp, State]`.

System-level messages for controlling actor lifecycle and behavior:
- `Pause`: Pause the actor (stops processing regular messages, but system messages still processed)
- `Unpause`: Unpause the actor (resumes processing of regular messages)
- `Close`: Close the actor (stops all processing, completes all pending futures)
- `AddBehavior(id: String, pf: PF[Msg, Rsp, State])`: Add a behavior via system message
- `RemoveBehavior(id: String)`: Remove a behavior by ID via system message

**Usage Note**: Because SystemMsg is now inside Actor, you must access it through an actor instance:
```scala
val actor = Actor[Int, String, Int](0, ...)
import actor.SystemMsg
actor ! SystemMsg.Pause
```

### HeartBeatStrategy

Strategies for message processing:
- `Linear(ms: Long)`: Fixed interval processing (e.g., every 100ms)
- `Agitated(minMs: Long, coeff: Double, maxMs: Long)`: Dynamic interval - starts at minMs, grows by coeff factor when idle, resets to minMs when messages arrive
- `Reactive(maxMs: Long, maxMsgs: Int)`: Triggers processing when either maxMs time elapses OR maxMsgs messages are queued

## Internal Architecture

### Message Queues

The Actor maintains two separate queues:
- `msgs: mutable.Queue[(Msg, Option[Promise[Rsp]])]` - Regular messages with optional response promises
- `systemMsgs: mutable.Queue[(SystemMsg, Option[Promise[Unit]])]` - System messages with optional response promises

Both queues use `mutable.Queue` for O(1) enqueue/dequeue operations.

### Message Processing

1. Messages are sent to `msgStream` (for regular messages) or `systemStream` (for system messages)
2. Streams enqueue messages in respective queues
3. For Reactive strategy, processing is triggered immediately if message count threshold is reached
4. Heartbeat triggers `processMessages()` at configured intervals
5. `processMessages()` calls `processSystemMessages()` then `processRegularMessages()`
6. Each message processing completes its associated promise (if present)

### Shutdown Sequence

When `SystemMsg.Close` is received with a promise (sent via `?`):
1. The promise is completed via `shutdown()` future
2. `shutdown()` performs:
   - Close the heartbeat (`beat.closeAndCheck()`)
   - Process any pending regular messages (`Future { processMessages() }`)
   - Wait for heartbeat to fully close (`beat.isClosedSignal.onTrue`)
   - Call parent close method (`super.closeAndCheck()`)
3. The promise completes only when all steps finish

This ensures users of `actor ? SystemMsg.Close` can be certain the actor is fully shut down when the future completes.

## Error Handling

- `NoResponse`: Special failure (`IllegalStateException`) indicating no response was provided for a message sent via `?`
- Behaviors that return `None` result in `NoResponse` when sent via `?`
- Behaviors that return `Some(None)` result in `Success(None)` response
- Behaviors that throw exceptions result in `Failure` response
- Unhandled exceptions in behaviors are caught and wrapped in `Failure`
- System messages that throw exceptions complete their promise with `Failure`

## Integration with Signals3

The Actor class integrates with the Signals3 library:

- Uses `SourceStream[Msg]` for the `in` input stream
- Uses `Stream[(Msg, Option[Promise[Rsp]])]` for `msgStream` (internal message stream)
- Uses `Stream[(SystemMsg, Option[Promise[Unit]])]` for `systemStream` (internal system message stream)
- Uses `GeneratorStream.heartbeat` for scheduling message processing
- Uses `CloseableFuture[Rsp]` and `CloseableFuture[Unit]` for responses
- Implements `Closeable` trait (provides `close()`, `closeAndCheck()`, `isClosedSignal`)
- Implements `Pausable` trait (provides `pause()`, `unpause()`, `isPausedSignal`)
- Works with `DispatchQueue` for execution context management
- Factory methods use `ExecutionContext` for async operations

## Concurrency Model

- **Message Processing**: Only one thread processes messages at a time (controlled by `isProcessing` atomic flag)
- **Thread Safety**: The `msgs` and `systemMsgs` queues are `mutable.Queue` which is not thread-safe by default, but access is serialized through `msgStream` and `systemStream` respectively
- **State Access**: The actor's `state` is a `var` and can be modified by behaviors. State access is thread-safe because only one thread processes messages at a time
- **Behavior Modification**: Behaviors can be added/removed during message processing, but the modifications take effect for subsequent messages only

## Performance Characteristics

- **Message Queue**: O(1) enqueue and dequeue operations (using `mutable.Queue`)
- **Behavior Lookup**: O(n) where n is number of behaviors (linear search through list)
- **Heartbeat Overhead**: Depends on strategy:
  - Linear: Constant overhead at fixed intervals
  - Agitated: Grows when idle, resets when messages arrive
  - Reactive: Triggers immediately when threshold is reached
- **Concurrency**: Messages are processed sequentially, not in parallel
