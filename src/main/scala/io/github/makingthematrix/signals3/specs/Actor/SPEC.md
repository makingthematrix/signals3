---
id: signals3-actor
type: module-design
status: active
title: Actor Module
parent: signals3
---

## Responsibility

The Actor module provides a lightweight implementation of the Actor model for concurrent and distributed programming. It is responsible for:

- Encapsulating state and behavior into actors that communicate via message passing
- Supporting both fire-and-forget (`!`) and request-response (`?`) message patterns
- Managing actor lifecycle (pause, unpause, close)
- Providing configurable heartbeat strategies for message processing
- Supporting dynamic behavior addition and removal
- Integrating with Signals3 streams for input/output

## Boundary

The Actor module interacts with:

- **Signals3 Core**: Uses `Stream`, `SourceStream`, `Signal`, `CloseableFuture`
- **GeneratorStream**: For heartbeat scheduling via `GeneratorStream.heartbeat`
- **Closeable/Pausable traits**: For lifecycle management
- **DispatchQueue**: For execution context management
- **ExecutionContext**: For async operations

## Key Components

### Type Hierarchy

1. **Actor[Msg, Rsp, State]**: Read-only trait exposing the actor's public API
2. **MutableActor[Msg, Rsp, State]**: Extends Actor, adds mutable state and behavior modification methods (used by behaviors)
3. **ActorImpl**: Private final class implementing MutableActor

### Type Parameters

- `Msg`: The type of incoming messages
- `Rsp`: The type of responses
- `State`: The type of internal state

### SystemMsg (Path-Dependent Enum)

Defined **inside** the `Actor` trait, making it path-dependent with access to the actor's type parameters:

- `Pause`: Suspend regular message processing (system messages still processed)
- `Unpause`: Resume regular message processing
- `Close`: Terminate the actor
- `AddBehavior(id: String, pf: PF[Msg, Rsp, State])`: Add a behavior dynamically
- `RemoveBehavior(id: String)`: Remove a behavior by ID

**Usage**: Access SystemMsg through an actor instance:
```scala
val actor = Actor[Int, String, Int](0, ...)
import actor.SystemMsg
actor ! SystemMsg.Pause
```

### HeartBeatStrategy

Strategies for controlling message processing intervals:

- `Linear(ms: Long)`: Fixed interval processing
- `Agitated(minMs: Long, coeff: Double, maxMs: Long)`: Dynamic interval - starts at minMs, grows by coeff when idle, resets when messages arrive
- `Reactive(maxMs: Long, maxMsgs: Int)`: Triggers processing when maxMs time elapses OR maxMsgs messages are queued

### Type Aliases

Defined in the companion object:

- `F[Msg, Rsp, State]`: Default behavior type - `(Msg, MutableActor[Msg, Rsp, State]) => Option[Rsp]`
- `PF[Msg, Rsp, State]`: Partial function behavior type - `PartialFunction[(Msg, MutableActor[Msg, Rsp, State]), Option[Rsp]]`

**Note**: Behaviors receive `MutableActor` to allow state mutation.

## API

### Message Sending

| Method | Description |
|--------|-------------|
| `!(msg: Msg)` | Fire-and-forget regular message |
| `?(msg: Msg): CloseableFuture[Rsp]` | Request-response regular message |
| `!(msg: SystemMsg)` | Fire-and-forget system message |
| `?(msg: SystemMsg): CloseableFuture[Unit]` | Request-response system message (completes when processed) |

### Behavior Management (MutableActor)

| Method | Description |
|--------|-------------|
| `addBehavior(id: String, pf: PF[...])` | Add behavior with explicit ID |
| `addBehavior(pf: PF[...]): String` | Add behavior with auto-generated UUID |
| `+(pf: PF[...]): String` | Operator alias for addBehavior |
| `removeBehavior(id: String)` | Remove behavior by ID |
| `removeBehavior(pf: PF[...])` | Remove behavior by reference |
| `-(id: String)` / `-(pf: PF[...])` | Operator aliases for removeBehavior |
| `getBehavior(id: String): Option[PF[...]]` | Retrieve behavior by ID |

### Stream Integration

| Property | Actor | MutableActor |
|----------|-------|--------------|
| `in` | `SourceStream[Msg]` | `SourceStream[Msg]` |
| `out` | `Stream[Rsp]` (read-only) | `SourceStream[Rsp]` (writable) |

### Lifecycle Methods

| Method | Description |
|--------|-------------|
| `close()` | Close the actor |
| `closeAndCheck(): Boolean` | Close and verify cleanup |
| `pause()` | Pause regular message processing |
| `unpause()` | Resume message processing |
| `isPausedSignal: Signal[Boolean]` | Signal indicating pause state |
| `isClosedSignal: Signal[Boolean]` | Signal indicating closed state |

### State Access

