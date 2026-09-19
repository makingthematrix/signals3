# Actor System & References Implementation Proposal

**Vibe Session ID:** `fcbf1297-ni0lsm34`  
**Date:** 2026-09-10  
**Status:** Proposal (Awaiting Review)

---

## Overview

This document proposes a comprehensive design for implementing an **Actor System** with **Actor References** for the signals3 library. The design supports both local (single JVM) and distributed (multi-server) scenarios while maintaining full backward compatibility with the existing Actor implementation.

## Issues that are not important or will be addressed in the future

This is a list of issues that are raised often by the AI agent when asked for analyzing the ActorSystem code, but ones that are either non-issues (there is a reason why the code looks like that) or ones that will be fixed in a later step of development and for now can be ignored:
1. `private var` collections, like `children`, `actorRefs` and `systems`: They are modified as a result of processing messages, and messages are processed in a single thread, therefore there are no possible race collisions, even though the collections are not annotated as `@volatile` or wrapped in `AtomicReference`.
2. The unhandled messages: In a later stage of development, we will implement a logging mechanism and every unhandled message will be reported to the logger.
3. It is known that a message might be lost, especially if sent by ! (`bang`) to a remote actor system. The sender should be prepared to for such a situation, e.g. by re-sending a message if a confirmation does not arrive in time. 

---

## Core Design Principles

### Location Transparency
Actor references should work identically whether the target actor is:
- Local (same JVM)
- Remote (different JVM, same cluster)
- Distributed (different machine, networked)

### Type Safety
Maintain Scala's type system where possible, while supporting heterogeneous actor types in the registry.

### Non-Intrusive
Existing Actor code should continue to work without modification. ActorSystem is opt-in.

### Notes from the author

Persistence requires serialization of the actor state and behaviors' ids. For simplicity, we may assume that unprocessed messages are ignored.
But if we persist only the behaviors' ids, it means that there needs to be somewhere a hardcoded dictionary of behaviors that
we access at deserialization and take the PFs from. In vanilla Scala, PFs cannot be serialized, unlike e.g. Lua or Python,
that is interpreted languages where we can simply keep PFs' source code stored somewhere and load it in when needed.
(What are the ways to persist PFs in Scala?)

Persistence of a parent requires persitence of the children. That means, we need to assign certain ids to the actors -
we can't persist JVM references. We can even generate such ids at the moment of actor creation. And that helps us also
with another issue.

Ideally, the Actor model allows us to create a layer of abstraction where we don't care anymore if another actor is
on the same server. We may start an Actor app on two servers, and let them talk to each to other via some API
in such a way that when one actor sends a message to another, that message is serialized and sent over the network.
At the receiver's server the message is deserialized and delivered as if it was sent from someone on the same server.

If we knew that all actors are on the same server, we could simply use JVM references to deliver messages. But if
they can be spread out on different machines, we need to use actors' unique ids, similar to how we need them to
serialize and deserialize parent-children connections.

Okay, but I left out one important detail. There needs to be an entity on every server that given the id is able to
find the actor, and we need a sort of a higher level reference that consists of the id of the actor which we want to
contact and an actual reference to that intermediary entity. Just for simplicity, let's call the higher level
reference ActorRef, and the entity ActorDictionary. ActorDictionary can be implemented as a separate entity that
is responsible for mapping actor ids to actual actor references, but it can also be the top-level actor on each
server with special logic responsible for transferring messages.

When a new actor is created, it's added as a child to ActorDictionary or as a child to another child of ActorDictionary,
or further down the line, and its id and JVM reference is sent to ActorDictionary. Furthermore, if there is another
Actor app on another server, that's already connected, ActorDictionary will share that id together with its own id,
i.e. ths server's app id, with its counterpart from that other app. And in the same way, it receives ids of new actors
living on that other server.

When we call `actorRef ! msg`, we actually call `actorDict.transfer(actorId, msg)`. That actorDict then finds out
to which server `actorId` belongs to. If it's on the same server, then actorDict has its JVM reference and can
transfer `msg` directly. If not, it will serialize `msg` and send it to that other server together with the id of
the receiver.

But that's slow, right? If we only run one server, this layer of abstraction is completely unnecessary and it slows
down sending and receiving messages. At the same time, we would like to keep using the same functionality: we're
already used to it and there might be a possibility that in the future we would like to scale up our system, meaning
we could start our Actor app on multiple servers.

