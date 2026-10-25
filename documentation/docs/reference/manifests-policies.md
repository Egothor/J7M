# Application Manifests and Host Policies

The application manifest is JSON in `META-INF/j7m-application.json`. The parser accepts named fields and rejects unsupported structure; it does not load application classes while parsing.

The following structural example uses the current manifest schema. Class and service names must identify actual compiled code in the packaged release:

```json
{
  "schemaVersion": "j7m.application/v1",
  "application": {
    "group": "com.example",
    "name": "orders",
    "version": "1.0.0",
    "displayName": "Orders"
  },
  "runtime": {
    "minJ7mVersion": "0.5.6",
    "javaVersion": "25",
    "moduleMode": "CLASSPATH",
    "bootstrapClass": "com.example.orders.OrdersApplication"
  },
  "services": [
    {
      "serviceName": "//apps/example/orders/Orders",
      "interfaceName": "com.example.orders.Orders",
      "implementationClass": "com.example.orders.OrdersService",
      "transport": "grpc",
      "globallyExported": true,
      "metadata": { "domain": "orders" }
    }
  ],
  "security": { "trustDomain": "orders", "confidential": true },
  "resources": {
    "cpu": "1",
    "memory": "256MiB",
    "maxConcurrentInvocations": 16,
    "maxInboundPayload": "1MiB"
  }
}
```

The sample is not a complete deployable bundle: it illustrates the manifest contract for your compiled application. A host still needs a suitable bootstrap implementation, declared services, verified artifacts and installed capabilities. Background-only applications may declare no exported services but still require their bootstrap.

| Area | Meaning |
| --- | --- |
| Application coordinate | Group, name and version identify an immutable release |
| Runtime requirements | Minimum middleware version, Java version, module mode and bootstrap identity |
| Modules | In JPMS mode, declared module names and bundle-relative JAR paths, plus bootstrap module |
| Services | Logical name, Java interface/implementation, transport and export metadata |
| Security | Requested trust domain/confidentiality; host policy must enforce them |
| Resources | Requested CPU/memory descriptions and actual supported invocation/payload policies |

Resource strings do not establish an operating-system sandbox. The selected host/isolation provider determines which requested controls can actually be enforced.

## Named policies

A policy name selects an exact trusted host registration. Examples include retry, circuit/bulkhead behavior, ownership, transaction handling and distributed hosted features. The host supplies the implementation and its finite configuration. Application annotations do not register credentials or authorize additional operations.

Treat unknown policy names, incompatible component shapes, unsupported refresh modes and wrong resource types as activation errors. Do not fall back silently to a weaker behavior. For each deployed policy name, retain the corresponding host configuration and intended resource/tenant scope.

**Source:** manifest parser (repository source: `j7m-application-platform/src/main/java/org/j7m/middleware/application/ApplicationManifestParser.java`), manifest API (repository source: `j7m-api/src/main/java/org/j7m/middleware/application/api/ApplicationManifest.java`).
