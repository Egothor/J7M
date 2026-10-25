# Executable manual examples

These small programs use the project's current implementation. They are adapted
from the source tests named in [`../data/example-sources.json`](../data/example-sources.json).
Their own JUnit checks execute the adapted code again; passing original tests is
not used as a substitute for testing these adaptations.

## Requirements and verification

Use JDK 25 and Python 3.9 or newer. Run the following from the repository root:

```shell
python3 documentation/tools/verify_examples.py
```

The Gradle wrapper downloads its pinned Gradle distribution and normal project
dependencies on first use. The verifier honors existing `JAVA_TOOL_OPTIONS` and
uses an `HTTPS_PROXY` setting when Java proxy options have not been supplied.
After dependency installation, `--offline` avoids dependency network access.
The network example still opens loopback gRPC sockets.

Equivalent direct Gradle command:

```shell
./gradlew -I documentation/examples/compile.init.gradle documentationVerify
```

The init script adds documentation tasks only to this invocation. It does not
change the normal root build. Generated classes, reports and temporary outputs
are under `documentation/examples/build/`. `documentationVerify` runs all five
checks every time; the Java compiler can reuse outputs only when inputs match.

Run one program independently:

```shell
./gradlew -I documentation/examples/compile.init.gradle documentationRunContainer
./gradlew -I documentation/examples/compile.init.gradle documentationRunSchema
./gradlew -I documentation/examples/compile.init.gradle documentationRunRuntime
./gradlew -I documentation/examples/compile.init.gradle documentationRunQueue
```

## What each program demonstrates

| Program | Executed behavior | Deliberate boundary |
| --- | --- | --- |
| `ContainerExample` | Generated `@Inject`, `@Startup` and `@Shutdown` adapters; explicit component definitions; missing dependency rejection | Bare container lifecycle is driven explicitly. Production application hosting owns those phases. |
| `SchemaExample` | `@SchemaType`/`@WireField` generated allowlist and explicit `SchemaDescriptor`; roundtrip and unregistered-value rejection | No arbitrary class serialization or dynamic remote class loading. |
| `RuntimeExample` | Two distinct runtimes, actual gRPC request/reply and callback, metadata-filtered lookup | One JVM shares an embedded registry store. Discovery across machines needs multicast or network bootstrap. Development transport security and disabled administration are intentional tutorial choices. |
| `QueueExample` | Real queue reducer through the facade, stable request identity, conflicting request rejection, claim/ack receipt and snapshot roundtrip | The small in-process adapter replaces consensus hosting. It does not prove quorum durability, measured-clock admission or reactive queue delivery. |

`GreetingComponent` and `GreetingFormatter` are public top-level components with
public constructors. This is the generated component contract; nested component
classes are not accepted. The annotation processor generates immutable indexes
and direct adapters during `documentationCompile`. The explicit variant uses
plain classes with no J7M injection/lifecycle annotations.

## Actual managed distributed integration proofs

For the queue, job and saga host behavior discussed in the manual, run the
unchanged real project integration tests:

```shell
python3 documentation/tools/verify_examples.py --integration
```

This executes the five adapted examples plus three selected original tests:

* `ManagedQueueGeneratedIT.generatedCallbackHasPreconstructorGuardIdentityAndActualTrackedTraceOverThreeVoterPush`
* `ManagedJobGeneratedIT.generatedProviderCheckpointProgressAndFreshRestoreUseActualNativeOwnershipAndPhysicalCompletion`
* `ManagedSagaGeneratedIT.actualGeneratedSagaRejectsMissingBackendPolicyAndAdministrationBeforeConstructorsThenRunsAndCompensates`

Those tests compile actual generated application components and use native
backends. Their process/host setup is intentionally retained rather than hidden
behind a misleading one-line annotated worker. Full signed deployment and
process-crash scenarios have additional tests cited by the corresponding manual
chapters. This command does not run the opt-in hour-long qualification.

The verifier writes `build/verification.json` with test counts and hashes of the
cited sources. The results describe the checks actually run and do not certify
unrelated transports, deployment profiles or arbitrary application side effects.

To test the verifier's fail-closed result handling:

```shell
python3 -m unittest discover -s documentation/tools/tests -p 'test_*.py'
```

## Live binding failover

```sh
./gradlew -I documentation/examples/compile.init.gradle documentationRunFailover
```

`FailoverExample.java` runs service **b**, client, then service **a** as three real
gRPC runtimes in **one JVM**, sharing an embedded registry. Public live-binding
adapters discover the newcomer: `ON_FAILURE` stays with b while
`ON_TOPOLOGY_CHANGE` selects a. A business call records one effect and throws;
its effect is never replayed on a. After b closes, a read-only identity call observes any transport failure; a
bounded binding watch waits for a and the next call uses it. No sleeps or guaranteed first-call failure are
assumed. Expected output:

```text
FailoverExample OK: sticky b, topology a, failover a; effects b=1 a=0 (no replay)
```

The in-process registry and permissive binding authorizer are tutorial setup.
This does not demonstrate cross-process discovery or production security; the
separate-JVM integration test listed in the provenance catalog covers a distinct
same-host process scenario.
