# Verified examples and source tests

The manual includes small executable programs plus links to deeper project integration tests. The programs are tested adaptations, not a copy of JUnit setup renamed as a production application.

## Run them

From the repository root:

```shell
python3 documentation/tools/verify_examples.py
python3 documentation/tools/verify_examples.py --integration
```

The verifier records actual test counts and source hashes under `documentation/examples/build/`. It uses the current JDK, actual annotation processor and project dependencies. Its integration mode runs the selected original tests below without starting the long opt-in qualification.

## Executable inventory

| Entry point | What actually executes | Boundary |
| --- | --- | --- |
| `ContainerExample` | Generated constructor injection and lifecycle adapters; explicit component definitions; missing dependency rejection | Local owned container, not signed deployment |
| `SchemaExample` | Generated and explicit schema roundtrips; unregistered-value rejection | Data allowlist, not heap serialization |
| `RuntimeExample` | Two distinct runtimes, actual gRPC call/callback and filtered lookup | One JVM/shared embedded store; development security |
| `QueueExample` | Real queue reducer/facade, stable identity, receipt ACK and snapshot roundtrip | Local adapter, not quorum or reactive-push qualification |
| `FailoverExample` | Sticky and topology-sensitive bindings, actual gRPC target replacement and no replay of an entered business effect | Three runtimes in one JVM/shared store; development security |

The source files are under `documentation/examples/src/main/java/org/j7m/documentation/`. `documentation/data/example-sources.json` records the original test methods, supported claims and exclusions for each adaptation.

## Actual generated hosted integrations

The integration mode executes these generated-host scenarios. Each linked pattern explains the relevant setup, code excerpts and observable results directly:

| Scenario | Boundary exercised |
| --- | --- |
| [Generated queue delivery](../patterns/work-queues.md) | Generated queue callback, required guard identity, three-voter push and tracked trace |
| [Durable job recovery](../patterns/durable-jobs.md) | Actual job provider, checkpoint/progress, fresh restore and physical completion |
| [Saga execution and compensation](../patterns/sagas.md) | Policy/admin rejection before construction, generated forward work and compensation |

For source provenance, these checks correspond to the selected generated queue (repository source: `j7m-application-distributed/src/test/java/org/j7m/middleware/application/distributed/ManagedQueueGeneratedIT.java`), job (repository source: `j7m-application-distributed/src/test/java/org/j7m/middleware/application/distributed/ManagedJobGeneratedIT.java`) and saga (repository source: `j7m-application-distributed/src/test/java/org/j7m/middleware/application/distributed/ManagedSagaGeneratedIT.java`) test classes. Exact method names and source hashes are recorded in `documentation/data/example-sources.json`; they are an evidence index, not prerequisites for understanding the manual.

For independent same-host JVM discovery/RPC exercises, use the commands in [Separate JVMs](../getting-started/separate-jvms.md). Those include the actual service-binding process harness and mTLS network-bootstrap child process.

## How code blocks are classified

Complete source includes come from the compiled example files. Short Java blocks elsewhere are explicitly focused contract/application fragments: host configuration, imports or application collaborators are supplied by the surrounding application. A fragment that demonstrates an annotation spelling is not independently claimed as a complete hosted integration.

Every public annotation has a documented example in the [complete reference](../annotations/index.md), including metadata-only/reserved declarations. That coverage count must not be mistaken for 80 independently executed distributed capabilities.
