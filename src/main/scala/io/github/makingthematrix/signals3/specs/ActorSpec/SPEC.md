---
id: signals3-actor-tests
type: submodule-design
status: active
title: Actor Test Suite
parent: signals3-actor
---

## Responsibility

The ActorSpec test suite is responsible for verifying the correct behavior of the Actor module, including:

- Actor creation and initialization
- Message handling (fire-and-forget and request-response)
- Behavior management (add, remove, execution order)
- System message processing
- Lifecycle management (pause, unpause, close)
- Heartbeat strategy behavior
- Stream integration (`in`/`out` streams)
- Concurrency and thread safety
- Error handling

## Test Framework

- **MUnit**: Primary test framework
- **Test utilities**: `io.github.makingthematrix.signals3.testutils.*`
- **Default timeout**: 1 second (configurable per test)

## Test Categories

### Basic Functionality

| Test | Description |
|------|-------------|
| Actor creation with initial state | Verifies actor initialization |
| Request-response message sending | Tests `?` operator returns correct response |
| Fire-and-forget message sending | Tests `!` operator processes messages |
| Response handling with NoResponse | Verifies `None` behavior returns failure |
| Exception handling in behaviors | Confirms exceptions propagate correctly |

### System Messages

| Test | Description |
|------|-------------|
| System messages handling | Tests Pause/Unpause/Close via `!` |
| Pause system message with response | Tests `? SystemMsg.Pause` returns Unit |
| Unpause system message with response | Tests `? SystemMsg.Unpause` returns Unit |
| Close system message with response | Tests `? SystemMsg.Close` completes after shutdown |
| AddBehavior system message | Tests dynamic behavior addition |
| RemoveBehavior system message | Tests dynamic behavior removal |

### Behavior Management

| Test | Description |
|------|-------------|
| Behavior added and used | Verifies added behavior is invoked |
| Concurrent behavior addition/removal | Tests multiple behaviors added/removed |
| AddBehavior and RemoveBehavior via system messages | End-to-end behavior lifecycle |

### Heartbeat Strategies

| Test | Description |
|------|-------------|
| Heartbeat strategies | Tests Linear, Agitated, Reactive all process messages |
| Agitated heartbeat interval grows when idle | Verifies dynamic interval growth |
| Reactive heartbeat processes messages | Verifies immediate processing on threshold |

### Stream Integration

| Test | Description |
|------|-------------|
| SourceStream integration via in stream | Tests `actor.in` receives messages |
| in stream receives messages sent to actor | Verifies `in ! msg` works |
| out stream receives responses | Tests behaviors can send to `out` |
| in and out streams work together | Bidirectional communication test |
| piping messages from external stream | Tests `externalStream.pipeTo(actor.in)` |

### Lifecycle Management

| Test | Description |
|------|-------------|
| System messages with messages in queue | Pause blocks regular messages, unpause resumes |
| Actor closed while messages in-flight | Graceful shutdown with pending messages |
| Close via ? completes only after actor is closed | Shutdown confirmation guarantee |
| Close via ? with pending messages waits for processing | Messages processed before close |
| Close via ! does not wait for response | Fire-and-forget close |
| Multiple Close via ? all complete | Idempotent close handling |

### Concurrency

| Test | Description |
|------|-------------|
| Concurrent message processing | 10 concurrent messages processed correctly |
| Actor continues processing after behavior exception | Error isolation |

### Error Handling

| Test | Description |
|------|-------------|
| Actor with no behaviors uses ignoreMsg | Default `None` behavior |
| Behavior returns None vs Some(None) | Distinguishes NoResponse from valid None |

### Special Cases

| Test | Description |
|------|-------------|
| Empty message lists | No-op processing |
| Actor with Unit state | Stateless actor support |
| Serial dispatch queue actor | Tests `Actor.serial` factory |
| Serial dispatch queue with multiple behaviors | Multi-behavior serial actor |

## Test Setup

```scala
class ActorSpec extends FunSuite {
  private val eventContext = EventContext()
  import Threading.defaultContext
  given Timeout: FiniteDuration = 1.seconds

  override def beforeEach(context: BeforeEach): Unit =
    eventContext.start()

  override def afterEach(context: AfterEach): Unit =
    eventContext.stop()

  private def close(actor: Actor[?, ?, ?]): Unit = {
    actor.close()
    waitFor(actor.isClosedSignal, true)
  }
}
```

## Test Utilities

| Utility | Description |
|---------|-------------|
| `waitFor(signal, value)` | Block until signal reaches expected value |
| `waitForResult(signal, value)` | Wait for signal to contain result |
| `resultCF(future)` | Extract result from CloseableFuture |
| `awaitCF(future)` | Await CloseableFuture completion |
| `tryResult(future)` | Attempt to get result with timeout |

## Coverage Areas

- ✅ Actor creation (all factory methods)
- ✅ Message sending (`!` and `?`)
- ✅ System messages (all variants)
- ✅ Behavior management (add/remove/execute)
- ✅ Heartbeat strategies (Linear, Agitated, Reactive)
- ✅ Stream integration (`in`/`out`)
- ✅ Lifecycle (pause/unpause/close)
- ✅ Error handling (exceptions, NoResponse)
- ✅ Concurrency (concurrent messages, atomic processing)
- ✅ Serial dispatch queue variants

## Running Tests

```bash
# Run all tests
sbt test

# Run only ActorSpec
sbt "testOnly io.github.makingthematrix.signals3.actors.ActorSpec"
```

## Notes

- Some concurrency tests may be flaky due to timing; rerun once if unrelated tests fail
- Close tests verify shutdown guarantees (pending message processing)
- System message response tests confirm `CloseableFuture[Unit]` completion
