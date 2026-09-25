# TODO

## Open

### Actor system & references
- [ ] The ability to register actors from another actor system
- [ ] Check if subclasses of `Msg` work, or do we need some type parameter magic with `<:` and `:`
- [ ] Make use of `into` and `Conversion[From, To]` to enable conversions between messages
- [ ] Make use of `ClassTag` in `ActorRef` (see the inject project) so that the actor refs' type parameters are not lost in collections (or: [type tests](https://docs.scala-lang.org/scala3/reference/other-new-features/type-test.html))
- [ ] Actors should carry tags (strings), and the actor system can get requests to connect an actor with any other actor that has a given tag

### Lifecycle, observability & configuration
- [ ] `HealthCheck` system message, sent from the parent to the child; if the child doesn't respond in time, the message is repeated, and then the child is closed
- [ ] Consider allowing the children to use different types of messages; and then: clusters? persistence?
- [ ] Maybe think about plugging in a logging functionality so that an unprocessed message can be logged as a warning
- [ ] Similarly, about metrics
- [ ] And about the max number of messages processed per heartbeat
- [ ] Make constants configurable through environment variables

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
- [x] Change the name of `finalBehavior` to `finalBehavior` (the last behavior); the current one is confusing
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
