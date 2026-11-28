# Behaviors Registry — Vulnerability Review

Analysis of the `Behaviors` object and its users (branch `actors`, HEAD `0497046` "Better unregistering of no more used behaviors",
which includes the PBeh composition API from `70f8fb6` "Behaviors compositions").

The review covered:

- `Behaviors.scala` — the four global registries (`behs`, `fails`, `pbehs`, `tbehs`), the `PBeh`/`TBeh` objects and their
  composition methods (`or`, `and`, `orElseId`, `andThenId`, the extensions), and `reset()`;
- `priv/BehaviorRegistry.scala` — the per-event registry with the new one-shot cleanup;
- `CanBeClosed`, `CanBeInitialized` — the lifecycle sugar, now always one-shot;
- `actors/ActorImpl`, `actors/ActorBuilder`, `actors/Actor` — registration and dispatch call sites;
- `generators/CloseableGenerator*`, `priv/Closeability` — the new reset-on-close cleanup;
- `IdGenerator` — id generation.

The two concurrency findings were **reproduced empirically** with a stress test run against the real API
(8 threads, 160,000 generated-id registrations + 500 rounds of same-id registration races; the smoke was
temporary and is not part of the tree).

---

## CRITICAL — the global registries are not thread-safe, and they lose data in normal use

`Behaviors` stores all four kinds of behaviors in `scala.collection.mutable.HashMap`s with no synchronization:

```scala
private val behs  = mutable.HashMap[String, () => Any]()
private val fails = mutable.HashMap[String, Throwable => Any]()
private val pbehs = mutable.HashMap[String, AnyPF]()
private val tbehs = mutable.HashMap[String, AnyTF]()
```

Concurrent mutation of a `mutable.HashMap` is undefined behavior: entries are lost, and concurrent resizes can
throw or produce inconsistent state.

**Reproduced, two ways:**

1. **Registration storm** — 8 threads x 20,000 `registerF(() => (), prefix)` calls with concurrent
   `isRegistered` lookups: **13,647 of 160,000 registrations silently lost (8.5%)**.
2. **Explicit-id race** — two threads registering different functions under the *same* id, 500 rounds
   (synchronized start per round): **in 22 rounds BOTH registrations returned `true`** — a lost update where
   one function silently overwrote the other and both callers believe their function is registered.

**Why this is the main use case, not an edge case.** `Behaviors` is a global singleton *written* from user and
builder threads (`ActorBuilder.setBehaviorPF` and every `with*F`/`addOn*F` sugar, `BehaviorRegistry.addOneShot`
behind every stream lifecycle registration, `Actor.apply`) and *read* on every actor's dispatch thread for every
message processed. Any multi-threaded application using actors or streams hits these maps concurrently.

**Impact.** A lost registration means an id that resolves to nothing. By the missing-id policy (D2: silent no-op)
this produces *no error anywhere*: an actor silently stops handling messages (every dispatch yields `NoResponse`
for asks and drops bangs), a stream's `onClose` silently never runs. The explicit-id race additionally breaks the
documented contract "registering with a taken id fails and keeps the original" — under the race the original is
silently replaced and both callers get `true`.

**Fix (small and contained):**

- back the four registries with `java.util.concurrent.ConcurrentHashMap`;
- make registration `map.putIfAbsent(id, f) == null` — this both replaces the `isRegistered` check-then-act
  (`registerBeh`/`registerPBeh`/`registerTBeh`/`registerFail`) and closes the TOCTOU window, since the
  uniqueness check and the insert become one atomic step;
- `unregister` and `isRegistered` translate directly (`remove`, `containsKey`).

The same exposure exists at a smaller scale in `priv.BehaviorRegistry`: the per-event `mutable.HashSet`s
(`behs`, `fails`, `oneshots`) are mutated by `AddOn*`/`Remove*` system messages and user code while the event
itself iterates them on the object's thread. `Collections.newSetFromMap(new ConcurrentHashMap())` — or a
documented single-threaded registration rule — would close it.

---

## HIGH — behavior ids are unscoped capabilities (type confusion and cross-actor invocation)

Two accepted-design decisions combine into the largest *design-level* exposure:

1. **Unchecked casts at retrieval.** `getPBeh[Msg, State]`/`getTBeh[Msg, State]` restore type parameters by a
   blind `asInstanceOf`; the library trusts that whoever retrieves an id asks for the same types it was
   registered with (documented in the scaladoc, decision D6: type-safety hardening deferred).
2. **Named dispatch without scoping.** A message with a behavior id resolves *any* id registered in the whole
   JVM, with no attachment or ownership check. This works through `actor ? ("id" -> msg)`, `tell(msg, id)`,
   path-based `ask(msg, path, id)` — and across systems, via `RemoteActorRef` (exercised by
   ActorSystemRemoteSpec "behavior ids are honored through a RemoteActorRef and through path-based routing").

Consequences:

- Any actor that can reach another actor — **including a remote actor system peer** — can invoke any registered
  partial behavior with an arbitrary message payload. Ids are plain strings: no namespace, no owner, no
  capability check. If a remote system is ever treated as a trust boundary, this is an
  arbitrary-behavior-invocation primitive.
- Retrieving an id with the wrong type parameters fails only at invocation time, deep inside dispatch, with
  `MatchError`/`ClassCastException` — never rejected at the boundary.

