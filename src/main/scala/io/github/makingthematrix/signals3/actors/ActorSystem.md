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

## Review: Remote Communication — Fifth Pass

**Vibe Session ID:** `36556460-d282-69df-6f7e-ff2a8da27442`  
**Date:** 2026-09-21  
**Reviewer:** Mistral Vibe (automated analysis)

This section records findings from a re-analysis after the `ask`/`bang`
refactoring around `ActorPath` (commit `5c05969`), the follow-up vulnerability
fixes (commit `95c212b`), and the new test suites (commits `99bd42c`,
`c810fbb`). All findings were verified at runtime with dedicated specs, not
only by reading the code. The "Issues that are not important or will be
addressed in the future" section (thread safety of `var` collections, unhandled
messages, message loss) is respected throughout.

### What was addressed

- **Heartbeat latency eliminated (fourth pass #1).** `bang`/`ask` route
  synchronously again, and same-system refs obtained via `AskForRef` are
  `LocalActorRef`s holding a direct JVM reference, so delivery never consults
  the registry. Ref-based sends have zero latency and no registration race;
  `toRef` / `toLocalRef` are gone.
- **Looping made structurally impossible (fourth pass #2).** The forward case
  in `ActorSystem.bang`/`ask` requires `systemId != id`, and a path never
  changes in transit, so only the system named in the path can deliver or drop
  a message. No TTL or visited-set is needed. (An intermediate refactor briefly
  reintroduced a synchronous `StackOverflowError` self-recursion via the
  self-registration in `systems`; it is fixed and pinned by tests.)
- **`SystemMsg` decoupled from user messages (fourth pass #3).** `RemoteMsg` /
  `RemoteRsp` are removed from `SystemMsg`; routing is now
  `bang`/`ask(msg, path, behId)`.
- **`AskForRef` no longer over-promises (fourth pass #4).** The cross-system
  variant performs a round trip (`RemoteSystemMsg.AskForRef`) and fails when
  the peer does not have the actor; `AskForRef(actorId, ownId)` routes as a
  local lookup. A TOCTOU where the peer's eager registry check beat a pending
  `Register` was fixed by queueing the lookup through the peer's message queue
  (FIFO behind `Register`), which also makes it deterministic.
- **Unused `self =>` removed (fourth pass #7).**
- **Remote features are tested (fourth pass #8).** `ActorSystemRemoteSpec`
  covers routing regressions, system lifecycle (`SystemClosed`, re-registration,
  `UnregisterSystem`), stale refs, `behId` routing through refs and paths, the
  message-loss policy, cross-system concurrency, and the TOCTOU regressions;
  `ActorPathSpec` covers path parsing and formatting; `ActorSystemSpec` covers
  the own-system-id lookup.
- **`AskForRefAsync` failure path.** An unknown system id used to leave the
  asker's future permanently uncompleted; it now completes with `Failure`.
- **Cross-thread visibility.** `actorRefs` and `systems` are `@volatile`, since
  synchronous routing reads them from the caller's thread.
- **Cross-system actor-id collision.** A path naming the peer's system reaches
  the peer's actor even when the sender has an actor with the same id.
- **`ActorPath.asString` bug.** The `val` in the trait evaluated during
  construction, before subclass vals were assigned, so
  `Local("a").asString` returned `"null://a"`. It is now a `def`.
- **Invalid ids fail (policy, in progress).** Invalid system ids fail the
  asking future with `IllegalArgumentException` instead of a sentinel; the
  `InvalidId` sentinel is being retired to places where a `SystemMsg` is
  returned directly.

### Remaining issues

#### 1. Invalid-id semantics are not yet uniform

Local `AskForRef` for an unknown actor returns the `InvalidId` sentinel (inside
a future), while the cross-system variant returns `Failure`. Similarly,
`AskForRefAsync` notifies the sender with `InvalidId` on a local miss, but on
a remote miss the asker gets a `Failure` and the sender receives nothing.
Finish the planned sweep so the sentinel appears only where a `SystemMsg` is
returned directly.

#### 2. Wrong error message for own-system actor misses

`ask(msg, Remote(ownId, missingActor), _)` falls through to the
`Remote(systemId, _)` case and reports `"Invalid system id: <own id>"`. It
should report an invalid actor id. The message-loss tests assert only failure,
not the message, so they will not need updating.

#### 3. Path-based sends to not-yet-registered actors drop silently

`bang(msg, Remote(ownId, childId))` immediately after `spawn` is dropped,
because routing reads `actorRefs` on the caller's thread while the child's
`Register` is still queued. Ref-based sends are immune (direct JVM reference).
Either document that paths are only for known-registered ids, or make
same-system misses fall back to enqueueing through the system's FIFO queue,
behind `Register` — which would make path-based sends as reliable as refs.

#### 4. `SystemClosed` cleanup is eventual and one-directional

`shutdown()` notifies only the systems the closing system knows about.
`RegisterSystem` is one-directional, so a system that a peer registered
unilaterally never learns about the peer's shutdown and keeps routing to it.
Fire-and-forget `SystemClosed` is acceptable per the message-loss policy, but
consider making registration bidirectional, or rely on the planned HealthCheck
system message, before a real network transport exists.

#### 5. Cross-system control messages need the recipient's path-dependent `SystemMsg`

`b ? a.SystemMsg.RegisterSystem(a)` does not compile — every system has its
own `SystemMsg` enum. `RegisterSystem` / `UnregisterSystem` could live in a
shared, non-path-dependent type, the way `RemoteSystemMsg` already does.

#### 6. The `@todo` conversion in `ask(msg: RemoteSystemMsg)` (fourth pass #6, unchanged)

`UnregisterSystem` always returns `Done`, so the error branch is unreachable
today; if it ever gains error semantics, the conversion would silently mask
them.

#### 7. Dropped and unhandled messages have no observability yet

The fall-through in `processSysEntry`, `bang`'s
`case _ => // invalid system or actor id`, and unhandled `RemoteSystemMsg`
are all silent. Wire them to the logging mechanism when it lands (an author's
planned item).

#### 8. Minor

The `asInstanceOf` on `RemoteSystemMsg.Ref` is safe only because `systems` is
homogeneous `RemoteSystem[Msg, Rsp]` — worth a comment or tighter typing.
`RemoteSystem.scala` is missing a trailing newline.

### Summary

The `toRef` registration race is fixed structurally (refs over paths, direct
local delivery), routing is crash-free and loop-free, cross-system lookups
verify actor existence race-free, and the remote surface is covered by tests
(the whole project suite, 596 tests, passes). What remains is mostly
consistency work — finishing the invalid-id semantics sweep (#1, #2), deciding
how reliable path-based sends must be (#3) — plus lifecycle hardening (#4, #5)
before a real network transport is built on top of `RemoteSystem`.
