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
- **Response Handling**: Support for both fire-and-forget and request-response patterns

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
2. **Agitated**: Adjusts processing intervals based on message load
3. **Reactive**: Processes messages immediately when they arrive or when a threshold is reached

### Behaviors

Behaviors define how an actor responds to messages. An actor can have:

- A default behavior that handles all messages
- Multiple custom behaviors (partial functions) that handle specific message types
- Behaviors can be added and removed dynamically

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

// Add a custom behavior
counterActor.addBehavior("reset", {
  case (0, actor) =>
    actor.state = 0
    Some("Counter reset")
})

// Send a system message to pause the actor
counterActor ! SystemMsg.Pause
```

## Methods

### Core Methods

- `!(msg: Msg)`: Send a message to the actor (fire-and-forget)
- `?(msg: Msg): CloseableFuture[Rsp]`: Send a message and get a future response
- `!(msg: SystemMsg)`: Send a system message (pause, unpause, close)
- `addBehavior(id: String, behavior: PF[Msg, Rsp, State])`: Add a custom behavior
- `addBehavior(behavior: Behavior[Msg, Rsp, State])`: Add a behavior with a generated ID
- `addBehavior(pf: PF[Msg, Rsp, State]): String`: Add a behavior and return its ID
- `removeBehavior(id: String)`: Remove a behavior by ID
- `removeBehavior(pf: PF[Msg, Rsp, State])`: Remove a behavior by reference
- `getBehavior(id: String): Option[PF[Msg, Rsp, State]]`: Get a behavior by ID

### Lifecycle Methods

- `closeAndCheck(): Boolean`: Close the actor and check if all messages are processed
- `pause()`: Pause message processing
- `unpause()`: Resume message processing

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

- `F[Msg, Rsp, State]`: Default behavior function type
- `PF[Msg, Rsp, State]`: Partial function type for custom behaviors
- `Behavior[Msg, Rsp, State]`: Tuple of behavior ID and partial function

## Enums

### SystemMsg

System-level messages for controlling actor lifecycle:
- `Pause`: Pause the actor
- `Unpause`: Unpause the actor
- `Close`: Close the actor

### HeartBeatStrategy

Strategies for message processing:
- `Linear(ms: Long)`: Fixed interval processing
- `Agitated(minMs: Long, coeff: Double, maxMs: Long)`: Dynamic interval based on message load
- `Reactive(maxMs: Long, maxMsgs: Int)`: Process immediately or when threshold is reached

## Error Handling

- `NoResponse`: Special failure indicating no response was provided
- Behaviors can return `Try[Option[Rsp]]` to handle failures
- Unhandled exceptions in behaviors are caught and wrapped in Failure

## Integration with Signals3

The Actor class integrates with the Signals3 library:

- Uses `SourceStream` for message input
- Uses `GeneratorStream.heartbeat` for scheduling
- Uses `CloseableFuture` for responses
- Implements `Closeable` and `Pausable` traits
- Works with `DispatchQueue` for execution context management