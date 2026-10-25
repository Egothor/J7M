# Configuration Reference

There is no single flat set of configuration keys covering the whole middleware. The following are actual configuration entry points.

| Entry point | Owner | Principal settings |
| --- | --- | --- |
| `MiddlewareRuntimeBuilder` | Runtime host | Identity/context, registry, codec, clock, security and transports |
| `NuggetAdminRuntimeConfiguration.Builder` | Administrative host | Name, security, protected PIN source, discovery/introspection integration |
| `MulticastRegistryOptions` | Discovery host | Registry/context, UDP group/port/interface, deadlines, gather window, TTL and query limits |
| `BootstrapRegistryOptions` | Discovering process | Seed endpoints, total deadline, depth/contact/candidate bounds, cache TTL, resolver |
| `NetworkBootstrapEndpointResolver` | Discovering process | TLS context and per-operation budget |
| `BootstrapRegistryServer` | Registry host | Bind address, TLS context, borrowed local registry, allowed contexts, request budget |
| `ApplicationManifest` | Application release | Identity, Java/module requirements, bootstrap, services and security/resource declarations |
| `ActivationOptions` | Deployment host | Strict verification and safe activation-planning configuration |
| `LiveConfiguration` | Application host | Versioned complete snapshots and typed bindings/source attachment |
| `ManagedHostBindings` | Trusted application host | Named policies, resource factories and authorized installed capabilities |
| `NetworkConsensusConfig` | Durable group host | Node/group, authorized peers/voters, private storage, key, timing, bounds and TLS |
| `PartitionServer.Binding` | Resource host | Immutable principal scope, distinct key and admin/maintenance permission |
| `ApplicationPlatformLimits` | Artifact/repository host | Bundle/metadata/expansion bounds and bounded repository work |

## Precedence and updates

Use the owning API's precedence rules. Annotation metadata selects or constrains a capability; host policy remains authoritative. A source configuration update is not an instruction to weaken security or replace a durable operation identity.

Live configuration sources publish complete snapshots with a monotonically increasing version. A missing key removes it in that snapshot. Conversion/validation must succeed for the whole refreshing set before publication. Use one immutable properties record for values that must be observed together.

A manifest is parsed data. Parsing and planning must not execute application code. Activation subsequently validates actual generated metadata, class/module origin and required host capabilities. See [manifest and policy reference](manifests-policies.md).

## Secrets

Use protected providers/files for TLS identity, native authentication material and administrative admission. Names, references and safe configuration summaries are not secret containers. A safe status view deliberately omits private material; it is not an exportable copy of the full host configuration.

For worked settings, see [discovery](../configuration/discovery.md), [TLS](../security/tls-and-certificates.md) and [distributed resource configuration](../configuration/distributed-resources.md).
