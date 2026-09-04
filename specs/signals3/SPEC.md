---
id: signals3
type: architecture-design
status: active
title: Signals3 Architecture
---

## Drivers

- Need for reactive programming patterns in Scala applications
- Requirement for efficient data flow between different execution contexts
- Need to maintain state in event-driven applications
- Requirement for composable event streams with functional programming patterns
- Need for thread-safe operations in concurrent environments

## Decisions

- **Functional API**: Designed with a functional programming approach, supporting map, flatMap, filter, etc.
- **Thread Safety**: Built with thread safety in mind for concurrent applications
- **Execution Context Integration**: Provides control over which thread subscribers are notified on
- **Value Caching**: Signals maintain their current value for new subscribers
- **Lazy Evaluation**: Many operations are lazily evaluated to optimize performance
- **Error Handling**: Comprehensive error handling and recovery mechanisms

## Invariants

- **Thread Safety**: All operations must be thread-safe
- **Value Consistency**: Subscribers must receive consistent values even with concurrent updates
- **Order Preservation**: Events must be delivered to subscribers in the order they were published
- **Memory Safety**: Subscriptions must be properly managed to prevent memory leaks
- **Lifecycle Management**: Resources must be properly released when signals are no longer needed

## Modules

The Signals3 library is organized into several key modules:

1. **Core Signal Module**: Contains the main Signal class and its subclasses
2. **Stream Module**: Contains the basic EventStream functionality
3. **Threading Module**: Provides execution context management
4. **UI Module**: Provides integration with UI frameworks (Android, JavaFX)

## Out of scope

- Direct integration with specific UI frameworks (provided through extension points)
- Distributed event streaming across multiple machines
- Persistence of signal values
- Built-in serialization of signal values
