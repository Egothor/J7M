# Containers and Orchestrated Environments

J7M is a Java middleware library and application platform. A container runs the host JVM and its configured application lifecycle. Container orchestration must preserve the same network, storage and authority rules as a deployment on ordinary hosts.

There is no implicit J7M Kubernetes operator, Helm chart or cluster-membership controller described by this guide. Integrate the available Java APIs and process supervisor explicitly.

## Image and configuration

Build an image with the required Java 25 runtime, middleware modules, host assembly and verified application artifacts. Keep private keys, PINs and native authentication keys out of image layers. Supply them through protected deployment configuration. Separate immutable code from writable state and staging volumes.

Choose discovery based on actual network support. A container network may not forward UDP multicast. In that case, configure reachable mutual-TLS bootstrap seed URIs. The seed's advertised address and certificate SAN must match the name visible to the client, not only a process-local hostname.

The built-in gRPC/RMI invocation references are currently loopback addresses. Distinct container network namespaces do not share that loopback interface. A working bootstrap connection is therefore insufficient to make those built-in invocation references usable across containers. Supply a supported host integration/custom invocation provider before claiming such a topology; do not add a fictional environment variable to the deployment.

## Lifecycle integration

Map readiness to the host's real readiness/probe state, not merely an open socket. On termination, stop new admission, request drain, wait for tracked physical work, then close owned services and exit. The orchestrator's grace period must cover that sequence. A fixed short stop timeout can turn an otherwise graceful deployment into repeated forced failures.

Do not reuse a durable voter identity simultaneously in two running pods. Retain its exclusively owned state volume for an ordinary restart. A new member or a restored generation requires the appropriate membership/recovery workflow; discovery of a new pod does not authorize it to vote.

## Before scaling

Verify at least two container instances from a separate client, including callbacks if used. Exercise replacement while a request or durable claim is active. Check stale-owner rejection and retry identity rather than counting only the final number of running containers. Confirm which resources survive container deletion and which are rebuilt from the artifact repository.

See [lifecycle](lifecycle.md), [topologies](topologies.md) and [deployment verification](verification.md).
