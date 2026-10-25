# Deployment Topologies

Choose a topology by the failures that must be isolated. Separate JVMs isolate Java process failure; separate hosts or storage devices isolate additional failures. Three voter JVMs on one machine demonstrate a network protocol but do not tolerate loss of that machine.

The current built-in gRPC/RMI invocation providers advertise loopback endpoints. Their directly runnable multi-JVM topology is same-host. A multi-host application topology needs an actual host integration/custom invocation provider with routable endpoints; discovery configuration alone does not supply one. Native resource/consensus and bootstrap endpoints have separate explicit bind/TLS APIs.

| Topology | Appropriate purpose | Boundary to keep explicit |
| --- | --- | --- |
| Embedded runtime | A Java process exposes or consumes distributed services | The enclosing process owns startup and shutdown |
| Separate application runtimes | Independent workers, service instances or frontend/backend processes | References and discovery must contain reachable endpoints |
| Dedicated registry seeds | Bootstrap discovery across routed networks | Seed availability is distinct from service and quorum availability |
| Dedicated artifact repository | Verified application distribution and resumable transfer | Publication and application activation are different operations |
| Dedicated resource groups | Durable queues, partitions, jobs or saga state | Consensus identity, electorate, authorization and private storage |

For a replicated service group of three voters, a majority is two; for five voters, it is three. These arithmetic facts do not establish an availability SLA. A partitioned minority must not continue pretending it can commit protected state. Test the configured group and its actual storage/network failure domains.

Place a service instance near the resource it frequently accesses when that reduces latency, but keep locality a selection preference unless the explicit ownership contract requires more. `@PreferLocal` or a shard key does not create data replication or guarantee a single global ordering across unrelated partitions.

## Plan a topology

Record the host/JVM inventory, application instance identities, registry contexts, seed addresses, invocation endpoints, resource group identities and durable paths. Define which credentials allow application access, maintenance and administration. Then draw both the happy path and the recovery path.

A network policy must permit the actual paths: client-to-service invocation, callback invocation when used, discovery questions/responses or bootstrap TLS, consensus peer traffic, resource-client traffic and artifact transfer. A single open management port is not a complete middleware network plan.

See [network and storage](network-storage.md) and [distributed resource configuration](../configuration/distributed-resources.md).
