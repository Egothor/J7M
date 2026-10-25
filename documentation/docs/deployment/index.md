# Deployment Guide

A J7M deployment has at least a host process and its application artifacts. Network discovery, invocation transports, artifact distribution and durable resource groups can run in separate processes. Select those boundaries from the application's availability and operational needs, rather than assuming every concern belongs in one JVM.

<figure class="j7m-diagram" markdown>
<div class="j7m-diagram-scroll" tabindex="0" role="region" aria-label="Runtime and application topology" markdown>

[![Runtime and application topology](../assets/diagrams/runtime-topology.svg)](../assets/diagrams/runtime-topology.svg){target="_blank" rel="noopener"}

</div>
<figcaption markdown="span">[Open full-size SVG](../assets/diagrams/runtime-topology.svg). On small screens, scroll the diagram horizontally.</figcaption>
</figure>
Follow this sequence:

1. [Build and inspect the artifacts](artifacts.md).
2. [Choose process and host boundaries](topologies.md).
3. [Establish application signing and repository trust](signed-applications.md).
4. [Configure reachable endpoints and durable storage](network-storage.md).
5. [Integrate with the process/container supervisor](containers.md).
6. [Define readiness and graceful shutdown](lifecycle.md).
7. [Verify the deployed topology from another process](verification.md).

Application discovery and consensus membership are independent. A newly discovered service can become a candidate for future calls; it does not become a voter or acquire authority over durable state. Similarly, repository publication makes a verified artifact available but does not prove that an application has been activated.

The examples use the current APIs and immutable application coordinates. There is no required J7M-specific Kubernetes operator or universal application-server XML file. Host assembly is explicit Java configuration, application declarations use the actual JSON manifest, and optional deployment tooling consumes those contracts.

## Release operations

- [Gradle application workflow](gradle-workflow.md): package, sign, verify and publish with truthful lifecycle task availability.
- [Application repositories](repositories.md): immutable publication, verification, promotion and audit.
- [Repository replication and repair](repository-replication.md): transfer bounds, conflicts, reconciliation and stronger verification policy.
- [Application upgrade and rollback](upgrade-rollback.md): local stop-start, rolling and blue-green behavior.
- [CLI reference](../reference/cli.md): command families, target identity, admission, paging and safe output.
