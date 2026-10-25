# Discovering Instances Across JVMs

A Java reference in one process is not an address directory for another process. J7M uses a `RegistryClient` to find `RegistryRecord` values containing service metadata and remote `ObjectRef` information. The configured transport subsequently carries the invocation. Discovery and invocation are separate paths.

## Choose the discovery mechanism

| Mechanism | What it knows | Suitable use |
| --- | --- | --- |
| Embedded registry | Registrations in its own store | One runtime or explicitly shared local registry ownership |
| UDP multicast | Local records plus answers from other registry listeners | Networks that explicitly support the configured multicast group and interface |
| Unicast bootstrap over mutual TLS | Known seed registries, then bounded peer hints | Multiple hosts, routed networks and environments without multicast |

The default runtime builder uses the embedded variant. Starting another JVM alone does not connect their registries. No central index is required by the protocol: several reachable registries can serve as seeds. A seed is a starting address, not a consensus leader.

## UDP multicast

Each multicast client maintains its local registry. A lookup/list sends a bounded question; listeners return matching local records. The querying client gathers replies within its deadline and applies the received-record trust policy. This is query/response discovery, not replication of one authoritative global directory.

This host fragment chooses the interface explicitly. `store`, `clock`, `verifier` and `auditSink` are host-supplied objects; the strict-trust constructor keeps remote records subject to verification:

```java
MulticastRegistryOptions options = new MulticastRegistryOptions(
        "orders-registry-1", "orders", "239.7.0.1", 47070,
        NetworkInterface.getByName("eth0"),
        Duration.ofMillis(500), Duration.ofMillis(25), Duration.ofSeconds(2),
        64, 32, 1, 64 * 1024, 60 * 1024, new UdpMulticastBus());

RegistryClient registry = new MulticastRegistryClient(
        store, clock, options, verifier, true, auditSink);
```

Use the actual interface name of the host. Allow the selected UDP group/port in the network policy and ensure the network permits multicast. Container bridges, cloud networks and routers may not forward it. TTL is a hop limit; increasing it does not establish a multicast route or authorize a trust domain.

The built-in defaults are group `239.7.0.1`, port `47070`, query deadline 500 ms, gather-after-first-response 25 ms, question TTL 2 seconds, 64 results, 32 peers, hop limit 1, requested receive buffer 64 KiB and accepted message size 60 KiB. Override those values deliberately rather than relying on defaults across unrelated deployments.

## Unicast bootstrap

Install a `BootstrapRegistryServer` beside an authoritative local registry. The endpoint is read-only: it supports lookup, list and peer reads, with no remote bind, unbind or heartbeat operation. It requires mutual TLS and a nonempty allowlist of registry contexts.

```java
BootstrapRegistryServer server = new BootstrapRegistryServer(
        new InetSocketAddress("0.0.0.0", 4117),
        serverTls, localRegistry, Set.of("orders"), Duration.ofSeconds(2));
```

The wildcard address is a bind address, not an advertised endpoint. Publish a reachable DNS name or IP address in a peer's `RegistryEndpoint`, such as `bootstrap+tls://registry-a.internal:4117`. Configure the certificate SAN for that original name or IP.

In a discovering process, select the seeds, bounds and verifier explicitly:

```java
NetworkBootstrapEndpointResolver resolver =
        new NetworkBootstrapEndpointResolver(clientTls, Duration.ofMillis(750));

BootstrapRegistryClient discovery = new BootstrapRegistryClient(
        localRegistry, Clock.systemUTC(),
        new BootstrapRegistryOptions(
                "orders-client", "orders",
                List.of("bootstrap+tls://registry-a.internal:4117",
                        "bootstrap+tls://registry-b.internal:4117"),
                Duration.ofSeconds(2), 3, 16, 64,
                Duration.ofSeconds(1), resolver),
        verifier, true, auditSink);
```

`serverTls` and `clientTls` are standard JDK `SSLContext` instances with the correct identity and dedicated trust material. Follow [TLS configuration](../security/tls-and-certificates.md). Configure more than one seed when seed availability matters. Bootstrap traversal observes maximum depth, contacted-peer count, candidate count, cache TTL and one overall deadline. Failed seeds consume that budget; they do not start a new overall timeout.

The network resolver supports configured DNS names and IPv4/IPv6 literals. A connection uses the first DNS address returned; separate seed URIs provide endpoint failover. Four bounded DNS workers prevent an unresponsive OS/JDK lookup from creating unbounded threads. There are at most 64 active client calls. Server limits include 16 concurrent requests without a worker queue, 128 new connections per one-second window, and a 1 ms–5 s configured request budget.

## Trust and ownership

TLS authenticates the connection. `RegistryRecordVerifier` independently verifies publisher records. Context matching, leases, generation and replay checks remain active. Receiving a record from a trusted TLS server does not automatically make its publisher trusted. A peer hint is a route, not authority.

The server borrows its registry; closing the server does not close that registry. `BootstrapRegistryClient` owns its local registry. The resolver has its own lifecycle and must also be closed. The low-level facade returned directly by the resolver preserves signature fields but does not itself perform publisher-signature verification; normal applications should use it through `BootstrapRegistryClient`.

## Refresh and failure detection

Managed service bindings can use `PolledBindingCandidateSource` to repeat registry discovery at the configured policy interval. `ON_TOPOLOGY_CHANGE` and `ON_FAILURE` therefore operate on observed candidates and health; they do not imply an instantaneous network-wide push event. A healthy target may remain sticky according to the selection policy.

A new JVM becomes eligible when it publishes a matching, trusted, live record and a discovering client observes it. The selected replacement must still satisfy the Java contract, qualifiers, context and security requirements. Refresh changes the target of eligible future calls. It does not replay business code that may already have executed.

## Verify and troubleshoot

1. Check that each runtime uses the intended registry implementation and context.
2. For multicast, verify interface, group, port and actual forwarding. For bootstrap, verify seed reachability and certificate SAN/client identity.
3. Confirm that the exporting runtime advertises an invocation endpoint reachable from the client; a successful discovery response does not prove that path works.
4. Check lease freshness, record verification and bounded-query diagnostics before increasing timeouts.
5. Start a second JVM, observe its candidate record, stop it, and verify the configured binding's future-call behavior.

Use the configuration fragments above in the owning registry and application hosts; keep both endpoints alive for the application lifetime.
