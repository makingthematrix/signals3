# Actor unit tests: proposed improvements and fixes

Scope: all suites in this folder — `ActorSpec`, `ActorBuilderSpec`, `ActorIntegrationSpec`,
`ActorSystemSpec`, `ActorSystemRemoteSpec`, `ActorPathSpec` (120 tests after the removal of
duplicates in commits `0f259a6`, `caea39b`, `c1e3927`).

Tests are referred to by name, not by line number.

---

## 1. Cross-cutting problems (highest priority)

### 1.1 `waitFor` does not assert
`testutils.waitFor` / `waitForResult` return a `Boolean` and never fail the test. 55 calls in this
folder are not wrapped in `assert(...)` (35 in `ActorSpec`, 16 in `ActorIntegrationSpec`, 2 in
`ActorSystemSpec`, 1 in `ActorBuilderSpec`, 1 in `ActorSystemRemoteSpec`), including the shared
`close(...)` helpers. Wrapping all of them in `assert` makes **8 tests fail** (see section 3) —
they currently pass without checking their main claim.

Fix:
- Add asserting variants to `testutils` (e.g. `expect(signal, value)` that calls `fail` on timeout)
  and use them everywhere, or wrap every call in `assert(waitFor(...), "<what we waited for>")`.
- Do the same in the `close(...)` / `closeChild(...)` helpers, so a failed close is reported.

### 1.2 `awaitCF` swallows failures
`awaitCF` discards the `Try` returned by `tryResult`, so tests like `Removing non-existent behavior is
safe`, the `AddBehavior`/`RemoveBehavior` round-trips in `ActorIntegrationSpec`, and the
`RegisterSystem` calls in `crossRegistered` pass even when the future fails.

Fix: use `resultCF` (throws on failure) where success is expected, and
`assertEquals(tryResultCF(cf), Failure(...))` where a failure is expected. Keep `awaitCF` only where
the outcome genuinely does not matter, and name it accordingly (e.g. `awaitCompletion`).

### 1.3 Assertions inside callbacks never fail the test
`AskForRef with an unknown system id fails with IllegalArgumentException` and
`asking a system with an unhandled RemoteSystemMsg fails` (both in `ActorSystemRemoteSpec`) call
`fail(...)` inside `cf.onComplete { ... }`. That runs on another thread, so the exception is lost and
the tests cannot fail.

Fix: `tryResultCF(cf) match { case Failure(InvalidSystemIdException("UNKNOWN")) => (); case other => fail(...) }`.

### 1.4 Timing via `Thread.sleep`
21 `Thread.sleep` calls in this folder (7 in `ActorSpec`, 7 in `ActorSystemRemoteSpec`, 4 in
`ActorSystemSpec`, 2 in `ActorIntegrationSpec`, 1 in `ActorBuilderSpec`), plus busy-wait loops.
They slow the suites down and are a source of flakiness.

Fix:
- Where the test waits for something to *happen*, wait on the thing itself: `resultCF(actor ? SystemMsg.AddBehavior(...))`
  instead of `actor ! AddBehavior(...); Thread.sleep(200)` (`AddBehavior and RemoveBehavior via system messages`),
  or a `Signal`/`Promise` instead of polling (`cross-system AskForRefAsync delivers a usable Ref…`,
  whose `CapturingActorImpl` should complete a `Promise[ActorRef]` instead of setting a `@volatile var`).
- `ActorBuilderSpec.ActorBuilder with all options configured` sleeps 100 ms before checking that
  `onInit` ran, but `onInit` runs synchronously inside `build()` — the sleep can simply be removed.
- Keep `Thread.sleep` only for negative checks ("nothing arrived within N ms"), and put those behind a
  single named helper (e.g. `assertNothingWithin(signal, 200.millis)`).

