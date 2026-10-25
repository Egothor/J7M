# Transport Configuration

Discovery returns a remote reference. A transport provider interprets that reference's transport endpoint and performs invocation. Configuring multicast or a bootstrap seed does not open an application's gRPC or RMI invocation path.

J7M supplies gRPC and RMI providers behind `TransportProvider`. A provider is owned by exactly one runtime. Add providers to `MiddlewareRuntimeBuilder.addTransport(...)`; the runtime starts and closes them. Service export and proxy creation select a transport by its identifier and the reference's contract.

## Current built-in endpoint boundary

The supplied `GrpcTransportProvider` and `RmiTransportProvider` currently advertise loopback (`127.0.0.1`) endpoints and expose no public bind/advertised-host configuration. Their out-of-the-box invocation examples therefore run separate JVMs on the same host. Multi-host application invocation requires a host integration or custom `TransportProvider` that actually supplies reachable endpoints; it cannot be enabled by an undocumented property. Bootstrap, native consensus/resource services and artifact endpoints have their own explicit network binding APIs and do not change this transport boundary.

| Concern | Operator action |
| --- | --- |
| Bind address | The supplied invocation providers use their current loopback endpoint behavior; a custom provider must implement its intended listener binding |
| Advertised address | The supplied providers advertise loopback; host integration for remote machines must actually supply routable references |
| TLS/authentication | Configure the concrete provider's supported security settings and trust policy |
| Payload and concurrency | Keep provider bounds consistent with application-level admission limits |
| Callbacks | Permit the caller-side callback endpoint when a returned or callback reference is invoked remotely |

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