One solution we could use is to have different subclasses of ActorRef. Before an actor can contact another, it first
needs to request its ActorRef from ActorDictionary. At that point ActorDictionary can decide: if the requested actor
is on the same server, it can return a subclass of ActorRef that contains that actor's JVM reference, and so
`actorRef ! msg` will turn simply into `actor ! msg` - maybe with some additional checks to make sure that the actor
is still alive. If the requested actor is on a different server, ActorDictionary can return a subclass of ActorRef
that contains the id of the requested actor and the id of the server it's on. In that case, `actorRef ! msg` will
turn into a call to `actorDict.transfer(actorId, msg)` - which will serialize `msg` and send it to that other server
together with the id of the receiver.

	// todo: Pausable, v
	// todo: pausing and closing through special messages, v
	// todo: private var state: State for keeping and modifying internal state, v
	// todo: behaviors must have access to this actor to be able to mutate the state v
	// todo: heartbeat should be a strategy: Linear(ms), Agitated(min, coeff, max), Reactive v
	// todo: Scaladoc v
	// todo: unit tests v
	// todo: managing behaviors through messages v
	// todo: divide the Actor class into an immutable trait used outside and a mutable class that extends it - the behaviors use the latter v
	// todo: add the out stream that can be used by behaviors to send messages to v
	// todo: change the behaviors list to a map - all behaviors that fit for a given message are executed, not only the oldest one v
	// todo: change the name of finalBehavior to finalBehavior (the last behavior); the current one is confusing v
	// todo: change the behaviors back to a list xD v
	// todo: a way to request that a given message is handled by a behavior with the given id v
	// todo: similarly, there should be an `onClose` function (but that's already implemented) v
	// todo: onInit function that the actor can use, for example, to send out messages that it's alive v
	// todo: remove finalBehavior; unprocessed messages are ignored v
	// todo: serial actors can have fewer safe-guards (and in fact they should have)  v
	// todo: ActorBuilder v
	// todo: spawn sub-actors v
	// todo: close sub-actors when the parent is closed v
	// todo: ActorSystem where you can register new actors with unique ids v
	// todo: ActorRef (local) retrieved from ActorSystem, used to send messages to other actors v
	
	// todo: RemoteActorRef and the ability to register actors from another app via https
	// todo: RemoteActorRef should carry the ActorSystem id too to enable communication between different actor systems

	// todo: HealthCheck system message, sent from the parent to the child; if the child doesn't respond in time, the message is repeated, and the the child is closed
	// todo: consider to allow the children to use different types of messages ; and then: clusters? persistance?
	// todo: maybe think about plugging in a logging functionality so that an unprocessed message can be logged as a warning
	// todo: similarly about metrics
	// todo: and about the max number of messages processed per heartbeat
	// todo: make constants configurable through environment variables
	// todo: actors should carry tags (strings) and the actor system ca get requests to connect an actor with any other actor that has a given tag

---
Implementation Considerations

### Thread Safety
- Use `ConcurrentHashMap` for actor registries
- Atomic operations for state changes
- Immutable message passing

### Performance
- Local ActorRef should have minimal overhead (direct method calls)
- Remote ActorRef should batch messages where possible
- Connection pooling for remote systems

### Backward Compatibility
- Existing Actor usage remains unchanged
- ActorSystem is opt-in
- No breaking changes to existing APIs

### Testing Strategy
- Unit tests for LocalActorSystem and LocalActorRef
- Integration tests for RemoteActorSystem with mock transports
- End-to-end tests with real network communication
- Stress tests for concurrent registration and lookup

---

## Migration Path

1. **Phase 1**: Implement LocalActorSystem and LocalActorRef
   - No remote support
   - Basic registration and lookup
   - Full backward compatibility

2. **Phase 2**: Add transport layer and RemoteActorRef
   - Implement TCP transport
   - Add serialization
   - Remote actor communication

3. **Phase 3**: Add clustering support
   - Gossip protocol for cluster membership
   - Cluster-aware actor discovery
   - Failure detection

4. **Phase 4**: Optimization
   - Connection pooling
   - Message batching
   - Metrics and monitoring

---

## File Structure

```
src/main/scala/io/github/makingthematrix/signals3/actors/
├── Actor.scala           # Existing
├── ActorBuilder.scala    # Existing
├── ActorSystem.scala     # New - Core actor system
├── ActorRef.scala        # New - Actor reference trait and implementations
├── ActorPath.scala       # New - Actor path and addressing
├── ActorTransport.scala  # New - Transport layer abstraction
├── MessageSerializer.scala # New - Serialization support
├── cluster/             # Optional - Cluster support
│   ├── ActorCluster.scala
│   └── ClusterMember.scala
└── config/
    └── ActorSystemConfig.scala
```

