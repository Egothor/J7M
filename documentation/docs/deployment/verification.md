# Deployment Verification

Verify behavior from the boundary where the application will be used. A JVM-local test does not establish cross-host routing, and a process count does not establish quorum availability.

| Check | Evidence to collect |
| --- | --- |
| Artifact | Exact coordinate, checksum and accepted signature policy |
| Host | Java version, module/classloader resolution and installed capabilities |
| Discovery | Another JVM sees the intended live, trusted candidate in the correct context |
| Invocation | Remote request succeeds through the actual advertised transport endpoint |
| Security | Missing/wrong identity, context and permission are rejected |
| Limits | Oversize payload, exhausted concurrency and deadline behavior are explicit |
| Durable state | A committed operation survives the required node/process restart |
| Ownership | A retired lease/receipt/epoch cannot mutate protected state |
| Shutdown | Admission stops and physical drain completes before final release |
| Observation | Metrics/logs identify safe outcomes without exposing secrets or payloads |

## A practical sequence

Start the configured seed or multicast listeners and resource hosts. Activate one service instance, then call it from a separate client process. Activate another matching instance and observe the configured binding's refresh behavior. Stop the first instance while a bounded operation is active and check the documented result: success, a safe predispatch retry, or an explicit uncertain outcome.

For a durable queue, prove that a message has one current receipt and that a stale receipt cannot acknowledge another delivery. For jobs and ownership, prove a restarted worker receives current authority and the old one cannot commit a protected effect. For inbox/outbox, compare the database effect, marker and native message identity across the relevant crash boundary.

Do not infer exactly-once external effects from a successful failover demonstration. Record which operations are deduplicated or transactional and where uncertainty remains.

The project's process/integration tests provide examples of these oracles. See [testing distributed applications](../testing/index.md) for the test families and the scope of their evidence. A test's validation profile applies to the exercised topology and configuration; it is not a blanket performance or availability warranty.

## Java 25 runtime access

The gRPC transport uses `grpc-netty-shaded`. On Java 25, Netty/JDK native access and `Unsafe` memory-access diagnostics must be handled deliberately. The repository's Java test and execution conventions supply:

```text
--enable-native-access=ALL-UNNAMED
--sun-misc-unsafe-memory-access=allow
```

These flags belong to the launched JVM, not to an application manifest or registry record. Supply the corresponding runtime policy when assembling a host outside the Gradle conventions, and recheck the actual classpath/module-path and dependency versions. Allowing these access paths does not grant application authorization or create process isolation. Do not silence a new warning without determining which dependency and access path produced it.