| Property | Actor | MutableActor |
|----------|-------|--------------|
| `state` | Read-only | Read/write (`state_=`) |
| `defBehavior` | Read-only | Read/write (`defBehavior_=`) |
| `heartbeat` | Read-only | Read/write (`heartbeat_=`) |

## Factory Methods

All factory methods are in the `Actor` companion object:

```scala
// With ExecutionContext (implicit)
Actor[Msg, Rsp, State](state, defBehavior, beat)(using ExecutionContext)
Actor[Msg, Rsp, State](state, defBehavior)(using ExecutionContext)  // Uses defBeat
Actor[Msg, Rsp, State](state, pfs: List[PF[...]], beat)(using ExecutionContext)
Actor[Msg, Rsp, State](state, pfs: List[PF[...]])(using ExecutionContext)  // Uses defBeat

// Serial dispatch queue variants
Actor.serial[Msg, Rsp, State](state, defBehavior, beat)
Actor.serial[Msg, Rsp, State](state, defBehavior)  // Uses defBeat
Actor.serial[Msg, Rsp, State](state, pfs: List[PF[...]], beat)
Actor.serial[Msg, Rsp, State](state, pfs: List[PF[...]])  // Uses defBeat
```

## Usage Examples

### Basic Actor

```scala
import scala.concurrent.ExecutionContext.Implicits.global

// Create an actor with initial state and default behavior
val counter = Actor[Int, String, Int](0, (msg, actor) => {
  actor.state += msg
  Some(s"Count: ${actor.state}")
})

// Fire-and-forget
counter ! 5

// Request-response
val response: CloseableFuture[String] = counter ? 3
response.foreach(println)  // "Count: 8"

// Close
counter.close()
```

### Custom Behaviors

```scala
val actor = Actor[Int, String, Int](0, (msg, _) => Some(s"Default: $msg"))

// Add behavior via method
actor.addBehavior("special", {
  case (42, _) => Some("The answer!")
})

// Add behavior via system message
import actor.SystemMsg
actor ! SystemMsg.AddBehavior("double", {
  case (n, _) if n < 10 => Some(s"Doubled: ${n * 2}")
})

// Remove behavior
actor ! SystemMsg.RemoveBehavior("special")
```

### Stream Integration

```scala
val actor = Actor[Int, String, Int](0, (msg, mut) => {
  mut.out ! s"Processed: $msg"  // Send to out stream
  None
})

// Subscribe to responses
actor.out.foreach(println)

// Pipe external events to actor
externalStream.pipeTo(actor.in)
```

### Lifecycle Management

```scala
import actor.SystemMsg

// Pause with confirmation
val pauseFuture: CloseableFuture[Unit] = actor ? SystemMsg.Pause
pauseFuture.foreach(_ => println("Paused"))

// Close with confirmation (waits for pending messages)
val closeFuture: CloseableFuture[Unit] = actor ? SystemMsg.Close
closeFuture.foreach(_ => println("Fully closed"))
```

## Internal Architecture

### Message Queues

- `msgs: mutable.Queue[(Msg, Option[Promise[Rsp]])]` - Regular messages with optional response promises
- `systemMsgs: mutable.Queue[(SystemMsg, Option[Promise[Unit]])]` - System messages with optional response promises

### Message Processing Flow

1. Messages enqueued via `msgStream` or `systemStream`
2. For Reactive strategy: immediate processing if threshold reached
3. Heartbeat triggers `processMessages()`
4. `processMessages()` calls `processSystemMessages()` then `processRegularMessages()`
5. Promises completed with results

### Behavior Evaluation Order

1. Custom behaviors evaluated in order added (first match wins)
2. Default behavior (`defBehavior`) used if no custom behavior matches
3. `Actor.ignoreMsg` returns `None` (results in `NoResponse` for `?` calls)

### Shutdown Sequence (via `? SystemMsg.Close`)

1. Close heartbeat
2. Process remaining messages
3. Wait for heartbeat closure signal
4. Complete parent close

## Error Handling

| Scenario | Result |
|----------|--------|
| Behavior returns `None` via `?` | `NoResponse` failure (`IllegalStateException`) |
| Behavior returns `Some(None)` | `Success(None)` |
| Behavior throws exception | `Failure(exception)` |

## Concurrency Model

- **Sequential Processing**: One thread processes messages at a time (`isProcessing` atomic flag)
- **Queue Access**: Serialized through `msgStream`/`systemStream`
- **State Safety**: Safe because of sequential processing
- **Behavior Modifications**: Take effect for subsequent messages only

## Performance Characteristics

- **Queue Operations**: O(1) enqueue/dequeue
- **Behavior Lookup**: O(n) linear search through behaviors list
- **Heartbeat Overhead**: Strategy-dependent
- **Message Processing**: Sequential, not parallel
