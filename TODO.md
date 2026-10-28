# TODO

## Open

### To do
- [ ] Create a util package object for common methods in actors unit tests (spawn, close, etc)
- [ ] Merge heartbeat strategies Agitated and Reactive into a smarter Agitated
- [ ] LogActor as a special subclass of ActorImpl
- [ ] When an actor receives a reference of LogActor, it starts to gather logging and debugging data (not before)
- [ ] PersistenceActor as a special subclass of ActorImpl
- [ ] When an actor receives a reference of PersistenceActor, it sends its state to the PersistenceActor at Close
- [ ] `HealthCheck` system message, sent from the parent to the child; if the child doesn't respond in time, the message is repeated, and then the child is closed
- [ ] The `Restart` system message and logic
- [ ] The ability to register actors from another actor system
- [ ] Make constants configurable through environment variables
- [ ] ReadWriteUpdate rewritten in signals3 with actors
- [ ] ANN rewritten in signals3 with actors

## Done

- [x] `Pausable`
- [x] Pausing and closing through special messages
- [x] `private var state: State` for keeping and modifying internal state
- [x] Behaviors must have access to this actor to be able to mutate the state
- [x] Heartbeat should be a strategy: `Linear(ms)`, `Agitated(min, coeff, max)`, `Reactive`
- [x] Scaladoc
- [x] Unit tests
- [x] Managing behaviors through messages
- [x] Divide the `Actor` class into an immutable trait used outside and a mutable class that extends it - the behaviors use the latter
- [x] Add the out stream that can be used by behaviors to send messages to
- [x] Change the behaviors list to a map - all behaviors that fit for a given message are executed, not only the oldest one
- [x] Change the name of `currentBehavior` to `finalBehavior` (the last behavior); the current one is confusing
- [x] Change the behaviors back to a list xD
- [x] A way to request that a given message is handled by a behavior with the given id
- [x] Similarly, there should be an `onClose` function (but that's already implemented)
- [x] `onInit` function that the actor can use, for example, to send out messages that it's alive
- [x] Remove `finalBehavior`; unprocessed messages are ignored
- [x] Serial actors can have fewer safe-guards (and in fact they should have)
- [x] `ActorBuilder`
- [x] Spawn sub-actors
- [x] Close sub-actors when the parent is closed
- [x] `ActorSystem` where you can register new actors with unique ids
- [x] `ActorRef` (local) retrieved from `ActorSystem`, used to send messages to other actors
- [x] `RemoteActorRef` should carry the `ActorSystem` id too, to enable communication between different actor systems
- [x] Check if subclasses of `Msg` work, or do we need some type parameter magic with `<:` and `:`
- [x] Make use of `into` and `Conversion[From, To]` to enable conversions between messages
- [x] Make use of `ClassTag` in `ActorRef` (see the inject project) so that the actor refs' type parameters are not lost in collections (or: [type tests](https://docs.scala-lang.org/scala3/reference/other-new-features/type-test.html))
- [x] System messages carried through actor systems
- [x] Actors should carry tags (strings), and the actor system can get requests to connect an actor with any other actor that has a given tag

