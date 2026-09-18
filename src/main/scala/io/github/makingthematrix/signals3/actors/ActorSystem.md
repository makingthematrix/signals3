# Actor System & References Implementation Proposal

**Vibe Session ID:** `fcbf1297-ni0lsm34`  
**Date:** 2026-09-10  
**Status:** Proposal (Awaiting Review)

---

## Overview

This document proposes a comprehensive design for implementing an **Actor System** with **Actor References** for the signals3 library. The design supports both local (single JVM) and distributed (multi-server) scenarios while maintaining full backward compatibility with the existing Actor implementation.

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

## Review: Remote Communication — Second Pass

**Vibe Session ID:** `8b4c27d5-5srq53hr`  
**Date:** 2026-09-18  
**Reviewer:** Mistral Vibe (automated analysis)

This section records findings from a re-analysis of the remote-communication
feature after the changes made in response to the first review. Items already
addressed are listed first, followed by remaining issues.

### What was addressed

- **`RemoteActorRef` is no longer dead code.** It now has real producers:
  `AskForRemoteRef` / `AskForRemoteRefAsync` (`ActorSystem.scala:45-55`) and
  `Actor.toRef` (`ActorImpl.scala:319-320`). The feature is genuinely wired up.
- **Decoupled from `ActorSystem`.** `RemoteActorRef` now holds
  `RemoteSystem[Msg, Rsp]` (`ActorRef.scala:24`), so it can proxy to any future
  `RemoteSystem` implementation, not just `ActorSystem`.
- **`isValid` removed** from `ActorRef`. Better than leaving a lie — the trait no
  longer claims to know validity it cannot compute.
- **Case classes → `final class`.** Both refs are now `final class`, removing
  spurious `equals` / `hashCode` / `copy` over mutable actor proxies.
- **Dead `.withSystemIf` removed** from `ActorSystem.spawn`.
- **Clearer naming.** `AskForRef` → `AskForLocalRef`; new `AskForRemoteRef`
  family. `Spawn.id` → `Spawn.actorId`.
- **Design doc trimmed**, removing the misleading host/port transport design that
  did not match the implementation.

### Remaining issues

#### 1. The `toRef` registration race (most important new issue)

`ActorImpl.toRef` (`ActorImpl.scala:319-320`) now returns a `RemoteActorRef` for
any actor whose `system` is set:

```scala
override lazy val toRef: ActorRef[Msg, Rsp] =
    system.map(s => RemoteActorRef(ActorPath.Remote(s.id, id), s)).getOrElse(toLocalRef)
```

This routes through `ActorSystem.bang`, which matches
`Remote(\`id\`, actorId) if actorRefs.contains(actorId)`. But the actor only
registers itself in `actorRefs` asynchronously, via `Register` enqueued during
`initialize()` (`ActorImpl.scala:270`) and processed on the system's heartbeat.
So:

```scala
val actor = builder.build()   // Register enqueued, not yet processed
val ref = actor.toRef          // RemoteActorRef created, looks valid
ref ! msg                      // bang: actorRefs.contains(actorId) is false -> case _ -> dropped
```

`toRef` hands you a usable-looking ref at the exact moment `build()` returns,
before registration is guaranteed to have completed. The intended pattern for
`AskForLocalRef` is to poll (`awaitRef` in tests), but `toRef` bypasses that wait
entirely. This is the most natural way to use `toRef` and it silently drops
messages. Either `toRef` should not exist before registration completes (block,
or return an unregistered marker), or `bang` should enqueue-then-route instead
of route-or-drop.

#### 2. `AskForRemoteRef` returns a ref without verifying the actor exists

`ActorSystem.scala:45-49`:

```scala
case (AskForRemoteRef(actorId, systemId), p) =>
    val rsp = systems.get(systemId)
        .map { s => Ref(RemoteActorRef(ActorPath.Remote(systemId, actorId), s)) }
        .getOrElse(InvalidId)
```