**Possible mitigations (when returning to D6):** store `ClassTag[Msg]`/`ClassTag[State]` (or a `TypeTest`) at
registration and verify it in `getPBeh`/`getTBeh`; scope named dispatch to ids on the actor's behavior stack;
or namespace ids per actor system.

---

## MEDIUM

1. **`registerF`/`registerPF`/`registerTF`/`registerFailCase` discard the registration result.**
   `IdGenerator.generate(prefix).tap { id => registerBeh(id -> f) }` ignores the `Boolean`. If registration
   ever fails (today: the race above; in future: any guard), the caller holds an id that points at a different
   function — or nothing — with no signal at all. The UUID-based ids make a collision unlikely once the
   thread-safety fix lands, but the silent drop should still not exist.

2. **`BehaviorRegistry.run` catches `Throwable`.** `OutOfMemoryError`, `LinkageError`, and
   `InterruptedException` all flow into user failure handlers. Additionally, if a *failure handler itself*
   throws, the exception escapes `run()` entirely — into `close()`/`closeAndCheck()` and from there into
   whatever thread runs the close. Catch `scala.util.control.NonFatal`, and wrap the fail handlers in their
   own try.

3. **Silent no-op on missing ids + remote dispatch = unobservable misconfiguration.** A stale or mistyped id
   referenced from a behavior stack, lifecycle set, or remote message produces no observable failure. At
   minimum a debug-level reporting hook would make these diagnosable in production. (Related: the only
   diagnostics that exist are `println`s — see LOW 2.)

---

## LOW

1. **`Behaviors.reset()` is a public, JVM-global wipe.** One accidental call in production silently disables
   every actor and stream in the process (every id resolves to nothing). Document it as test-only, or guard
   it behind an explicit opt-in.

2. **println-only diagnostics.** Collisions, invalid ids in composition, and similar conditions are reported
   only to stdout — invisible under production logging setups, no stack traces. (Also a typo in the current
   message: `PBeh.getvalidPBeh, invalid PBeh ids: ...`.) A tiny logging hook or returning richer failures
   would cover this.

3. **Memory gaps in the (otherwise well-built) one-shot cleanup.** The new scheme is safe by construction:
   only generated, UUID-based ids are ever auto-unregistered, and `remove()` checks `oneshots` membership
   first, so ids the user shares between objects are never touched. Remaining gaps:
   - an actor closed **before** initialization leaks its `onInit` one-shot registrations forever — the new
     close path resets pause/unpause/beat but not init;
   - objects that never fire their event and are never closed retain their registrations permanently; the
     registered closures capture user state, so dynamic creation patterns grow the registry unboundedly;
   - `PBeh.or`/`and` (and the extensions) register a fresh composite on every call — composing per message
     would register one new behavior per message. Worth a "compose once, at setup" note in the scaladoc.

4. **`IdGenerator` is thread-safe** — `java.util.Random` is CAS-based and `UUID.randomUUID` does not collide.
   Two test-only nits: `setSeed`'s check-then-act on a plain `var` can be raced, and once `lazy val random`
   has been forced by a `generate` call, a later `setSeed` has no effect. Neither matters in production.

---

## What is already good

- The one-shot policy preserves the "users may share ids" rule exactly: `addId`-attached ids are never
  auto-unregistered; only freshly generated ids are.
- `reset-on-close` for pause/unpause/beat in `ActorImpl` and the generators removes the largest known leak class.
- The composition API reports failures via `Option` and never silently overwrites a taken id (single-threaded).
- Ids embed a full UUID, so generated-id collisions are practically impossible — the concrete corruption paths
  are the concurrency races above, not the generator.

## Recommended order of work

1. ConcurrentHashMap + `putIfAbsent` in `Behaviors` (CRITICAL) — re-run the stress test, expecting 0 lost
   entries and 0 double-wins. **DONE (uncommitted):** the four registries are now `ConcurrentHashMap`s with
   lock-free reads and all writes (register/unregister/reset) under a single private lock, which keeps the
   cross-registry uniqueness check and the insert one atomic step. Stress re-run: 0 double-wins in 500
   same-id rounds, 0 of 160,000 registrations lost; full suite 672/672 green.
2. Same treatment (or a threading rule) for `BehaviorRegistry`'s HashSets. **DONE (uncommitted):** the three
   per-event sets (`behs`, `fails`, `oneshots`) are now `ConcurrentHashMap.newKeySet`s. No lock is needed —
   unlike `Behaviors` there is no cross-registry uniqueness invariant; the concurrent sets alone close the
   corruption class, and iteration during `run()` is weakly consistent (an id added mid-run simply runs at the
   next firing). Stress-verified: 4 concurrent attach threads + 5,000 one-shot registrations against 2 threads
   firing the event 100 times each — no exceptions, all ids survived; full suite 672/672 green.
3. `NonFatal` + guarded fail handlers in `BehaviorRegistry.run` (MEDIUM 2).
4. Stop discarding registration results in the `registerF`-family (MEDIUM 1).
5. `resetOnInit` on close (LOW 3) and the "compose once" scaladoc note.
6. D6 type-safety hardening and id scoping (HIGH) — a design discussion, deliberately deferred until now.
