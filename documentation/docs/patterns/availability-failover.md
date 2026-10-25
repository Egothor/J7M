# Availability, selection and failover

**Use case:** a service has several eligible instances and must tolerate an instance disappearing. First decide whether you are selecting an interchangeable service, addressing an exact object or operating on a quorum-backed resource.

=== "With annotations"

    ```java
    @Inject
    public SearchFrontend(@ServiceRef(name = "catalog",
            refresh = BindingRefresh.ON_FAILURE, policy = "catalog-client") Catalog catalog) {
        this.catalog = catalog;
    }
    ```

    Add host-selected circuit-breaker or bulkhead declarations when the dependency policy requires them. Business errors must not be mistaken for dependency unavailability.

=== "Explicit API"

    ```java
    Catalog catalog = runtime.lookup(
            "catalog", Catalog.class, LookupOptions.highAvailabilityOptions());
    String answer = catalog.find("book");
    ```

    This requests the runtime's supported HA lookup behavior. It is not a durable operation-replay protocol. For exact refresh and retry rules, use the explicitly configured live binding APIs.

## Do not conflate the failure domains

Service discovery returns candidates; it does not change a native consensus group's electorate. A quorum-backed queue follows its own authenticated leader/membership protocol. A returned object reference may be tied to one specific owner and must not be arbitrarily replaced with a different object.

## Configure and test

Set candidate limits, contract/version/qualifiers, trust/readiness admission, refresh mode, availability behavior and finite budgets. Default injection is stable and local; it does not automatically discover more JVMs. See [discovery](../configuration/discovery.md).

After entered business fails or its reply is lost, return that uncertainty to the application. Replacement can help the next logical call. It must not cause the same non-idempotent effect to run elsewhere without a recovery protocol.

## Worked scenario: add a replica, then lose the selected instance

A frontend has three binding policies over the same business contract:

| Binding | Healthy replica appears | Selected replica disappears |
| --- | --- | --- |
| ON_FAILURE | Keeps its current healthy incarnation | Reports the failed call; a later logical call may select another |
| ON_TOPOLOGY_CHANGE | Can reselect on a newer authoritative snapshot | Follows the supported replacement policy |
| NEVER | Keeps the original reference | Fails rather than becoming another object |

Suppose the frontend initially selects B. A newly eligible A appears later, while C is discoverable but denied by host admission. The sticky binding keeps B; the topology-sensitive binding can move to A. C is never usable merely because it was discovered.

The application treats a business failure as a business outcome:

```java
try {
    sticky.proxy().effect();
    System.out.println("Operation completed");
} catch (MiddlewareException failure) {
    System.out.println("Operation outcome requires application recovery");
    // Do not invoke effect() on another replica as an availability probe.
}
```

Here `sticky` is the host-owned `ServiceBinding<Business>` assembled below. The sample effect increments a counter before failing, so another invocation would repeat the effect. Connection replacement helps a subsequent logical operation; it does not reconcile this uncertain one.

When B disappears, an eligible A can serve a later read-only `identify()` call through the refreshable binding. An exact NEVER binding remains tied to B. In a deployment these replicas can be independent JVMs, with reachable transport and the configured network discovery path.

## Run the same idea as a small application

The manual also contains `FailoverExample`, a complete program using public `LiveServiceBindings`, `PolledBindingCandidateSource` and `RuntimeBindingConnector` APIs. It starts service `b`, adds service `a`, compares both refresh modes and closes `b`. Its service deliberately increments an `AtomicInteger` before returning a business failure:

```java title="Application operation used to detect accidental replay"
public void effect() {
    effects.incrementAndGet();
    throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE,
            "Business effect already entered");
}
```

Its public `Business` interface has `String identify()` and `void effect()`. Both replicas are explicitly exported as service `business` with the same public contract ID, version and qualifier fingerprint:

```java title="Actual publication metadata used by the runnable application"
var options = new ExportOptions(false, "grpc", "default", List.of(), Map.of(
        "j7m.contract.id", "documentation.business", "j7m.contract.version", "1",
        "j7m.qualifier.fingerprint", ManagedQualifier.fingerprint(List.of())), null, null);
var registrationB = b.export("business", serviceB, Business.class, options);
```

Here `b` is the server runtime named `b`, and `serviceB` is its `Service("b")` instance with the counter shown above. A caller runtime named `client` shares the tutorial registry store. The host connects periodic discovery to actual gRPC and selects a finite named policy:

```java title="Public live-binding assembly"
var source = new PolledBindingCandidateSource(client);
var bindings = new LiveServiceBindings(source, new RuntimeBindingConnector(client),
        (request, ref) -> true, "client", Map.of("tutorial", new BindingPolicy(
        BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.WAIT,
        Duration.ofSeconds(2), Duration.ofMillis(30), Duration.ofMillis(300),
        Duration.ofSeconds(2), 16)), () -> Instant.now().plusSeconds(2));
var request = new ServiceBindingRequest<>(Business.class, "business",
        "documentation.business", 1, List.of(), "default", BindingRefresh.ON_FAILURE);
var sticky = bindings.open(request, "tutorial");
```

The tutorial's admission callback allows its own local replicas; a production host must replace it with real contract, authority and readiness admission. A second request differing only in `BindingRefresh.ON_TOPOLOGY_CHANGE` creates `moving`. The program owns and closes registrations, source, bindings and runtimes with `try`-with-resources. The full runnable file supplies that surrounding lifecycle.

The program calls that operation once through the sticky binding. After `a` appears and `b` closes, only a read-only `identify()` probe is used to observe connection failure and allow subsequent selection; the business operation is never retried as a probe. A bounded binding watch waits for `a`, then the final counters must still be `b=1` and `a=0`.

```shell
./gradlew -I documentation/examples/compile.init.gradle documentationRunFailover
```

Expected output:

```text
FailoverExample OK: sticky b, topology a, failover a; effects b=1 a=0 (no replay)
```

This small application uses three runtimes and actual gRPC in **one JVM**, sharing an embedded registry store. It uses development transport security for the isolated tutorial. Configure reachable endpoints and network discovery for an independently hosted deployment; the tutorial does not claim cross-host invocation with the built-in loopback providers.
