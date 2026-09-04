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
- Supporting dynamic behavior addition and removal via system messages
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
- `AddPF(pf: PF[Msg, Rsp, State])`: Add a behavior with auto-generated UUID

**Usage**: Access SystemMsg through an actor instance:
```scala
val actor = Actor[Int, String, Int](0, ...)
import actor.SystemMsg
actor ! SystemMsg.Pause
```

**Thread Safety Note**: All behavior modifications MUST go through SystemMsg messages (AddBehavior, RemoveBehavior, AddPF). This ensures thread-safety as system messages are processed sequentially by the actor's message processing loop.

### HeartBeatStrategy

Strategies for controlling message processing intervals:

- `Linear(ms: Long, timeout: FiniteDuration = 5.second)`: Fixed interval processing with configurable behavior timeout
- `Agitated(minMs: Long, coeff: Double, maxMs: Long, timeout: FiniteDuration = 5.second)`: Dynamic interval - starts at minMs, grows by coeff when idle (up to maxMs), resets when messages arrive
- `Reactive(maxMs: Long, maxMsgs: Int, timeout: FiniteDuration = 5.second)`: Triggers processing when maxMs time elapses OR maxMsgs messages are queued

**Note**: The `timeout` parameter specifies the maximum time a behavior can take to execute before timing out. In non-serial dispatch mode, behaviors that exceed this timeout will complete their promise with a TimeoutException.

### Type Aliases

Defined in the companion object:

- `PF[Msg, Rsp, State]`: Partial function behavior type - `PartialFunction[(Msg, MutableActor[Msg, Rsp, State]), Option[Rsp]]`
- `Beh[Msg, Rsp, State]`: Behavior tuple type - `(id: String, pf: PF[Msg, Rsp, State])`

**Note**: Behaviors receive `MutableActor` to allow state mutation. The `PF` type alias is the primary behavior type used throughout the Actor API.

## API

### Message Sending

| Method | Description |
|--------|-------------|
| `!(msg: Msg)` | Fire-and-forget regular message |
| `?(msg: Msg): CloseableFuture[Rsp]` | Request-response regular message |
| `!(msg: SystemMsg)` | Fire-and-forget system message |
| `?(msg: SystemMsg): CloseableFuture[Unit]` | Request-response system message (completes when processed) |

### Behavior Management

**Important**: Behavior management methods on `MutableActor` are `private[actors]` and not directly accessible to users. All behavior modifications must be done through SystemMsg messages.

| Method | Description | Access |
|--------|-------------|--------|
| `getBehavior(id: String): Option[PF[...]]` | Retrieve behavior by ID | Public |
| `addBehavior(id: String, pf: PF[...]): Boolean` | Add behavior with explicit ID (returns false if ID exists) | Private |
| `addBehavior(pf: PF[...]): String` | Add behavior with auto-generated UUID | Private |
| `removeBehavior(id: String)` | Remove behavior by ID | Private |

**Public API for Behavior Management**: Use SystemMsg:
- `actor.ask(SystemMsg.AddBehavior(id, pf))` - Add behavior with ID
- `actor.ask(SystemMsg.RemoveBehavior(id))` - Remove behavior by ID
- `actor.ask(SystemMsg.AddPF(pf))` - Add behavior with auto-generated ID
- `actor.getBehavior(id)` - Retrieve behavior by ID

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
| `heartbeat` | Read-only | Read-only |

**Note**: The `defBehavior` concept has been removed. The default behavior is now passed as a parameter to the Actor constructor and can be retrieved via `getBehavior("")` or by checking the behaviors list. State mutations should be done through behaviors that receive `MutableActor`.

## Factory Methods

All factory methods are in the `Actor` companion object. They return `Actor[Msg, Rsp, State]` which is actually an `ActorImpl` (private implementation) that extends `Closeable` and `Pausable`.

