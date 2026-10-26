# Issues

## 1. Message reordering: two messages sent one after another by the same sender can be processed out of order

Found while working on the new unit tests in `ActorSystemSpec` ("concurrent cross-system system messages are safe" — the concurrency stress test added to cover the recently introduced cross-system delivery of system messages, i.e. `ActorSystem.tell/ask(msg: SystemMsg, path)` routing through the `systems` map and `RemoteActorRef` forwarding of system messages).

### Symptom

The stress test (8 threads, 10 actors on a peer system, each op sending `Pause`/`Unpause` system messages through a `RemoteActorRef` and through `ActorPath.Remote`, followed by a regular `ping` message) hung deterministically: the asked `ping` never got a response within its 5-second timeout. Instrumented runs showed the target actor left **permanently paused** (`isPaused == true`, both actor systems healthy), so the pending regular message could never be processed.

### Root cause

`SourceStream.!` publishes an event via `Stream.publish` → `dispatch(event, None)`. Each subscriber is then notified in `StreamSubscription.onEvent` (`src/main/scala/io/github/makingthematrix/signals3/priv/StreamSubscription.scala`):

```scala
executionContext match {
  case Some(ec) if !currentContext.contains(ec) => Future(if (subscribed) Try(f(event)))(using ec)
  case _ => f(event)
}
```

Because `!` dispatches with `currentContext = None`, every event is executed in a **separate `Future`** on the subscriber's execution context. `ActorImpl`/`ActorSystem` use `systemStream ! (msg, rsp)` and `msgStream ! (msg, rsp, behId)` to enqueue incoming messages, so each `tell`/`ask` becomes an independent task on the dispatch queue. Nothing guarantees that these tasks run in publication order — the two `Future`s can be scheduled in either order, especially under load.

In the stress test this means a `Pause` published *before* an `Unpause` can be *enqueued after it*. If the `Pause` ends up as the last system message in the actor's queue, the actor stays paused forever and every later regular message hangs.

### Impact

- This is a pre-existing issue in the message layer, not something introduced by the cross-system system messages. It affects **all** messages, user and system, local and cross-system.
- It is a low-probability race per message pair: single-threaded sequences with synchronization between sends (a `waitFor`, or waiting for an `ask` response) effectively never hit it, which is why the rest of the suite is stable.
- But it breaks the basic actor-model expectation that `tell(a); tell(b)` from one sender delivers `a` before `b`, and it makes any concurrent overlap of non-idempotent control messages (`Pause`/`Unpause`, `AddBehavior`/`RemoveBehavior`) unsafe — the failure mode is a permanently stuck actor.

### Possible directions

- Preserve publication order per stream, e.g. dispatch `ActorImpl`/`ActorSystem` queue entries with the synchronous variant (`!!` / `publish(event, ec)`, which calls the subscriber inline when the contexts match). This preserves order because the enqueue happens on the caller's thread, at the cost of changing concurrency semantics — note the `SourceStream` Scaladoc warns to use `!!` "with caution", and for serial-dispatch actors with a `Reactive` heartbeat the synchronous path would run `processMessages()` on the publisher's thread.
- Or document that `!` ordering is not guaranteed and that senders must use `ask` (or otherwise synchronize) when message order matters.

### Current state

No production code was changed. The stress test was made deterministic instead: `Pause` coverage uses `ask`s (whose responses order the operations), and the concurrent bang traffic is limited to idempotent `Unpause`s, which cannot strand an actor regardless of reordering. If ordering is ever guaranteed per sender, the test should be strengthened back to overlapping `Pause` bangs.

Reproduced originally with: `sbt 'testOnly io.github.makingthematrix.signals3.actors.ActorSystemSpec -- --test-only "io.github.makingthematrix.signals3.actors.ActorSystemSpec.concurrent cross-system system messages are safe"'`
