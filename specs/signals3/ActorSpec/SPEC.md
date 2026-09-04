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
- Integration testing with concurrent behavior modifications

## Test Framework

- **MUnit**: Primary test framework
- **Test utilities**: `io.github.makingthematrix.signals3.testutils.*`
- **Default timeout**: 1 second (configurable per test)

## Test Categories

### Basic Functionality

| Test | Description |
|------|-------------|
| Actor creation with initial state | Verifies actor initialization with state |
| Request-response message sending | Tests `?` operator returns correct response via CloseableFuture |
| Fire-and-forget message sending | Tests `!` operator processes messages without response |
| Response handling with NoResponse | Verifies behavior returning `None` results in NoResponse failure |
| Exception handling in behaviors | Confirms exceptions in behaviors propagate to caller via Failure |
| Message sending without response | Tests fire-and-forget with behaviors that return None |
| Message sending with response | Tests request-response with behaviors that return Some |
| Future completion | Verifies CloseableFuture completes when message is processed |

### System Messages

| Test | Description |
|------|-------------|
| System messages handling | Tests Pause/Unpause/Close via `!` operator |
| Pause system message with response | Tests `? SystemMsg.Pause` returns CloseableFuture[Unit] |
| Unpause system message with response | Tests `? SystemMsg.Unpause` returns CloseableFuture[Unit] |
| Close system message with response | Tests `? SystemMsg.Close` completes after shutdown and pending messages |
| Close system message without response | Tests `! SystemMsg.Close` fire-and-forget |
| AddBehavior system message | Tests dynamic behavior addition via SystemMsg.AddBehavior |
| RemoveBehavior system message | Tests dynamic behavior removal via SystemMsg.RemoveBehavior |
| AddPF system message | Tests adding behavior with auto-generated ID via SystemMsg.AddPF |
| Message processing during pause | Tests that messages queue during pause and process after unpause |

### Behavior Management

| Test | Description |
|------|-------------|
| Adding behaviors with IDs | Tests addBehavior with explicit ID |
| Adding behaviors without IDs | Tests addBehavior with auto-generated UUID |
| Removing behaviors by ID | Tests removeBehavior by string ID |
| Removing behaviors by reference | Tests removeBehavior by PF reference |
| Behavior execution order | Tests LIFO ordering of behavior evaluation |
| Behavior matching | Tests that first matching behavior wins |
| Default behavior execution | Tests fallback to default behavior when no match |
| Getting behaviors by ID | Tests getBehavior retrieves correct behavior |
| Behavior added and used | Verifies added behavior is invoked for matching messages |
| Duplicate behavior ID handling | Tests that duplicate IDs are not replaced (add returns false) |
| AddBehavior and RemoveBehavior via system messages | End-to-end behavior lifecycle via SystemMsg |
| Concurrent behavior addition/removal | Tests thread-safe behavior modifications from multiple threads |

### Heartbeat Strategies

| Test | Description |
|------|-------------|
| Heartbeat strategies | Tests Linear, Agitated, Reactive all process messages correctly |
| Linear heartbeat strategy | Tests fixed interval processing |
| Agitated heartbeat strategy | Tests dynamic interval processing |
| Reactive heartbeat strategy | Tests threshold-based processing |
| Message processing intervals with linear heartbeat | Tests timing of Linear strategy |
| Dynamic interval adjustment with agitated heartbeat | Tests interval growth in Agitated strategy |
| Immediate message processing with reactive heartbeat | Tests Reactive strategy triggers on message count |
| Agitated heartbeat interval grows when idle | Verifies dynamic interval growth |
| Reactive heartbeat processes messages | Verifies immediate processing on threshold |
| Behavior timeout in non-serial mode | Tests that behaviors exceeding timeout complete with failure |

### Stream Integration

| Test | Description |
|------|-------------|
| SourceStream integration via in stream | Tests `actor.in` is a SourceStream that receives messages |
| in stream receives messages sent to actor | Verifies messages sent via `!` appear in `in` stream |
| out stream receives responses | Tests behaviors can send to `out` SourceStream |
| in and out streams work together | Bidirectional communication test |
| piping messages from external stream | Tests `externalStream.pipeTo(actor.in)` |
| Message sending without response | Tests fire-and-forget via `in` stream |

### Lifecycle Management

| Test | Description |
|------|-------------|
| Pausing and unpausing actors | Tests pause() and unpause() methods |
| Closing actors | Tests close() method |
| Actor cleanup after closing | Tests resource cleanup on close |
| System messages with messages in queue | Pause blocks regular messages, unpause resumes |
| Actor closed while messages in-flight | Graceful shutdown with pending messages |
| Close via ? completes only after actor is closed | Shutdown confirmation guarantee |
| Close via ? with pending messages waits for processing | Messages processed before close |
| Close via ! does not wait for response | Fire-and-forget close |
| Multiple Close via ? all complete | Idempotent close handling |
| Pausing and unpausing via system messages | Tests SystemMsg.Pause and SystemMsg.Unpause |
| Closing via system message | Tests SystemMsg.Close |