```scala
// With ExecutionContext (implicit)
Actor[Msg, Rsp, State](state, pf: PF[Msg, Rsp, State])(using ExecutionContext)
Actor[Msg, Rsp, State](state, pf: PF[Msg, Rsp, State], beat: HeartBeatStrategy)(using ExecutionContext)
Actor[Msg, Rsp, State](state, behavior: Beh[Msg, Rsp, State])(using ExecutionContext)
Actor[Msg, Rsp, State](state, behavior: Beh[Msg, Rsp, State], beat: HeartBeatStrategy)(using ExecutionContext)
Actor[Msg, Rsp, State](state, pfs: List[PF[Msg, Rsp, State]])(using ExecutionContext)
Actor[Msg, Rsp, State](state, pfs: List[PF[Msg, Rsp, State]], beat: HeartBeatStrategy)(using ExecutionContext)

// With onInit callback
Actor[Msg, Rsp, State](state, pf: PF[Msg, Rsp, State], onInit: MutableActor[Msg, Rsp, State] => Unit)(using ExecutionContext)
Actor[Msg, Rsp, State](state, behavior: Beh[Msg, Rsp, State], onInit: MutableActor[Msg, Rsp, State] => Unit)(using ExecutionContext)
Actor[Msg, Rsp, State](state, pfs: List[PF[Msg, Rsp, State]], onInit: MutableActor[Msg, Rsp, State] => Unit)(using ExecutionContext)

// Serial dispatch queue variants (use dedicated serial dispatch queue)
Actor.serial[Msg, Rsp, State](state, pf: PF[Msg, Rsp, State])
Actor.serial[Msg, Rsp, State](state, pf: PF[Msg, Rsp, State], beat: HeartBeatStrategy)
Actor.serial[Msg, Rsp, State](state, behavior: Beh[Msg, Rsp, State])
Actor.serial[Msg, Rsp, State](state, pfs: List[PF[Msg, Rsp, State]])
Actor.serial[Msg, Rsp, State](state, pfs: List[PF[Msg, Rsp, State]], beat: HeartBeatStrategy)
```

**Note**: The default heartbeat strategy is `HeartBeatStrategy.Linear(100L)` (100ms interval).

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
val actor = Actor[Int, String, Int](0, {
  case (msg, _) => Some(s"Default: $msg")
})

// Add behavior via system message (only public API)
import actor.SystemMsg
actor.ask(SystemMsg.AddBehavior("special", {
  case (42, _) => Some("The answer!")
}))

// Add behavior with auto-generated ID
actor.ask(SystemMsg.AddPF({
  case (n, _) if n < 10 => Some(s"Doubled: ${n * 2}")
}))

// Remove behavior via system message
actor.ask(SystemMsg.RemoveBehavior("special"))

// Retrieve behavior
val specialBehavior: Option[Actor.PF[Int, String, Int]] = actor.getBehavior("special")
```

**Important**: Behavior modifications must be done through SystemMsg messages to ensure thread-safety. The `addBehavior` and `removeBehavior` methods on MutableActor are private and not accessible to users.

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

// Unpause with confirmation
val unpauseFuture: CloseableFuture[Unit] = actor ? SystemMsg.Unpause
unpauseFuture.foreach(_ => println("Unpaused"))

// Close with confirmation (waits for pending messages)
val closeFuture: CloseableFuture[Unit] = actor ? SystemMsg.Close
closeFuture.foreach(_ => println("Fully closed"))

// Direct methods (also available)
actor.pause()
actor.unpause()
actor.close()
```

**Note**: When closing an actor, all pending promises for messages in the queue will be completed with `ActorIsClosed` exception. The actor will process remaining messages before fully closing.

## Internal Architecture

### Message Queues

- `msgs: AtomicReference[mutable.Queue[(Msg, Option[Promise[Rsp]], behId: String)]]` - Regular messages with optional response promises and behavior ID, stored in an AtomicReference for thread-safety
- `systemMsgs: AtomicReference[mutable.Queue[(SystemMsg, Option[Promise[Unit]])]]` - System messages with optional response promises, stored in an AtomicReference for thread-safety
- `behMap: mutable.HashMap[String, PF[Msg, Rsp, State]]` - O(1) lookup map for behaviors by ID
- `behaviors: List[Beh[Msg, Rsp, State]]` - Ordered list of behaviors for sequential evaluation

### Message Processing Flow