---

## Notes

- This proposal maintains full backward compatibility with existing Actor code
- The design is non-intrusive - existing code works without modification
- ActorSystem is opt-in functionality
- Remote communication is transparent to users
- Type safety is preserved where possible

**Last Updated:** 2026-09-10  
**Next Steps:** Awaiting review and feedback

---

## Review: Remote Communication — Fourth Pass

**Vibe Session ID:** `8b4c27d5-5srq53hr`  
**Date:** 2026-09-19  
**Reviewer:** Mistral Vibe (automated analysis)

This section records findings from a re-analysis after the fixes for the
`toRef` registration race (#1) and the peer-cleanup-on-shutdown issue (#3).
Items already addressed across all passes are listed first, followed by
remaining issues. The "Issues that are not important or will be addressed in
the future" section (thread safety of `var` collections, unhandled messages,
message loss) is respected throughout.

### What was addressed

- **`RemoteActorRef` is no longer dead code.** It now has real producers:
  `AskForRemoteRef` / `AskForRemoteRefAsync` and `Actor.toRef`.
- **Decoupled from `ActorSystem`.** `RemoteActorRef` now holds
  `RemoteSystem[Msg, Rsp]`, so it can proxy to any future `RemoteSystem`
  implementation.
- **`isValid` removed** from `ActorRef`.
- **Case classes → `final class`** for both refs.
- **Dead `.withSystemIf` removed** from `ActorSystem.spawn`.
- **Clearer naming.** `AskForRef` → `AskForLocalRef`; new `AskForRemoteRef`
  family. `Spawn.id` → `Spawn.actorId`.
- **Design doc trimmed.**
- **`"local"` guard is unbypassable.** The constructor is `private`, so only
  `ActorSystem.apply` can construct one, and `apply` enforces
  `assert(id != "local")`.
- **System self-registration is synchronous.** `ActorSystem.initialize()`
  writes to `systems` and `actorRefs` before calling `super.initialize()`, on
  the constructing thread. The system is fully reachable the moment
  `ActorSystem.apply` returns.
- **`toRef` registration race fixed.** `bang`/`ask` no longer route directly on
  the caller's thread. They enqueue a `RemoteMsg(path, msg)` system message to
  the system's own `systemStream`, which is the same FIFO queue that `Register`
  messages go through. When the system processes its queue, `Register`
  messages (enqueued during child `initialize()`) are processed before any
  `RemoteMsg` enqueued after `spawn` returned. By the time `remoteBang` /
  `remoteAsk` reads `actorRefs`, the child is already registered. The race is
  eliminated.
- **Peer cleanup on shutdown.** `shutdown()` now sends `SystemClosed(id)` to
  all peers before calling `super.shutdown()`. Each peer's
  `bang(msg: RemoteSystemMsg)` matches `SystemClosed` and enqueues
  `UnregisterSystem(systemId)`, which removes the shutting-down system from the
  peer's `systems` map on its next heartbeat. Peers will no longer route to a
  dead system.

### Remaining issues

#### 1. Local message delivery through `bang`/`ask` now pays a heartbeat cycle of latency

This is the most significant trade-off introduced by the `toRef` race fix.
Previously, `bang(Local(actorId), msg)` called `actorRefs(actorId) ! msg`
synchronously on the caller's thread — zero latency. Now every `bang` / `ask`
through `RemoteSystem` (including `RemoteActorRef.!(msg)`, which is the output
of `toRef`) enqueues a `RemoteMsg` and waits for the next heartbeat to process
it. For a `Linear(100ms)` heartbeat, that is up to 100ms of added latency on
every message, even to actors in the same JVM.

This is a design choice, not a bug — the correctness benefit is real. But it
affects the library's core value proposition (lightweight, fast actors). If the
race only matters for `toRef` on freshly spawned children, you could route
through system messages only when the actor might not be registered yet, and
keep direct routing for known-registered actors. Or, `RemoteActorRef` could
hold a direct `LocalActorRef` when the target is local, bypassing the system
message queue. Worth a thought before the feature gets used in
latency-sensitive contexts.

#### 2. No loop detection — now an async infinite loop instead of stack overflow

The `RemoteMsg` mediation fixes the `StackOverflowError` problem (multi-hop
forwarding is now async, one heartbeat per hop), but it is still an infinite
loop. If A and B are cross-registered and an actor id is missing on both,
`RemoteMsg` bounces A -> B -> A -> B indefinitely, consuming CPU and
generating messages forever. A TTL counter or a visited-set in
`ActorPath.Remote` would bound this. The fix for the `toRef` race made this
easier to trigger (any `RemoteActorRef` to a non-existent actor on a
cross-registered pair of systems will do it).

