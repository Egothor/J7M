# Read-only network bootstrap

`BootstrapRegistryServer` exposes an explicitly installed registry through mutual
TLS. `NetworkBootstrapEndpointResolver` resolves `bootstrap+tls://host:port`
addresses, including configured DNS names and IPv4/IPv6 literals. A registry in
another JVM can therefore be a bootstrap seed. This endpoint supports only
lookup, list and peer reads. It has no publication, heartbeat, administrative or
Java object deserialization operation. Existing defaults do not install either
component; `InMemoryBootstrapEndpointResolver` remains available for embedded
use.

## TLS and authorization

Supply a normal JDK `SSLContext` with the endpoint's own private key/certificate
and a dedicated trust store. On the server, that trust store authorizes client
certificates; all clients it trusts can read the explicitly configured context
allowlist. On the client, trust the registry server issuer and provide a client
identity trusted by that server. Do not use a permissive trust manager. The
server certificate must contain the original DNS name or IP in its SAN; the
resolver enables HTTPS endpoint identification. Certificate issuance, key
storage and rotation belong to host operations. Do not put credentials in a
bootstrap URI, ObjectRef metadata or registry records.

A host can construct its TLS context using the standard key/trust manager APIs:

```java
KeyStore identity = KeyStore.getInstance("PKCS12");
try (InputStream input = Files.newInputStream(identityPath)) {
    identity.load(input, identityPassword);
}
KeyStore trust = KeyStore.getInstance("PKCS12");
try (InputStream input = Files.newInputStream(trustPath)) {
    trust.load(input, trustPassword);
}
KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
keys.init(identity, privateKeyPassword);
TrustManagerFactory issuers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
issuers.init(trust);
SSLContext tls = SSLContext.getInstance("TLS");
tls.init(keys.getKeyManagers(), issuers.getTrustManagers(), null);
```

Paths and password character arrays come from the host's protected configuration.
Use distinct identity and trust material for server and client as appropriate.
No new security provider or transport dependency is required.

## Registry host process

Use the authoritative local registry as the borrowed backend, rather than a
recursive network bootstrap wrapper. For example, the registry host's lifecycle
can own this endpoint:

```java
try (BootstrapRegistryServer server = new BootstrapRegistryServer(
        new InetSocketAddress("0.0.0.0", 4117), serverTls, localRegistry,
        Set.of("tenant-a"), Duration.ofSeconds(2))) {
    // server.address() reports the actual bound address, including an allocated port.
    hostLifetime.awaitShutdown();
}
```

`localRegistry`, `serverTls` and `hostLifetime` are supplied by the existing host.
The endpoint borrows the registry; closing the endpoint does not close the
registry. The host can publish its reachable `bootstrap+tls` address through
existing `RegistryPeerStatus.endpoints`. Advertise a routable address, rather
than a wildcard bind address or a `localhost` address for remote machines.

## Discovering process

Choose the initial seed URIs, context, traversal bounds and verifier explicitly:

```java
try (NetworkBootstrapEndpointResolver resolver = new NetworkBootstrapEndpointResolver(
        clientTls, Duration.ofMillis(750));
     BootstrapRegistryClient discovery = new BootstrapRegistryClient(
        localRegistry, Clock.systemUTC(),
        new BootstrapRegistryOptions("client-registry", "tenant-a",
            List.of("bootstrap+tls://registry-a.internal:4117",
                    "bootstrap+tls://registry-b.internal:4117"),
            Duration.ofSeconds(2), 3, 16, 64, Duration.ofSeconds(1), resolver),
        recordVerifier, true, auditSink)) {
    List<RegistryRecord> candidates = discovery.list(query);
    Optional<RegistryRecord> concrete = discovery.lookup(reference);
}
```

Here `clientTls`, `recordVerifier`, `auditSink`, `query` and `reference` come from
host configuration and normal registry APIs. `BootstrapRegistryClient` owns and
closes its supplied local registry, as before. The resolver is independently
owned and must be closed by the host. Use a separate borrowed/local registry only
where that existing ownership contract is appropriate.

TLS authenticates and protects the connection. Record authenticity is still
checked by the existing `RegistryRecordVerifier` and strict received-record
policy: transport authentication does not mark a publisher's record trusted.
Context, generation, signature freshness, replay and lease checks remain in that
policy. Exact repeated signed heartbeats can be rejected after a cache miss until
a fresh valid publication arrives; the network path does not disable replay
checking. Cached bootstrap candidates are rechecked for lease expiry before
return. Concrete lookup calls each peer's concrete lookup operation, so another
instance of the same service cannot occupy its only result slot.

The facade returned directly by the resolver is a low-level read-only transport
client. It preserves record authentication fields but does not itself verify
publisher signatures. Use it through `BootstrapRegistryClient` for normal trusted
discovery. Closing a facade disables its future operations; closing the resolver
also terminates active sockets.

## Bounds, deadlines and operations

* A request has one connection and one response. DNS, TCP connect, TLS handshake,
  write and read share an operation budget of 1 ms through 5 seconds. A facade
  resolved by a bootstrap traversal also captures that traversal's original
  absolute deadline, including subsequent peer reads. Failed seeds consume this
  budget; they do not obtain a fresh overall timeout.
* The resolver owns at most 64 active calls and four DNS workers, without a DNS
  work queue. JDK DNS can ignore interruption; such work retains its worker until
  physical exit. Saturated DNS admission fails safely instead of spawning more
  workers. The OS/JDK DNS implementation remains outside library cancellation
  control. The current connection attempt uses the first resolved address;
  separate seed URIs provide endpoint failover.
* The server permits 16 concurrent requests with no worker queue and 128 new
  connections per one-second admission window. It limits handshake and request
  lifetime before decoding. A backend must return promptly; an uncooperative
  backend can retain one of the 16 workers after its socket deadline. Close
  interrupts owned workers and closes sockets but does not forcibly abort backend
  Java code.
* Outer frames are at most 256 KiB. There are at most 64 returned records or
  peers. Record/query fields reuse the existing multicast codec's 60 KiB packet,
  48 KiB aggregate string, 2048-byte string and 128-element map/list limits.
  Peers use explicit fields, at most 128 nested entries and 2048 Java characters
  per string, additionally constrained by the whole frame. Oversized responses
  fail; they are not silently truncated to a byte limit or deserialized as Java
  objects.
* A bootstrap scan continues to obey its configured maximum depth, contacts and
  candidates. Peer hints are routing hints, not authority. Only the configured
  TLS trust store authorizes a destination, and the requested context remains
  subject to server authorization and record verification.

Failures expose the fixed `Bootstrap endpoint unavailable` middleware error.
Existing bootstrap discovery statistics count successful/failed peer contacts;
server DEBUG diagnostics contain fixed text and exception class names, without
raw throwable messages, credentials or record payloads. To diagnose startup,
check certificate SAN/trust/client identity, configured context, seed reachability
and bounds before increasing a timeout. Rollback consists of closing these
explicitly owned components and removing their configuration; embedded/default
runtime assembly is unchanged.