1. Messages enqueued via `msgStream` or `systemStream` using atomic operations
2. For Reactive strategy: immediate processing if maxMsgs threshold reached
3. Heartbeat triggers `processMessages()` at configured intervals
4. `processMessages()` acquires `isProcessing` lock, then calls `processSystemMessages()` then `processRegularMessages()`
5. System messages are processed first (including AddBehavior, RemoveBehavior, Pause, Unpause, Close)
6. Regular messages are processed in order, with behavior lookup and execution
7. Promises completed with results or failures

**Thread Safety**: The `isProcessing` AtomicBoolean ensures only one thread processes messages at a time. Queue operations use AtomicReference for thread-safe access.

### Behavior Evaluation Order

1. Behaviors are evaluated in LIFO order (most recently added first) - the behaviors list is prepended to
2. First behavior that matches the message (via `isDefinedAt`) wins
3. If no behavior matches, returns `Ignored[Rsp]` which results in `NoResponse` for `?` calls
4. Behaviors can return `None` to indicate no response, which results in `NoResponse` for `?` calls
5. Behaviors can return `Some(response)` to provide a response

**Note**: Behavior modifications (add/remove) take effect immediately for the next message processed, but do not affect messages currently being processed.

### Shutdown Sequence (via `? SystemMsg.Close`)

1. All pending message promises are failed with `ActorIsClosed` exception
2. All pending system message promises are failed with `ActorIsClosed` exception
3. Heartbeat stream is closed
4. Input and output streams are closed
5. Wait for heartbeat closure signal
6. Complete parent close

**Note**: The shutdown process ensures no messages are lost and all pending futures are properly completed with failures.

## Error Handling

| Scenario | Result |
|----------|--------|
| Behavior returns `None` via `?` | `NoResponse` failure (`IllegalStateException("No response")`) |
| Behavior returns `Some(None)` | `Success(None)` |
| Behavior throws exception | `Failure(exception)` |
| Actor is closed when sending message | `ActorIsClosed` failure (`IllegalStateException("Actor is closed")`) |
| Behavior times out (non-serial mode) | `Failure(TimeoutException)` |

**Note**: In serial dispatch mode, behaviors execute synchronously without timeout. In non-serial mode, behaviors have a configurable timeout (default 5 seconds) from the HeartBeatStrategy.

## Concurrency Model

- **Sequential Processing**: One thread processes messages at a time, enforced by `isProcessing` AtomicBoolean guard
- **Queue Access**: Thread-safe using AtomicReference for both message queues
- **State Safety**: Safe because behaviors execute sequentially and receive MutableActor for state mutations
- **Behavior Modifications**: Thread-safe because all modifications go through SystemMsg which are processed sequentially
- **Serial vs Non-Serial**: In serial dispatch mode (using SerialDispatchQueue), behaviors execute synchronously. In non-serial mode, behaviors may execute asynchronously with timeout protection.

**Thread Safety Guarantees**:
- Only one thread can execute `processMessages()` at a time
- System messages (including behavior modifications) are processed before regular messages
- All state mutations happen within the serialized message processing loop
- Behavior modifications through SystemMsg are guaranteed to be thread-safe

**Important**: While the Actor ensures thread-safe message processing, behaviors themselves must be thread-safe if they access external shared state. The Actor only guarantees that behaviors won't be executed concurrently for the same actor instance.

## Performance Characteristics

- **Queue Operations**: O(1) enqueue/dequeue using mutable.Queue with AtomicReference
- **Behavior Lookup**: O(n) linear search through behaviors list for pattern matching, O(1) for ID-based lookup via behMap
- **Behavior Addition**: O(1) prepend to list and insert into HashMap
- **Behavior Removal**: O(n) filter for list, O(1) for HashMap removal
- **Heartbeat Overhead**: Strategy-dependent (Linear: constant, Agitated: dynamic calculation, Reactive: threshold-based)
- **Message Processing**: Sequential, not parallel (one message at a time per actor)
- **Memory**: Each actor maintains its own queues, behaviors, and heartbeat stream

**Optimizations**:
- Serial dispatch mode reduces overhead by avoiding Future wrapping
- Behavior lookup uses both list (for ordered evaluation) and HashMap (for O(1) ID-based retrieval)
- AtomicReference for queues provides lock-free thread-safe access
- Reactive heartbeat strategy minimizes latency for bursty workloads