#### 3. `RemoteMsg`/`RemoteRsp` in `SystemMsg` blurs system/user message separation

`SystemMsg` was purely about lifecycle and management (Pause, Close, Register,
Spawn, etc.). It now carries user-level `Msg` and `Rsp` types via
`RemoteMsg(path: ActorPath, msg: Msg)` and `RemoteRsp(rsp: Rsp)` (`Actor.scala`).
Every `Actor`'s `SystemMsg` enum now includes these cases, even though they are
only meaningful for `ActorSystem`. Regular actors' `processSysEntry` falls
through to `case _ => // @todo: log`, so a `RemoteMsg` sent to a regular actor
is silently ignored — which is fine functionally, but the coupling is
inelegant. If `RemoteMsg` / `RemoteRsp` lived in a separate `ActorSystem`-
specific message type, the `SystemMsg` enum could stay focused on actor
lifecycle.

#### 4. `AskForRemoteRef` returns a ref without verifying the actor exists

`ActorSystem.scala` — `AskForRemoteRef` checks that the peer *system* is
registered, but not that the peer has the actor. `AskForLocalRef` checks
`actorRefs.get(actorId)` and returns `InvalidId` if the actor is missing. The
remote variant cannot (it has no access to the peer's registry), but the result
is that `AskForRemoteRef` returns a `Ref` that looks like a successful lookup
yet will silently drop `!` (or fail `?`) at the peer if the actor doesn't
exist there. The name implies "ask if this actor exists remotely"; the behavior
is "make a ref to a remote system+actorId whether or not the actor is there."
Worth a name like `MakeRemoteRef` or a doc note that success means "system
reachable," not "actor found."

There is also an inconsistency when `systemId == this.id`: the system
self-registers in `systems`, so `AskForRemoteRef("nonexistent", this.id)`
returns a `Ref`, while `AskForLocalRef("nonexistent")` returns `InvalidId` —
same system, same actor, different answers depending on which API you call.

#### 5. `shutdown()` sends `SystemClosed` to peers fire-and-forget, doesn't wait

`shutdown()` calls `sys ! RemoteSystemMsg.SystemClosed(id)` (bang, not ask) for
each peer, then immediately calls `super.shutdown()`. A peer that is slow to
process the `UnregisterSystem` will still try to route to the shutting-down
system in the meantime. Per the "not important" list (message loss is
expected), this is acceptable. Just noting that the cleanup is eventual, not
immediate.

#### 6. `ask` for `RemoteSystemMsg` — conversion acknowledged but lossy

`ActorSystem.ask(msg: RemoteSystemMsg)` converts `SystemMsg.Done` to
`RemoteSystemMsg.Done` and everything else to `RemoteSystemMsg.InvalidId`. The
`@todo` comment flags this as clunky. The lossy part: `UnregisterSystem` always
returns `Done` (it just does `systems -= systemId`), so the `InvalidId` branch
is unreachable in practice. But if `UnregisterSystem` ever gains error
semantics, the conversion would silently mask the specific error. Minor for
now.

#### 7. Unused `self =>` alias

`ActorSystem.scala` has `self =>` but it is never referenced. Remove or use
it.

#### 8. No tests for any remote feature

Still zero tests for `RegisterSystem`, `UnregisterSystem`,
`AskForRemoteRef` / `AskForRemoteRefAsync`, cross-system `bang` / `ask` through
`RemoteMsg`, `SystemClosed` cleanup, `toRef` returning a `RemoteActorRef`, or
the `RemoteMsg` / `RemoteRsp` round-trip. The `toRef` race fix and the
`SystemClosed` cleanup are both testable with two systems and a few
assertions. Given that these are the two most recent fixes and both involve
subtle ordering, tests would provide the most value here.

### Summary

The `toRef` registration race and the peer-cleanup-on-shutdown issue are both
correctly fixed. The `RemoteMsg` mediation elegantly solves the registration
race by serializing routing through the same FIFO queue as `Register`, and
`SystemClosed` correctly notifies peers to clean up. The main cost is latency:
all `RemoteSystem`-routed messages now wait one heartbeat, even local ones.
The remaining issues are the missing loop guard (#2, now an async infinite
loop rather than a crash), the `SystemMsg` / user-message coupling (#3), the
`AskForRemoteRef` over-promise (#4), and the absence of tests (#8).
