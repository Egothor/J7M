# Network Addresses and Persistent Storage

Every advertised endpoint must be usable from the process that consumes it. A listener bound to `0.0.0.0` has not advertised a routable hostname. A `localhost` address in a remote reference points to the consuming host's loopback interface, not the exporting host.

The supplied gRPC/RMI invocation providers currently advertise `127.0.0.1` and have no public bind/advertised-host setting. Use their examples on one host. Multi-host application invocation requires a real provider/host integration that supplies routable endpoints; no discovery option rewrites those references. The explicit binding support of bootstrap and native resource services is a separate capability.

Inventory discovery, invocation, callback, bootstrap, artifact, consensus and resource-service endpoints separately. Associate each with its protocol, TLS identity, client authorization, bind address and advertised address. Restrict network reachability according to that inventory.

| Path | Configuration detail |
| --- | --- |
| UDP multicast discovery | Group, UDP port, selected interface and hop limit; actual network forwarding |
| Unicast bootstrap | `bootstrap+tls` seed URI, server SAN, client certificate and allowed context |
| Invocation | Concrete transport endpoint and service authentication policy |
| Consensus | Authorized peer roster, group identity, peer key and mutual TLS outside loopback |
| Resource service | Server-bound principal/scope and application versus maintenance/admin permissions |
| Artifact transfer | Tenant/capabilities, signed manifest and secure endpoint |

## Durable directories

Give each live state owner its own private directory. Do not share one consensus directory between processes or clone a live directory to manufacture another voter. Storage locks and generation checks are correctness controls, not obstacles to work around.

The durable network services depend on filesystem behavior such as atomic rename, fsync and private paths. Unsupported operations fail explicitly. Qualify the chosen filesystem and volume semantics instead of assuming every shared or object-backed mount behaves like a local disk.

Budget for the write-ahead log, retained results and identities, snapshots, staging images, backup publication and restore headroom. Logical payload quotas do not measure every allocation block used by the filesystem. Monitor free space and I/O latency as well as application-level counters.

Place secrets outside artifact and state directories where a read-only application can expose them. Mount application releases read-only when practical, and scope write permissions to each host-owned state root.

For restoration, follow the [fresh-generation recovery procedure](../operations/recovery.md), including fencing the old routing generation before bringing restored state into service.
