# Configuration Guide

J7M separates the configuration owned by a host from the declarations shipped in an application. The host selects transports, registries, trust material, resource clients and named policies. An application requests those installed capabilities through its manifest, explicit APIs or generated annotation metadata. An annotation cannot install a privileged backend or grant its own permissions.

Start with the deployment boundary you need:

| Task | Read |
| --- | --- |
| Assemble a runtime and configure administration | [Runtime configuration](runtime.md) |
| Find service instances in other JVMs | [Discovery](discovery.md) |
| Carry invocations between runtimes | [Transports](transports.md) |
| Configure managed components and named policies | [Applications and policies](application-policies.md) |
| Host durable partitions, queues and executions | [Distributed resources](distributed-resources.md) |
| Set operational limits and observation | [Limits and observability](limits-observability.md) |

There is no universal `application.yaml` that silently configures every J7M subsystem. The examples in this guide identify real Java configuration objects and the JSON application manifest. Values such as `serverTls`, `recordVerifier` and `authenticationKey` denote objects supplied by protected host configuration; they are not magic variables populated by J7M.

Configuration should be reproducible. Keep nonsecret settings under version control, obtain secrets from an appropriate protected source, and record the chosen policy names with the deployed application coordinate. Validate a complete candidate configuration before admitting application work.

See also [configuration reference](../reference/configuration.md) and [deployment verification](../deployment/verification.md).
