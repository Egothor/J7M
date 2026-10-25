# Runtime and Nugget Configuration

`MiddlewareRuntimeBuilder` assembles an invocation runtime. Its defaults are an `EmbeddedRegistryClient`, a default value codec, the UTC system clock, a generated runtime identity and the development security configuration. These defaults are useful for local construction; a deployment must deliberately select its network, trust and administrative settings.

The following is a host-assembly fragment. `registry`, `transport` and `security` are already configured instances of the corresponding public APIs:

```java
MiddlewareRuntime runtime = new MiddlewareRuntimeBuilder()
        .runtimeId("orders-runtime-1")
        .registryContext("orders")
        .registry(registry)
        .addTransport(transport)
        .security(security)
        .build();
```

The resulting runtime owns the supplied registry and transport providers. Close the runtime during host shutdown; do not share one transport-provider instance between independently owned runtimes. If a separate component borrows the same local registry, coordinate its shutdown before the runtime closes that registry.

## Administration is a separate capability

Nugget administration is enabled by the builder unless explicitly disabled. Its configuration must provide a valid PIN source, or explicitly choose the unsafe developer mode. A deployment must not treat `SecurityDefaults.development()` or an unsafe administrative setting as a production credential policy.

Configure the headless administration service through `NuggetAdminRuntimeConfiguration.builder()` and pass it to `nuggetAdmin(...)`. The public builder exposes the existing security configuration, nugget name, PIN providers and discovery/introspection integration. Protected PIN material must not appear in status, logs, metadata or command history. See [identity and trust](../security/identity-and-trust.md).

## Configuration ownership

| Owner | Typical settings |
| --- | --- |
| Host | Runtime identity, registry implementation, transport providers, TLS, signing trust, admin access |
| Application activation | Configuration snapshot, managed binding factories, policy instances and declared resource limits |
| Distributed resource host | Durable directory, group identity, authorized voters, principal bindings, clock health and capacities |

An injected application value is not a host authority. For example, setting a string called `admin=true` in application configuration does not install a privileged native-resource client.

**Implementation sources:** MiddlewareRuntimeBuilder (repository source: `j7m-runtime/src/main/java/org/j7m/middleware/runtime/MiddlewareRuntimeBuilder.java`), NuggetAdminRuntimeConfiguration (repository source: `j7m-runtime/src/main/java/org/j7m/middleware/runtime/NuggetAdminRuntimeConfiguration.java`).
