---
id: signals3-signal
type: module-design
status: active
title: Signal Module
parent: signals3
---

## Responsibility

The Signal module is responsible for:

- Providing the core Signal class implementation
- Implementing reactive programming patterns
- Managing signal subscriptions and notifications
- Supporting signal transformations (map, filter, flatMap, etc.)
- Handling value caching and change detection
- Providing thread-safe operations
- Supporting execution context management
- Implementing error handling and recovery mechanisms
- Providing integration with Scala futures and collections

## Boundary

The Signal module interacts with:

- **Scala Standard Library**: For collections, futures, and functional programming constructs
- **ExecutionContext**: For thread management and dispatching
- **EventContext**: For subscription lifecycle management
- **Java Concurrency Utilities**: For thread synchronization
- **Client Code**: Applications that use signals for reactive programming
- **Testing Framework**: For verifying signal behavior

## Key Components

1. **Signal[V]**: The main signal class with value caching
2. **SourceSignal[V]**: A signal that can receive external values
3. **EventSource[V, Subscriber]**: Base class for event dispatching
4. **SignalSubscriber**: Interface for signal subscribers
5. **SignalSubscription**: Manages the lifecycle of a subscription
6. **Various specialized signal classes**: For transformations, combinations, etc.
