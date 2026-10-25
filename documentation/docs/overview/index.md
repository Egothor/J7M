# Product overview

J7M is Java 25 distributed-object middleware with an application hosting platform
and opt-in distributed resource services. Its core abstraction is a concrete
`ObjectRef`: a live object identity with interfaces, location information,
generation and lease metadata. Applications obtain Java proxies, pass callbacks
and return further references without defining a separate static endpoint for
every object.

## Project lineage

J7M follows **j5m**, the earlier middleware built for a high-capacity crawler and
a virtual army. It continues that foundation as a platform for new generations
of related projects delivered as distributed applications. See
[project history](../about/index.md#from-j5m-to-j7m).

Use this section to decide which layers your application needs. A two-JVM
service does not automatically need durable queues or a replicated state
machine. A durable workflow needs more than a successful remote method call.

| Question | Read |
| --- | --- |
| Where do applications, Nuggets and resources run? | [Architecture](architecture.md) |
| Which modules should I understand first? | [Modules](modules.md) |
| What guarantees do the features provide? | [Capabilities and boundaries](capabilities.md) |
| Is J7M appropriate for this application? | [Evaluation checklist](evaluation.md) |
| What do the middleware terms mean? | [Terminology](terminology.md) |

The [getting-started guide](../getting-started/index.md) introduces working code.
The [configuration guide](../configuration/index.md) explains how a host supplies
discovery, transports, security and durable resources.
