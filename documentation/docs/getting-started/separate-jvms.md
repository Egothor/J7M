# Exercise communication across JVMs

Two runtime objects in one JVM and two independent JVM processes are different exercises. Start by seeing actual transport behavior, then configure a registry connection shared by the independently launched applications.

## First: actual gRPC between two runtimes

```shell
./gradlew -I documentation/examples/compile.init.gradle documentationRunRuntime
```

Expected application output:

```text
RuntimeExample OK: two runtimes, actual gRPC, callback, filtered discovery
```

The program uses two distinct runtimes and real gRPC, but shares an embedded registry store in one JVM. It checks a request/reply, a caller-owned callback and metadata-filtered discovery. Its development security and disabled administration are explicitly local tutorial settings.

```java title="RuntimeExample.java"
--8<-- "documentation/examples/src/main/java/org/j7m/documentation/RuntimeExample.java"
```

## Then: independent registry and application JVMs

A JVM-local embedded registry store cannot connect separate processes. Run a host-owned bootstrap endpoint beside the publishing application's registry, and configure the client application to query it over mutual TLS. The following two fragments belong in separate Java entry points; they deliberately leave certificate loading and business transport construction to the host configuration.

### Publishing JVM

```java
// localRegistry contains this JVM's exported business services.
// serverTls is an SSLContext with the server identity and trusted client issuers.
try (var endpoint = new BootstrapRegistryServer(
        new InetSocketAddress("127.0.0.1", 4117),
        serverTls, localRegistry, Set.of("orders"), Duration.ofSeconds(2))) {
    shutdownSignal.await();
}
```

`shutdownSignal` is an application-owned `CountDownLatch`. The endpoint borrows `localRegistry`; its owner must keep that registry and the service exports alive until shutdown. Use a host-reachable bind address when registry clients are on another machine.

### Discovering JVM

```java
try (var resolver = new NetworkBootstrapEndpointResolver(
            clientTls, Duration.ofMillis(750));
     var registry = new BootstrapRegistryClient(
            localRegistry, Clock.systemUTC(),
            new BootstrapRegistryOptions(
                    "order-client", "orders",
                    List.of("bootstrap+tls://localhost:4117"),
                    Duration.ofSeconds(2), 3, 16, 64,
                    Duration.ofSeconds(1), resolver),
            recordVerifier, true, auditSink)) {
    // Supply registry to this application's MiddlewareRuntimeBuilder.registry(registry).
    // Resolve the exported business interface through the resulting runtime.
}
```

`clientTls`, `recordVerifier` and `auditSink` come from trusted host configuration. The client's certificate must be trusted by the endpoint, and the endpoint certificate must include `localhost` as a SAN for this tutorial URI. Publisher-record verification remains distinct from TLS authentication. `BootstrapRegistryClient` owns the local registry it is given; do not give it an independently owned runtime registry that will be closed twice.

The bootstrap classes are in `org.j7m.middleware.registry.bootstrap`. Import JDK `InetSocketAddress`, `Clock`, `Duration`, `List` and `Set` from their standard packages. For a complete certificate-loading fragment and production seed configuration see [TLS configuration](../security/tls-and-certificates.md) and [discovery configuration](../configuration/discovery.md).

!!! important "Same-host scope"
    The built-in gRPC/RMI providers currently advertise loopback business endpoints. These process exercises run on one host. A reachable registry on another machine does not rewrite a service's advertised address. Cross-host business invocation requires an appropriate transport/host integration; native resource endpoints and network bootstrap have their own explicit bind configuration.

