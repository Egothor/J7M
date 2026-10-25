# Modules and dependencies

Start with the application responsibility, then select the modules that implement
it. The table describes the repository modules; it does not claim they are all
required dependencies of every application.

| Module | Responsibility |
| --- | --- |
| `j7m-api` | Public runtime, transport, registry, application contracts and annotations |
| `j7m-kernel` | Invocation, dispatch, lifetime and policy enforcement machinery |
| `j7m-runtime` | Runtime assembly, embedded/multicast/bootstrap registries and host operations |
| `j7m-transport-grpc` | Modern network transport implementation |
| `j7m-transport-rmi` | RMI transport implementation under the same middleware API |
| `j7m-application-platform` | Artifacts, repositories, verification, deployment and managed hosting |
| `j7m-annotation-processor` | Generated component definitions, schemas and adapters |
| `j7m-application-distributed` | Managed application bindings for distributed resource features |
| `j7m-durable-log`, `j7m-consensus` | Durable storage and consensus building blocks |
| `j7m-distributed-core` | Replicated resources, ownership, queues, events, jobs, sagas and schedules |
| `j7m-integrations` | JDBC delivery integration and observability exporters |
| `j7m-messaging`, `j7m-coordination`, `j7m-saga` | Additional messaging, coordination and saga facilities; consult their API contracts before combining profiles |
| `j7m-isolation` | Isolation capability support; not a general-purpose hostile-code sandbox |
| `j7m-cluster-management` | Authenticated, fenced application rollout and canary facilities |
| `j7m-deployer-cli`, `j7m-gradle-plugin` | Deployment and build integration |
| `j7m-adapter-spi`, `j7m-adapter-tck`, `j7m-testkit` | Extension contracts and verification support |

The repository `settings.gradle` defines the authoritative module inventory.
Java applications use the ordinary API types; the host selects the implementation
modules. Generated application code must see the processor and its supported API
types during compilation. See [schemas and JPMS](../programming/schemas-jpms.md).
The [JPMS dependency map](../programming/module-dependencies.md) lists current
module names, direct dependencies and service-loading boundaries.

[Build J7M](../getting-started/build.md) ·
[Transport configuration](../configuration/transports.md)
