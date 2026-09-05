---
id: signals3-actor-builder
type: module-design
status: active
title: ActorBuilder Module Specification
parent: signals3-actor
tags: [actor, builder, fluent-api]
---

## Responsibility

The `ActorBuilder` class provides a fluent API for configuring and creating `Actor` instances. It consolidates the 24 factory methods in the `Actor` companion object into a single, type-safe builder pattern that supports all configuration combinations.

## Boundary

### Public API

- `ActorBuilder[Msg, Rsp, State](initialState: State)` - Creates a new builder with initial state
- `withState(newState: State)` - Sets the initial state
- `withBehavior(pf: PF[Msg, Rsp, State])` - Adds a behavior with auto-generated ID
- `withBehavior(id: String, pf: PF[Msg, Rsp, State])` - Adds a behavior with explicit ID
- `withBehavior(behavior: Beh[Msg, Rsp, State])` - Adds a behavior as a (String, PF) tuple
- `withBehaviors(behaviors: Iterable[Beh[Msg, Rsp, State]])` - Adds multiple behaviors with explicit IDs
- `withBehaviors(behaviors: Iterable[PF[Msg, Rsp, State]])` - Adds multiple behaviors with auto-generated IDs
- `withHeartbeat(strategy: HeartBeatStrategy)` - Sets a custom heartbeat strategy
- `withLinearHeartbeat(ms: Long)` - Sets a linear heartbeat strategy
- `withAgitatedHeartbeat(minMs: Long, coeff: Double, maxMs: Long)` - Sets an agitated heartbeat strategy
- `withReactiveHeartbeat(maxMs: Long, maxMsgs: Int)` - Sets a reactive heartbeat strategy
- `withOnInit(callback: MutableActor[Msg, Rsp, State] => Unit)` - Sets an initialization callback
- `withSerialDispatch()` - Configures the actor to use serial dispatch
- `withExecutionContext(ec: ExecutionContext)` - Sets a custom execution context for parallel dispatch
- `build()(using ec: ExecutionContext)` - Builds the actor with the configured settings
- `buildAsync()(using ec: ExecutionContext)` - Alias for build()

### Pre-defined Heartbeat Strategies

- `Linear100ms` - Linear heartbeat with 100ms interval
- `Reactive100ms10` - Reactive heartbeat with 100ms interval and 10 message threshold

### Internal Implementation

- Uses immutable state - each configuration method returns a new `ActorBuilder` instance
- Behaviors are stored in a list and prepended (LIFO order - last added matches first)
- The `build()` method selects the appropriate factory method based on configuration:
  - Serial vs non-serial dispatch
  - Single behavior vs multiple behaviors
  - Presence of onInit callback
- For multiple behaviors, adds them one by one to preserve behavior IDs

## Contract

### Behavior Order

Behaviors are matched in **LIFO order** (Last In, First Out). The most recently added behavior is checked first when a message arrives. This matches the behavior of the underlying `Actor` class.

### Thread Safety

The `ActorBuilder` itself is **immutable** and thread-safe. Each configuration method returns a new instance. The built `Actor` inherits the thread-safety properties of the underlying `Actor` implementation.

### Execution Context

- For **serial dispatch**: The ExecutionContext is ignored as serial actors always use `ExecutionContext.global` internally
- For **parallel dispatch**: The ExecutionContext passed to `build()` or set via `withExecutionContext()` is used
- The `onInit` callback uses the same ExecutionContext as the actor

### Initialization

The `onInit` callback is invoked exactly once when the actor is initialized, before message processing begins. The callback receives the `MutableActor` instance and can modify its state.

## Decisions

### D1: Fluent API Design

**Decision**: Use method chaining with a fluent API pattern.

**Rationale**: 
- Provides a clean, readable syntax for actor configuration
- Type-safe - compile-time checking of configuration combinations
- Discoverable - IDE autocomplete helps users find available methods
- Consistent with modern Scala API design patterns

**Alternatives Considered**:
- A: Separate configuration classes - More verbose, less fluent
- B: DSL with implicit conversions - More complex, harder to understand

### D2: Immutable Builder

**Decision**: Each configuration method returns a new `ActorBuilder` instance.

**Rationale**:
- Thread-safe by design
- Functional programming style
- Easy to reason about
- No need for defensive copying

**Alternatives Considered**:
- A: Mutable builder - Less thread-safe, requires careful usage

### D3: Behavior ID Preservation

**Decision**: When multiple behaviors are configured, add them one by one to preserve behavior IDs.

**Rationale**:
- Behavior IDs are used for removing behaviors by ID
- The `Actor` factory methods for `List[PF]` don't preserve IDs (they generate new ones)
- Adding behaviors one by one ensures IDs are preserved

**Trade-off**: Slightly less efficient than batch adding, but maintains correctness

### D4: LIFO Behavior Order

**Decision**: Maintain LIFO (Last In, First Out) order for behavior matching.

**Rationale**:
- Matches the underlying `Actor` class behavior
- Intuitive - most recently added behavior takes precedence
- Consistent with the existing factory methods

**Alternatives Considered**:
- A: FIFO order - Would require reversing the behaviors list, inconsistent with Actor

### D5: Pre-defined Heartbeat Strategies

**Decision**: Provide pre-defined heartbeat strategy constants for common use cases.

**Rationale**:
- Convenience for users
- Reduces boilerplate
- Common strategies are easily accessible

## Dependencies

- **Depends on**: `signals3-actor` (Actor trait and implementation)
- **Uses**: Scala 3 standard library, `java.util.UUID` for ID generation

## Location

- **Source**: `src/main/scala/io/github/makingthematrix/signals3/actors/ActorBuilder.scala`
- **Tests**: `src/test/scala/io/github/makingthematrix/signals3/actors/ActorBuilderSpec.scala`
- **Spec**: `specs/signals3/ActorBuilder/SPEC.md`
