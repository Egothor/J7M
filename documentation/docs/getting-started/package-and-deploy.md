# Package and deploy an application

A component example verifies application code. A managed deployment additionally needs a verified artifact, a manifest, a repository, trusted host capabilities and a controlled activation request.

## Prepare the application

Keep public business interfaces separate from implementation details. Compile generated component/schema/host adapters with the real processor. For named modules, declare the required generated service providers and export public contracts; do not use blanket `opens` as a replacement for a valid generated contract.

```java
@J7mApplication(group = "example", name = "orders", version = "1.0")
public final class OrdersApplication {
    public OrdersApplication() { }
}
```

This fragment supplies application coordinates, not a complete deployable bundle. Include the implementation, its finite generated metadata, manifest and signing/checksum material required by the chosen repository/host path.

## Configure the host separately

The host chooses transports, registry/discovery, security, isolation/resource policy and any queue/job/saga factories. A bundle may select a named policy; it cannot create trusted backend credentials or install its own administrative authority.

The deployment API uses exact coordinates:

```java
ApplicationCoordinate coordinate = ApplicationCoordinate.parse("example:orders:1.0");
ApplicationDeploymentPlan plan = manager.planActivation(coordinate);
```

This is a public API fragment with a configured `ApplicationManager`. Planning is not activation. Review the plan and use the actual activation request/host policy described in the [deployment guide](../deployment/index.md).

## Verify admission and shutdown

Verify that preparation and readiness complete before services or hosted workers admit work. On any failure, uncommitted exports/features must be closed. At shutdown, close admission and drain actual owned work before releasing application resources.

For complete artifact and host procedures, continue to [artifacts](../deployment/artifacts.md), [signed applications](../deployment/signed-applications.md), [lifecycle](../deployment/lifecycle.md) and [deployment verification](../deployment/verification.md).
