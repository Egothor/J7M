# Adapter SPI and compatibility testing

The adapter SPI connects J7M resource contracts to alternative implementations. It separates a provider's declared capabilities from behavior observed by the compatibility test kit (TCK). An integration name or successful connection is insufficient evidence that an adapter preserves ordering, durability, fencing or isolation.

## Provider responsibilities

`AdapterProvider` exposes `id()`, `descriptor()`, `health()`, `status()`, `metrics()`, `auditEvents()` and `close()`. Providers own their connections and background work, document any narrower concurrency contract, and keep operations bounded. Public descriptions, status and diagnostics must redact credentials and payloads.

The SPI module, `org.j7m.adapter.spi`, depends on the public API, not on a vendor client. Keep vendor dependencies inside the implementation module. Declare supported semantic profiles and limitations explicitly instead of presenting an approximation as a stronger contract.

## Execute a semantic profile

This source-checked harness fragment assumes an actual `AdapterProvider` named `provider` and its `AdapterSemanticProbe` named `probe`.

```java
AdapterTckRunner runner = new AdapterTckRunner();
AdapterTckReport report = runner.run(
        provider, probe, AdapterTckProfiles.messaging());
var compatibility = report.summary();
var scenarioResults = report.results();
var failureResults = report.failureModeResults();
```

The fragment shows harness assembly, not a complete vendor adapter or an executed certification. Review the summary and every required scenario before accepting the adapter. Available profiles cover durable log, messaging, coordination, consensus, repository replication, saga and isolation.

The runner supplies bounded operation and cleanup deadlines, deterministic reference operations, failure-mode checks and redaction checks. A probe must perform actual observable operations against its implementation. For reference operations, override the three-argument `runReferenceOperations(scenario, operations, session)` and record actual effects through `AdapterReferenceSession.recordEffect`. Merely returning a descriptor, echoing a transcript or implementing only the older overload does not demonstrate the required effects.

## Interpret the report

| Compatibility level | Meaning for deployment |
| --- | --- |
| `NATIVE_EQUIVALENT` | Required tested semantics match the selected native profile |
| `COMPATIBLE_WITH_LIMITATIONS` | Use only where the reported limitations are acceptable |
| `DEVELOPMENT_ONLY` | Restrict to development/test scenarios |
| `UNSUPPORTED` | Required capability is unavailable |
| `REJECTED` | Checks found behavior that prevents acceptance |

Compatibility applies to the adapter version, configuration and semantic profile tested. It is not third-party certification or a promise of universal exactly-once effects. Test production transport, persistence and failure behavior separately where the profile requires them.

The runner also checks sentinel leakage in public provider and report surfaces. Avoid including secrets in `toString`, exceptions, health/status, audit events or failure descriptions. See [enterprise integrations](../security/enterprise-integrations.md) and [module dependencies](module-dependencies.md).
