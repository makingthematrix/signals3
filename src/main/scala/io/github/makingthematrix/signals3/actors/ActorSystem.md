# Actor System & References Implementation Proposal

**Vibe Session ID:** `fcbf1297-ni0lsm34`  
**Date:** 2026-09-10  
**Status:** Proposal (Awaiting Review)

---

## Overview

This document proposes a comprehensive design for implementing an **Actor System** with **Actor References** for the signals3 library. The design supports both local (single JVM) and distributed (multi-server) scenarios while maintaining full backward compatibility with the existing Actor implementation.

---

## Table of Contents

1. [Core Design Principles](#1-core-design-principles)
2. [Actor Reference (ActorRef) Design](#2-actor-reference-actorref-design)
3. [Actor System Design](#3-actor-system-design)
4. [Remote Actor System](#4-remote-actor-system)
5. [Integration with Existing Actor API](#5-integration-with-existing-actor-api)
6. [Usage Examples](#6-usage-examples)
7. [Message Serialization](#7-message-serialization)
8. [Error Handling and Resilience](#8-error-handling-and-resilience)
9. [Clustering Support (Optional/Advanced)](#9-clustering-support-optionaladvanced)
10. [Configuration](#10-configuration)
11. [Implementation Considerations](#11-implementation-considerations)
12. [Migration Path](#12-migration-path)

---

## 1. Core Design Principles

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

## 2. Actor Reference (ActorRef) Design

### Type Hierarchy

```scala
// Base trait for all actor references
trait ActorRef {
  def path: ActorPath
  def ! (msg: Any): Unit
  def ? (msg: Any): CloseableFuture[Any]
  def isLocal: Boolean
  def isValid: Boolean
}

// Typed actor reference for type-safe messaging
trait ActorRef[Msg, Rsp] extends ActorRef {
  def ! (msg: Msg): Unit
  def ? (msg: Msg): CloseableFuture[Rsp]
  override def ! (msg: Any): Unit = this ! msg.asInstanceOf[Msg]
  override def ? (msg: Any): CloseableFuture[Any] = this ? msg.asInstanceOf[Msg]
}

// Local actor reference - direct to Actor instance
final class LocalActorRef[Msg, Rsp, State] private[actors] (
  private val actor: Actor[Msg, Rsp, State]
) extends ActorRef[Msg, Rsp] {
  override def path: ActorPath = ActorPath.Local(actorId)
  override def ! (msg: Msg): Unit = actor ! msg
  override def ? (msg: Msg): CloseableFuture[Rsp] = actor ? msg
  override def isLocal: Boolean = true
  override def isValid: Boolean = !actor.isClosed
}

// Remote actor reference - proxies to remote actor
final class RemoteActorRef[Msg, Rsp] private[actors] (
  val path: ActorPath.Remote,
  private val transport: ActorTransport
) extends ActorRef[Msg, Rsp] {
  override def ! (msg: Msg): Unit = transport.send(path, msg)
  override def ? (msg: Msg): CloseableFuture[Rsp] = transport.sendWithResponse(path, msg)
  override def isLocal: Boolean = false
  override def isValid: Boolean = true // Remote validity is connection-dependent
}
```

### Actor Path (Unique Identifier)

```scala
sealed trait ActorPath {
  def asString: String
  def name: String
  def systemName: String
}

object ActorPath {
  // Local actor within the same JVM
  case class Local(actorId: String) extends ActorPath {
    def asString: String = s"local://$actorId"
    def name: String = actorId
    def systemName: String = "local"
  }

  // Remote actor in a different JVM/process
  case class Remote(systemName: String, host: String, port: Int, actorId: String) extends ActorPath {
    def asString: String = s"$systemName://$host:$port/$actorId"
    def name: String = actorId
  }

  // For parsing paths
  def parse(path: String): ActorPath = {
    val LocalPattern = """local://(.+)""".r
    val RemotePattern = """([^/:]+)://([^:]+):(\d+)/(.+)""".r

    path match {
      case LocalPattern(actorId) => Local(actorId)
      case RemotePattern(system, host, port, id) => Remote(system, host, port.toInt, id)
      case _ => throw new IllegalArgumentException(s"Invalid actor path: $path")
    }
  }
}
```

---

## 3. Actor System Design

### Core Traits

```scala
trait ActorSystem {
  def name: String
  def host: String
  def port: Int

  // Actor registration and lookup
  def register[Msg, Rsp, State](name: String, actor: Actor[Msg, Rsp, State]): ActorRef[Msg, Rsp]
  def get[Msg, Rsp](path: ActorPath): Option[ActorRef[Msg, Rsp]]
  def get[Msg, Rsp](name: String): Option[ActorRef[Msg, Rsp]]
  def getUnsafe[Msg, Rsp](path: ActorPath): ActorRef[Msg, Rsp]

  // Actor creation shortcuts
  def actorOf[Msg, Rsp, State](name: String, state: State, behavior: Actor.PF[Msg, Rsp, State])
                             (using ec: ExecutionContext): ActorRef[Msg, Rsp]

  def actorOf[Msg, Rsp, State](name: String, state: State, behavior: Actor.Beh[Msg, Rsp, State])
                             (using ec: ExecutionContext): ActorRef[Msg, Rsp]

  // System lifecycle
  def shutdown(): CloseableFuture[Unit]
  def isShutdown: Boolean

  // Cluster support (future)
  def cluster: Option[ActorCluster]
}
```

### Local-only Implementation

```scala
final class LocalActorSystem(val name: String = "default") extends ActorSystem {
  private val actors = new ConcurrentHashMap[String, AnyRef]()
  private val isShutdownFlag = new AtomicBoolean(false)

  override def host: String = "localhost"
  override def port: Int = 0
  override def cluster: Option[ActorCluster] = None

  override def register[Msg, Rsp, State](name: String, actor: Actor[Msg, Rsp, State]): ActorRef[Msg, Rsp] = {
    require(!isShutdownFlag.get(), "Cannot register actors on shutdown system")
    val path = ActorPath.Local(name)
    actors.put(name, LocalActorRef(actor).asInstanceOf[AnyRef])
    LocalActorRef(actor)
  }

  override def get[Msg, Rsp](path: ActorPath): Option[ActorRef[Msg, Rsp]] = path match {
    case ActorPath.Local(name) => get[Msg, Rsp](name)
    case _ => None // Remote paths not supported in local system
  }

  override def get[Msg, Rsp](name: String): Option[ActorRef[Msg, Rsp]] = {
    actors.get(name).map(_.asInstanceOf[ActorRef[Msg, Rsp]])
  }

  override def getUnsafe[Msg, Rsp](path: ActorPath): ActorRef[Msg, Rsp] = {
    get[Msg, Rsp](path).getOrElse(throw new IllegalArgumentException(s"Actor not found: $path"))
  }

  override def actorOf[Msg, Rsp, State](name: String, state: State, behavior: Actor.PF[Msg, Rsp, State])
                                    (using ec: ExecutionContext): ActorRef[Msg, Rsp] = {
    val actor = Actor(state, behavior)(using ec)
    register(name, actor)
  }

  override def actorOf[Msg, Rsp, State](name: String, state: State, behavior: Actor.Beh[Msg, Rsp, State])
                                    (using ec: ExecutionContext): ActorRef[Msg, Rsp] = {
    val actor = Actor(state, behavior)(using ec)
    register(name, actor)
  }

  override def shutdown(): CloseableFuture[Unit] = {
    if (isShutdownFlag.compareAndSet(false, true)) {
      // Close all registered actors
      val closeFutures = actors.values().iterator().asScala.map {
        case ref: LocalActorRef[_, _, _] => ref.actor.close()
        case _ => ()
      }
      CloseableFuture.sequence(closeFutures.toSeq).map(_ => ())
    } else {
      CloseableFuture.successful(())
    }
  }

  override def isShutdown: Boolean = isShutdownFlag.get()
}
```

---

## 4. Remote Actor System

```scala
final class RemoteActorSystem(
  val name: String,
  val host: String,
  val port: Int,
  transport: ActorTransport
) extends ActorSystem {
  private val localSystem = new LocalActorSystem(name)
  private val remoteConnections = new ConcurrentHashMap[String, ActorTransport]()
  private val isShutdownFlag = new AtomicBoolean(false)

  override def cluster: Option[ActorCluster] = None // Could be Some(cluster) for clustering support

  override def register[Msg, Rsp, State](name: String, actor: Actor[Msg, Rsp, State]): ActorRef[Msg, Rsp] = {
    localSystem.register(name, actor)
  }

  override def get[Msg, Rsp](path: ActorPath): Option[ActorRef[Msg, Rsp]] = path match {
    case p: ActorPath.Local => localSystem.get[Msg, Rsp](p)
    case p: ActorPath.Remote =>
      // Check if we have a connection to this remote system
      val key = s"${p.systemName}://${p.host}:${p.port}"
      if (remoteConnections.containsKey(key)) {
        Some(new RemoteActorRef[Msg, Rsp](p, remoteConnections.get(key)))
      } else {
        // Attempt to connect
        transport.connect(key).value match {
          case Some(Success(_)) =>
            remoteConnections.put(key, transport)
            Some(new RemoteActorRef[Msg, Rsp](p, transport))
          case _ => None
        }
      }
    case _ => None
  }

  // ... other methods delegate to localSystem or handle remote
}
```

---

## 5. Integration with Existing Actor API

### Extending Actor with System Awareness

```scala
trait Actor[Msg, Rsp, State] {
  // Existing methods...

  // New methods for actor system integration
  def system: Option[ActorSystem] = None

  def ref: ActorRef[Msg, Rsp] = system match {
    case Some(sys) => sys.getUnsafe[Msg, Rsp](ActorPath.Local(this.toString))
    case None => LocalActorRef(this)
  }
}

// Extended ActorImpl with system support
final private[actors] class ActorImpl[Msg, Rsp, State](
  private var _state: State,
  override val heartbeat: HeartBeatStrategy = Actor.defBeat,
  private val _system: Option[ActorSystem] = None
)(using ec: ExecutionContext) extends MutableActor[Msg, Rsp, State] with Closeable with Pausable {
  // Existing implementation...

  override def system: Option[ActorSystem] = _system

  // When closed, also unregister from system if registered
  override def closeAndCheck(): Boolean = {
    _system.foreach { sys =>
      // Find and remove this actor from the system
      // (implementation would need to track registered actors)
    }
    super.closeAndCheck()
  }
}
```

### Factory Methods with System

```scala
object Actor {
  // Existing methods...

  // New factory methods with actor system
  inline def apply[Msg, Rsp, State](
    name: String,
    state: State,
    behavior: PF[Msg, Rsp, State],
    system: ActorSystem
  )(using ExecutionContext): ActorRef[Msg, Rsp] = {
    val actor = new ActorImpl(state, defBeat, Some(system))(using ec).tap { a =>
      a.addBehavior("default" -> behavior)
      a.initialize()
    }
    system.register(name, actor)
  }

  // Builder integration
  def builder[Msg, Rsp, State](system: ActorSystem): ActorBuilderWithSystem[Msg, Rsp, State] =
    new ActorBuilderWithSystem(system)
}

// Extended builder with system support
final class ActorBuilderWithSystem[Msg, Rsp, State](
  system: ActorSystem,
  state: State = null.asInstanceOf[State],
  behaviors: List[Actor.Beh[Msg, Rsp, State]] = Nil,
  heartbeat: Actor.HeartBeatStrategy = Actor.defBeat,
  onInit: Option[MutableActor[Msg, Rsp, State] => Unit] = None,
  useSerialDispatch: Boolean = false,
  name: Option[String] = None
) {
  // Similar to ActorBuilder but builds with system registration

  def withName(name: String): ActorBuilderWithSystem[Msg, Rsp, State] = {
    this.copy(name = Some(name))
  }

  def build()(using ec: ExecutionContext): ActorRef[Msg, Rsp, State] = {
    require(name.isDefined, "Actor name is required for system registration")
    val actor = if (useSerialDispatch) buildSerial() else buildParallel(ec)
    system.register(name.get, actor)
  }
}
```

---

## 6. Usage Examples

### Basic Local System Usage

```scala
// Create a local actor system
val system = LocalActorSystem("myApp")

// Create actors through the system
val counterRef: ActorRef[Int, String] = system.actorOf(
  name = "counter",
  state = 0,
  behavior = { case (msg: Int, actor) =>
    actor.state += msg
    Some(s"Count: ${actor.state}")
  }
)

// Send messages
counterRef ! 5
val response: CloseableFuture[String] = counterRef ? 3

// Lookup actors
val retrieved: ActorRef[Int, String] = system.getUnsafe("counter")
retrieved ! 10

// Shutdown system (closes all actors)
system.shutdown()
```

### Remote Communication

```scala
// Server side
val serverSystem = RemoteActorSystem(
  name = "paymentService",
  host = "0.0.0.0",
  port = 2552,
  transport = ActorTransport.tcp
)

// Create a payment processor actor
serverSystem.actorOf(
  name = "processor",
  state = Map.empty[String, Double],
  behavior = { case (msg: PaymentRequest, actor) =>
    // Process payment
    Some(PaymentResponse(true))
  }
)

// Client side
val clientSystem = RemoteActorSystem(
  name = "clientApp",
  host = "localhost",
  port = 0, // No server mode
  transport = ActorTransport.tcp
)

// Get reference to remote actor
val processorRef = clientSystem.getUnsafe[PaymentRequest, PaymentResponse](
  ActorPath.Remote("paymentService", "payment-server.example.com", 2552, "processor")
)

// Use it transparently
val response: CloseableFuture[PaymentResponse] = processorRef ? PaymentRequest(100.0)
```

### Mixed Local and Remote

```scala
val system = RemoteActorSystem("analytics", "0.0.0.0", 8080)

// Create local actors
val collectorRef = system.actorOf("collector", state, collectorBehavior)
val aggregatorRef = system.actorOf("aggregator", state, aggregatorBehavior)

// Get reference to remote service
val dbRef = system.getUnsafe[Query, Result](
  ActorPath.Remote("database", "db.example.com", 5432, "queryService")
)

// Local actor can send messages to remote actor
collectorRef ! StartCollection(dbRef)
```

---

## 7. Message Serialization

```scala
trait MessageSerializer {
  def serialize(msg: Any): Array[Byte]
  def deserialize(bytes: Array[Byte]): Any
  def contentType: String
}

// JSON serializer using circe
final class JsonSerializer extends MessageSerializer {
  import io.circe._
  import io.circe.syntax._
  import io.circe.parser._

  override def serialize(msg: Any): Array[Byte] = {
    msg.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
  }

  override def deserialize(bytes: Array[Byte]): Any = {
    val json = new String(bytes, StandardCharsets.UTF_8)
    decode[Any](json).toTry.get
  }

  override def contentType: String = "application/json"
}

// Binary serializer using Java serialization
final class JavaSerializer extends MessageSerializer {
  override def serialize(msg: Any): Array[Byte] = {
    val bos = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(bos)
    oos.writeObject(msg)
    oos.close()
    bos.toByteArray
  }

  override def deserialize(bytes: Array[Byte]): Any = {
    val bis = new ByteArrayInputStream(bytes)
    val ois = new ObjectInputStream(bis)
    val obj = ois.readObject()
    ois.close()
    obj
  }

  override def contentType: String = "application/x-java-serialized-object"
}
```

---

## 8. Error Handling and Resilience

```scala
sealed trait ActorSystemError extends Exception

object ActorSystemError {
  case class ActorNotFound(path: ActorPath) extends ActorSystemError
  case class SystemShutdown(system: String) extends ActorSystemError
  case class ConnectionFailed(path: ActorPath, cause: Throwable) extends ActorSystemError
  case class SerializationError(msg: Any, cause: Throwable) extends ActorSystemError
  case class Timeout(path: ActorPath) extends ActorSystemError
}

// Enhanced ActorRef with better error handling
trait ActorRef[Msg, Rsp] {
  def ! (msg: Msg): Unit
  def ? (msg: Msg): CloseableFuture[Rsp]
  def ? (msg: Msg, timeout: FiniteDuration): CloseableFuture[Rsp]
  def isAvailable: Signal[Boolean]
  def onFailure: Signal[ActorSystemError]
}
```

---

## 9. Clustering Support (Optional/Advanced)

```scala
trait ActorCluster {
  def join(seedNodes: Seq[String]): CloseableFuture[Unit]
  def leave(): CloseableFuture[Unit]
  def members: Signal[Set[ClusterMember]]
  def memberStatus: Signal[ClusterStatus]

  // Cluster-aware actor discovery
  def select(path: String): ActorSelection
}

case class ClusterMember(
  address: String,
  host: String,
  port: Int,
  status: ClusterMemberStatus
)

sealed trait ClusterMemberStatus
object ClusterMemberStatus {
  case object Joining extends ClusterMemberStatus
  case object Up extends ClusterMemberStatus
  case object Leaving extends ClusterMemberStatus
  case object Exiting extends ClusterMemberStatus
  case object Down extends ClusterMemberStatus
  case object Removed extends ClusterMemberStatus
}

sealed trait ClusterStatus
object ClusterStatus {
  case object Joining extends ClusterStatus
  case object Active extends ClusterStatus
  case object Leaving extends ClusterStatus
  case object Exiting extends ClusterStatus
}

// Actor selection for cluster-wide messaging
trait ActorSelection {
  def ! (msg: Any): Unit
  def ? (msg: Any): CloseableFuture[Any]
  def tell(msg: Any): Unit
  def ask(msg: Any): CloseableFuture[Any]
}
```

---

## 10. Configuration

```scala
case class ActorSystemConfig(
  name: String = "default",
  host: String = "localhost",
  port: Int = 0,
  transport: TransportConfig = TransportConfig.Tcp,
  serialization: SerializationConfig = SerializationConfig.Json,
  heartbeat: HeartBeatStrategy = Actor.defBeat,
  cluster: Option[ClusterConfig] = None
)

sealed trait TransportConfig
object TransportConfig {
  case object Tcp extends TransportConfig
  case object Http extends TransportConfig
  case class Custom(name: String, factory: () => ActorTransport) extends TransportConfig
}

sealed trait SerializationConfig
object SerializationConfig {
  case object Json extends SerializationConfig
  case object Java extends SerializationConfig
  case object Protobuf extends SerializationConfig
  case class Custom(name: String, factory: () => MessageSerializer) extends SerializationConfig
}

case class ClusterConfig(
  seedNodes: Seq[String] = Nil,
  gossipInterval: FiniteDuration = 1.second,
  failureDetection: FiniteDuration = 10.seconds
)

object ActorSystem {
  def apply(config: ActorSystemConfig): ActorSystem = {
    config.transport match {
      case TransportConfig.Tcp =>
        new RemoteActorSystem(
          config.name,
          config.host,
          config.port,
          ActorTransport.tcp
        )
      case TransportConfig.Http =>
        new RemoteActorSystem(
          config.name,
          config.host,
          config.port,
          ActorTransport.http
        )
      case TransportConfig.Custom(_, factory) =>
        new RemoteActorSystem(config.name, config.host, config.port, factory())
    }
  }

  def local(name: String = "default"): ActorSystem = new LocalActorSystem(name)
}
```

---

## 11. Implementation Considerations

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

## 12. Migration Path

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