### 1.5 Duplicated helpers
`close`, `closeChild`, `create`, `spawn`, `newSystem`, `newActor`/`newActorOn`, `awaitRef` and
`awaitInvalid` are copy-pasted across 4–5 suites, and `ActorSystemRemoteSpec` hand-rolls the same
polling loop twice more (`closing a system makes its peers unregister it`,
`UnregisterSystem removes the peer and makes it unroutable`).

Fix: move them into a shared `ActorTestUtils` trait (or into `testutils`), together with a generic
`eventually(timeout)(block)` for the polling loops.

### 1.6 Cleanup is skipped when a test fails
Most tests close their actors/systems as the last statements, so a failing assertion leaks running
heartbeats into the following tests (a plausible cause of the occasional flaky failure in
`ActorSystemSpec`). `ActorSystemRemoteSpec` uses `try/finally` in a few tests but not consistently.

Fix: a munit `FunFixture` (or a small `withActor(...)(test)` / `withSystems(...)(test)` loan helper)
that always closes what it created.

### 1.7 Non-thread-safe shared variables
Plain `var`s are written from actor threads and read from the test thread:
- `ActorSpec.Actor continues processing after behavior exception` — `callCount` is incremented inside
  a behavior, which in parallel mode runs in a `Future`. Use `AtomicInteger`.
- `ActorIntegrationSpec.Spawn with onInit runs it on the child during initialization` and
  `Spawn via ! creates a child without returning a reference` — `receivedChild` / `ref` are set on
  the parent's processing thread. Use `AtomicReference` or complete a `Promise`.
- `ActorBuilderSpec.ActorBuilder with all options configured` — `initCalled` (safe today only because
  `onInit` is synchronous; an `AtomicBoolean` removes the doubt).

---

## 2. Probable production bug: `Close` drops pending messages

`ActorImpl.shutdown()` flushes the regular message queue and fails the pending promises with
`actorIsClosed`; it does not process them. This contradicts:
- the Scaladoc of `ActorImpl.closeAndCheck()` ("it asynchronously processes any pending messages"),
- `ActorSpec.Close via ? when actor has pending messages` (fails once its `waitFor` is asserted),
- the intent of `ActorSpec.Close via ? with pending messages waits for processing` (asserts nothing).

Decide the intended semantics first, then:
- **If Close should drain the queue:** fix `shutdown()` to process queued messages before closing, and
  assert in both tests that every pending message was processed (count them).
- **If Close should drop the queue:** fix the Scaladoc, rename the two tests, and assert that the
  pending `?` futures fail with `actorIsClosed` and the `!` messages never reach the behavior.

Either way, `ActorSpec.Actor closed while messages in-flight` should assert the outcome of each future
(today its `tryResult` result is discarded, so it only proves that nothing hangs).

---

## 3. Tests that pass without checking anything (fail once `waitFor` is asserted)

| Test (`ActorSpec`) | Cause | Fix |
|---|---|---|
| `bang with behavior ID routes message to specific behavior` | Behaviors match in LIFO order and both are catch-alls, so `actor ! 1` goes to `"special"` (added last), never to `"default"`. | Add `"special"` before `"default"`, or make `"special"` non-catch-all; then assert both halves. |
| `Close via ? when actor has pending messages` | `shutdown()` drops pending messages (section 2). | Depends on the decision in section 2. |
| `onInit can send messages via out stream` | `onInit` runs inside `build()`, before the test can subscribe to `out`; streams don't replay. | Decide whether this is supported. If not, rewrite: `onInit` sends a message to `in`, the behavior forwards it to `out` after subscription — or document the limitation and drop the test. |
| `onInit handshake - actor sends reference to another actor` | The parent behavior returns `Some(...)` but never writes to `out`, so `parent.out` never emits. | In the parent behavior write `mut.out ! ...` (and return `None`), subscribe to `parent.out` before creating the child. |
| `onInit can send messages to external stream` | Subscribes to `externalStream` after the actor (and its `onInit`) was built. | Subscribe before building the actor. |
| `onInit bidirectional handshake between two actors` | Subscribes to `actor1.in` after `actor2`'s `onInit` already sent to it. Also not bidirectional. | Subscribe before creating `actor2`; either make it really bidirectional (actor1 answers back) or rename it. |
| `in stream receives messages sent to actor` | Not verified; likely lost/reordered `Signal.mutate` updates from concurrent `foreach` callbacks. | Collect into a thread-safe structure (`ConcurrentLinkedQueue`) and compare as a set / sorted; then assert. Also: this only tests that `in` delivers to its own subscribers — assert on the actor's processing instead (see the `out` tests). |
| `in and out streams work together for bidirectional communication` | Same as above (the 2-message variant `out stream receives responses…` passes; the 3-message one does not). | Same fix. After fixing, it is a duplicate of `out stream receives responses when behavior sends to it` — keep one. |