### Concurrency

| Test | Description |
|------|-------------|
| Concurrent message processing | Multiple concurrent messages processed correctly |
| Actor continues processing after behavior exception | Error isolation - one behavior exception doesn't stop processing |
| Behavior modifications during message processing | Tests adding/removing behaviors while messages are processed |
| Concurrent behavior additions | Tests thread-safe behavior addition from multiple threads |
| Concurrent behavior additions and removals | Tests mixed add/remove operations from multiple threads |
| Stress test: high concurrency behavior modifications | Tests 10,000+ concurrent behavior modifications |

### Error Handling

| Test | Description |
|------|-------------|
| Actor with no behaviors uses ignoreMsg | Default `None` behavior results in Ignored |
| Behavior returns None vs Some(None) | Distinguishes NoResponse from valid Some(None) |
| NoResponse for unmatched messages | Tests that unmatched messages via `?` return NoResponse |
| Exception handling in behaviors | Tests that exceptions in behaviors complete promise with Failure |
| Actor closed exception | Tests that sending to closed actor returns ActorIsClosed |
| Behavior timeout | Tests timeout handling in non-serial mode |
| Removing non-existent behavior | Tests that removing non-existent behavior doesn't cause errors |

### Special Cases

| Test | Description |
|------|-------------|
| Empty message lists | No-op processing when queues are empty |
| Actor with Unit state | Stateless actor support |
| Serial dispatch queue actor | Tests `Actor.serial` factory uses SerialDispatchQueue |
| Serial dispatch queue with multiple behaviors | Multi-behavior serial actor |
| Behavior execution order consistency | Tests LIFO ordering is maintained |
| Adding behavior with duplicate ID | Tests that duplicate IDs are not replaced |
| Messages sent during behavior modification | Tests messages processed correctly during concurrent modifications |
| Behavior modifications do not cause message loss | Tests all messages processed even with concurrent behavior changes |

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

  // Helper to close actor and wait for completion
  private def close(actor: Actor[?, ?, ?] & Closeable): Unit = {
    actor.close()
    waitFor(actor.isClosedSignal, true)
  }
  
  // Helper to create actor with Closeable type for closing
  private def create[Msg, Rsp, State](state: State, pf: Actor.PF[Msg, Rsp, State]): Actor[Msg, Rsp, State] & Closeable & Pausable =
    Actor(state, pf).asInstanceOf[Actor[Msg, Rsp, State] & Closeable & Pausable]
}
```

**Note**: The `create` helper and typed `close` method are used because `Actor` trait doesn't extend `Closeable` directly - only the `ActorImpl` implementation does. The cast is safe because all factory methods return `ActorImpl` instances.

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
- ✅ Message sending (`!` and `?` operators)
- ✅ System messages (all variants: Pause, Unpause, Close, AddBehavior, RemoveBehavior, AddPF)
- ✅ Behavior management (add/remove/execute/get)
- ✅ Heartbeat strategies (Linear, Agitated, Reactive with timeout)
- ✅ Stream integration (`in`/`out` streams)
- ✅ Lifecycle (pause/unpause/close with confirmation)
- ✅ Error handling (exceptions, NoResponse, ActorIsClosed, timeout)
- ✅ Concurrency (concurrent messages, behavior modifications, atomic processing)
- ✅ Serial dispatch queue variants
- ✅ Integration tests (behavior modifications during message processing)
- ✅ Thread safety (concurrent behavior modifications via SystemMsg)

**Note**: Integration tests in `ActorIntegrationSpec` specifically verify thread-safety of behavior modifications through system messages.

## Running Tests

```bash
# Run all tests
sbt test

# Run only ActorSpec
sbt "testOnly io.github.makingthematrix.signals3.actors.ActorSpec"
```

## Notes

- Some concurrency tests may be flaky due to timing; rerun once if unrelated tests fail
- Close tests verify shutdown guarantees (pending message processing, promise completion)
- System message response tests confirm `CloseableFuture[Unit]` completion
- Thread safety tests in `ActorIntegrationSpec` verify that behavior modifications through SystemMsg are thread-safe
- Behavior modifications can ONLY be done through SystemMsg (AddBehavior, RemoveBehavior, AddPF) which ensures thread-safety
- The `isProcessing` AtomicBoolean guard ensures only one thread processes messages at a time
- All state mutations happen within the serialized message processing loop

**Important**: The Actor implementation guarantees thread-safety for behavior modifications because:
1. All behavior modifications must go through SystemMsg
2. SystemMsg are processed in `processSystemMessages()` which runs within `processMessages()`
3. `processMessages()` is guarded by `isProcessing` AtomicBoolean
4. Therefore, only one thread can modify behaviors at a time