It checks that the peer *system* is registered, but not that the peer has the
actor. `AskForLocalRef` checks `actorRefs.get(actorId)`. The remote variant
cannot (it has no access to the peer's registry), but the result is that
`AskForRemoteRef` returns a `Ref` that looks like a successful lookup yet will
silently drop `!` (or fail `?`) at the peer if the actor doesn't exist there.
The name implies "ask if this actor exists remotely"; the behavior is "make a
ref to a remote system+actorId whether or not the actor is there." Worth a name
like `MakeRemoteRef` or a doc note that success means "system reachable," not
"actor found."

#### 3. `actorRefs` and `systems` are still unguarded `var Map`

`ActorSystem.scala:17-18` — unchanged. Written from `processSysEntry` (on the
system's processing future), read synchronously from `bang` / `ask` on arbitrary
caller threads. No `@volatile` or `AtomicReference`. The immutable maps won't
corrupt, but a writer thread's publication of a new map has no happens-before
relationship with a reader thread — stale reads are possible. This is the
underlying mechanism that makes the `toRef` race above nondeterministic rather
than merely "happens before the first beat."

#### 4. No loop detection in multi-hop forwarding

`bang` / `ask` forward `Remote(systemId, _)` to `systems(systemId)`
(`ActorSystem.scala:80, 88`), which re-resolves the same path on the peer. If A
and B are cross-registered and an actor id is missing on both, `bang` recurses
A -> B -> A -> B ... with no TTL or visited set. For `bang` this is unbounded
stack recursion; for `ask` it is unbounded future chaining. The `toRef` change
makes this easier to hit accidentally (a `RemoteActorRef` to a system that
doesn't have the actor, forwarded to a system that doesn't have it either).

#### 5. `AskForRemoteRefAsync` still reports `Done` on undelivered delivery

`ActorSystem.scala:50-55` — same shape as the old `AskForLocalRefAsync`.
`sender ! rsp` is fire-and-forget (a no-op if the sender is closed, per
`ActorImpl.bang:168`), and `respond(p, Done)` is unconditional. The asker gets
`Done` regardless of whether the `Ref` / `InvalidId` was actually delivered.
Logging/acknowledgement is deferred, so this is only noted here.

#### 6. The `"local"` guard is bypassable

`assert(id != "local")` is in `ActorSystem.apply` (`ActorSystem.scala:96`) but
not in the `new ActorSystem(...)` constructor, which is used directly in tests
(`ActorSystemSpec.scala:141`) and is `public` (the class is `final`, not
`private`). So the reserved-name collision is guarded on the factory path but
not the constructor path. Moving the assert into the constructor body, or
making the constructor `private[actors]`, would close this.

#### 7. No tests for any remote feature

Still zero tests for `RegisterSystem`, `AskForRemoteRef` /
`AskForRemoteRefAsync`, cross-system `bang` / `ask`, `toRef` returning a
`RemoteActorRef`, or `RemoteActorRef` in general. The feature is now real and
reachable, but every path above is unexercised. Given the heartbeat-async
registration model, the `toRef` race in particular will not surface without a
test that sends immediately after `build()`.

### Summary

The changes fixed the structural problems flagged in the first review:
`RemoteActorRef` is now a real, decoupled, non-dead-code class with actual
producers. The `isValid` lie is gone. The case-class misuse is gone. The dead
builder line is gone. Naming is clearer.

What remains is concentrated in two areas. First, the async-registration /
sync-routing mismatch — now more consequential because `toRef` hands out
routing refs immediately after `build()`, before the async `Register` is
guaranteed to have landed in `actorRefs`. Combined with the un-`@volatile`'d
maps, this is a nondeterministic silent-drop bug reachable through the most
natural usage of `toRef`. Second, `AskForRemoteRef` over-promises: it returns a
ref on system-reachability, not actor-existence, and there is still no loop
guard for multi-hop forwarding. Both are worth addressing before the feature
gets used, even with logging and heterogeneous typing deferred.