---

## 4. Weak, vacuous or misnamed tests (per suite)

### ActorSpec
- `Concurrent behavior addition and removal` — nothing concurrent happens; the adds/removes are
  sequential. Rename (e.g. `AddBehavior and RemoveBehavior via ?`); `awaitAllTasks` is unnecessary.
- `Duplicate behavior IDs are NOT replaced` — the comment says "The second behavior should have
  replaced the first", the assertion says the opposite. Fix the comment.
- `Agitated heartbeat interval grows when idle` — asserts nothing about the interval. Either measure
  latency after an idle period vs. right after activity, or expose the computed interval to the
  `actors` package (`private[actors] def interval()`) and assert it grows to `maxMs`.
- `Heartbeat strategies` — only proves each strategy eventually delivers. Add a `Reactive` test that
  checks `maxMsgs` triggers processing before `maxMs` elapses (e.g. `Reactive(maxMs = 2000, maxMsgs = 2)`,
  send 2 messages, expect a response well under 2 s).
- `Only the first close via ? completes, the next one fails` — the comment says the second close
  "should time out", but the second `ask` on a closed actor fails immediately with `actorIsClosed`.
  Assert that exact failure instead of `isFailure` with a 1 s timeout.
- `Pause system message with response via ?` / `Unpause system message with response via ?` — assert
  the response value (`Done`), not only that the future completed.
- `System messages with messages in queue` — fine, but the negative check (`Thread.sleep(100)` then
  `!received`) should use the helper from 1.4.
- `onInit exception handling with resource cleanup` — only checks that the exception propagates; the
  "resource cleanup" in the name is not verified. Either assert the heartbeat was closed (e.g. through
  a test subclass) or rename.

### ActorBuilderSpec
- `ActorBuilder with linear / agitated / reactive heartbeat` and `…with pre-defined heartbeat
  strategies` — any heartbeat passes them. Assert the configured strategy: make
  `Actor.heartbeat` `protected[actors]` (it is `protected` today) or expose the builder's configured
  value, then `assertEquals(actor.heartbeat, HeartBeatStrategy.Linear(50))` etc.
- `ActorBuilder with serial dispatch` — add `assert(actor.isSerial)` (and `!isSerial` for the default).
- `ActorBuilder with all options configured` — remove the sleep (1.4) and assert the heartbeat and the
  special behavior (it is shadowed by the catch-all added after it — assert that via `ask(behId, …)`).

### ActorIntegrationSpec
- `Concurrent behavior additions and removals through system messages are thread-safe` — it only
  removes ids that were never added, so the removal half is vacuous. Add all behaviors first, then
  remove half of them concurrently and assert exactly the other half remains.
- `Child inherits parent's heartbeat strategy functionally` — passes with any heartbeat. Use a slow
  parent beat (e.g. `Linear(2000)`) and assert the child is *also* slow, or assert the strategy
  directly (see ActorBuilderSpec).
- `Spawn with explicit heartbeat overrides inheritance` — with a 2 s parent beat and a 5 s default
  timeout the child passes even if it inherits. Use `resultCF(child ? 1)(using 500.millis)`.
- `Parent and child process messages concurrently without interference` — the comment says "states
  diverge" but both end at 1225. Send different sets of messages so independence is actually visible.
