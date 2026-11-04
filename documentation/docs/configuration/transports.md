# Transport Configuration

Discovery returns a remote reference. A transport provider interprets that reference's transport endpoint and performs invocation. Configuring multicast or a bootstrap seed does not open an application's gRPC or RMI invocation path.

J7M supplies gRPC and RMI providers behind `TransportProvider`. A provider is owned by exactly one runtime. Add providers to `MiddlewareRuntimeBuilder.addTransport(...)`; the runtime starts and closes them. Service export and proxy creation select a transport by its identifier and the reference's contract.

## gRPC listener and authoritative advertised endpoint

`GrpcTransportProvider()` and `GrpcTransportProvider(securityOptions)` bind and advertise IPv4 loopback (`127.0.0.1`) with an OS-assigned port. The listener is now explicitly loopback-bound; older versions listened on a wildcard while publishing loopback. Configure a public destination before the runtime starts:

```java
import org.j7m.middleware.transport.grpc.GrpcNetworkConfiguration;
import org.j7m.middleware.transport.grpc.GrpcSecurityOptions;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;

var network = new GrpcNetworkConfiguration(
        "0.0.0.0", 7443, "worker.example.net", 7443);
var transport = new GrpcTransportProvider(
        GrpcSecurityOptions.mutualTls(true, hostOwnedTlsConfiguration), network);
// Add transport to MiddlewareRuntimeBuilder.addTransport(transport) before build().
```

`hostOwnedTlsConfiguration` is a `GrpcTlsConfiguration` loaded from the host's certificate, private key and trust-root paths. The server certificate must contain `worker.example.net` in its SANs. Do not use an authority override to compensate for a mismatched certificate.

| Setting | Meaning and supported combinations |
| --- | --- |
| `bindHost` | Bare DNS name or IP literal assigned to the local host, or wildcard `0.0.0.0` / `::`. Startup resolves the name and binds its first address. |
| `bindPort` | `0` requests an available OS port; `1` through `65535` requests that exact local port. |
| `advertisedHost` | Explicit bare DNS name or IPv4/IPv6 literal reachable by clients. It must resolve at startup; every resolved address must be non-wildcard and non-multicast, and limited broadcast is rejected. |
| `advertisedPort` | `0` publishes the actual bound port, including an ephemeral bind; a positive port publishes that exact destination for host-managed forwarding/NAT. |
| IPv6 | Use bare literals such as `::1`; the published URI brackets them. Scoped literals, embedded ports, schemes, credentials and URI paths are unsupported. |

For a local alias with an ephemeral port, use `new GrpcNetworkConfiguration("127.0.0.2", 0, "127.0.0.2", 0)`. For wildcard binding, explicitly supply a usable advertised host; wildcard addresses never become invocation destinations. A positive advertised port may differ from the listener port, but J7M does not create forwarding or prove remote reachability. With an ephemeral bind behind forwarding, the host must arrange forwarding to the resolved listener port before clients invoke it. DNS resolution during startup cannot establish routing, firewall permissions or DNS consistency on other hosts.

Host syntax and port ranges fail with `INVALID_ARGUMENT` when configuration is constructed. Unresolvable hosts and prohibited destination addresses fail before a listener is opened. Unassigned local bind addresses, occupied ports and invalid TLS material fail startup with `TRANSPORT_ERROR`; no usable export is published, and any candidate server is shut down. A provider belongs to one runtime and cannot be started twice or restarted after closing.

The runtime creates each authoritative `ObjectRef` using the provider's resolved advertised endpoint. Clients use that generated reference directly through lookup or `runtime.reference(...)`. Rewriting `ObjectRef.withTransport(...)` is not configuration: existing endpoint, identity, runtime and interface authority checks still apply. TLS chain checks, SAN verification, mutual TLS and authorization remain independent requirements. Configure reachable callback providers in the caller runtime as well when callbacks are used.

The executable [runtime example](../getting-started/separate-jvms.md) now demonstrates an explicitly configured nondefault loopback endpoint. Real runtime tests exercise alias invocation over mutual TLS with a matching IP SAN, resolved port zero, fixed ports, authority rejection, SAN mismatch, bind failure cleanup and default constructors. These are local socket checks; actual multi-host operation is not qualified by this change.

RMI still advertises loopback and has no equivalent public bind/advertise API. Bootstrap, native consensus/resource services and artifact endpoints have their own network configuration and do not change invocation endpoints.

The gRPC provider carries generic remote-object operations; it does not require a separate static protobuf service definition for each Java application interface. Its public contract also supports logical asynchronous calls and streaming. Exact locally established return-class tokens preserve an application's schema/classloader boundary during decoding.

RMI remains a distinct provider. Do not assume a gRPC-specific streaming or cancellation facility exists merely because both providers implement a common transport SPI. Verify the concrete capability required by the application against its provider and tests.

## Deployment procedure

1. Choose the transport required by the service contract and application manifest.
2. Establish that the chosen provider supports the required host boundary, endpoint reachability and security before runtime startup.
3. Export a service and inspect its actual reference endpoint.
4. From another JVM, invoke a bounded request and, where used, a callback or returned reference.
5. Test deadline, authentication and oversize rejection rather than only a successful call.

An HTTP reverse proxy intended for ordinary REST controllers is not automatically a valid front end for every J7M protocol. Keep the transport's actual protocol, framing and authentication intact. The headless administration interface is not an application HTTP server.

**Sources:** TransportProvider (repository source: `j7m-api/src/main/java/org/j7m/middleware/transport/api/TransportProvider.java`), gRPC provider (repository source: `j7m-transport-grpc/src/main/java/org/j7m/middleware/transport/grpc/GrpcTransportProvider.java`), RMI provider (repository source: `j7m-transport-rmi/src/main/java/org/j7m/middleware/transport/rmi/RmiTransportProvider.java`).