- `Spawn on a closed actor fails with ActorIsClosed` — `intercept[IllegalStateException]` accepts any
  `IllegalStateException`; assert it is `Actor.actorIsClosed`.
- `An independently closed child does not close its siblings or the parent` — the extra
  `waitFor(c1.isClosedSignal, true)` after `closeChild(c1)` is redundant.
- Replace the remaining `Await.result(..., 1.second)` calls with `resultCF` for consistency with the
  suite-wide timeout.

### ActorSystemSpec
- `Two actors communicate via AskForRef` — the actors never talk to each other, and `awaitRef` is
  called twice per actor. Make actor A forward to B through a ref/path inside its behavior, or rename.
- `Concurrent AskForRef queries during registration are safe` — all actors are created before the
  queries start, and `awaitRef` retries anyway, so registration and lookup never overlap. Start the
  lookups concurrently with the creations and use a single `AskForRef` per id without retries (the
  lookup is FIFO behind `Register`, as `ActorSystemRemoteSpec` relies on).
- `Register with a duplicate id overwrites the previous entry` — also assert what happens to the old
  actor (still running? still reachable directly?), so the overwrite policy is pinned down.

### ActorSystemRemoteSpec
- Fix the two `onComplete` tests (1.3).
- `ask to a missing actor on the own system fails without hanging` — assert the concrete exception
  (`InvalidIdException("missing")`), which also pins down ActorSystem.md "Remaining issue #2"
  (own-system misses currently report an invalid *system* id). Also fix the stray indentation of the
  second `awaitCF`.
- `bang to a missing actor or system is silently dropped` and
  `cross-system bang to a nonexistent actor on the peer is dropped, not recursed` — no assertion.
  After the bangs, assert the system(s) still answer (`AskForRef` on a real actor). The
  `catch StackOverflowError` blocks are unnecessary: an SOE fails the test anyway.
- `a ref obtained via AskForRef delivers immediately after spawn, without waiting for a heartbeat` —
  the name promises no heartbeat wait but the test allows 5 s. Either assert delivery with a timeout
  shorter than the heartbeat, or rename to what it checks (no registration race).
- `cross-system actor-id collision…` — replace `Thread.sleep(500)` with: wait for B first, then use the
  negative-check helper for A.
- `closing a system makes its peers unregister it` / `UnregisterSystem removes the peer…` — replace the
  hand-written polling loops with the shared `eventually` helper (1.5).
- Missing regression test for ActorSystem.md "Remaining issue #3": `bang(msg, Remote(ownId, childId))`
  right after `spawn` is dropped. Add a test documenting the chosen behavior.

### ActorPathSpec
- Fine as is. Optionally add: `Local` round-trip through `asString`/`parse` (currently `Local("a1")`
  formats as `"://a1"`, which `parse` rejects as an empty system id — decide if that is intended), and
  paths containing extra `://` in the actor id.

---

## 5. Missing coverage worth adding

- Behavior-ID routing to a **non-existent** behavior id (`ask("nope", 1)` / `bang("nope", 1)`).
- A behavior that exceeds `heartbeat.timeout` in parallel mode (the `Await.result` in `onMessage`).
- `ask`/`bang` on a closed actor (regular messages, not only `Spawn`).
- `in` and `out` streams are closed after `close()`.
- Pausing a parent does not pause its children (or does — pin down the behavior).
- `Reactive` `maxMsgs` threshold (see ActorSpec above) and `Agitated` interval reset after activity.

---

## 6. Suggested order

1. Asserting `waitFor` + fix `awaitCF` / callback assertions (1.1–1.3) — reveals everything else.
2. Decide the Close semantics (section 2) and fix either the code or the tests.
3. Fix the 8 tests in section 3.
4. Shared helpers + fixture-based cleanup (1.5, 1.6), then remove `Thread.sleep` (1.4).
5. Per-suite improvements (section 4), then new coverage (section 5).
