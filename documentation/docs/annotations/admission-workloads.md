# Admission and workload declarations

These annotations make workload intent visible to descriptor validation and installed host policies. A priority or rate-limit label by itself does not install a scheduler, create capacity or extend a deadline.

## @Backpressure {#backpressure}
Declares concurrent admission metadata. **Targets:** type and method. **Attribute:** `maxConcurrent = -1` means unspecified.

```java
@Backpressure(maxConcurrent = 8)
String renderReceipt(String orderId);
```

The host must consume and enforce a supported limit. Treat an overload rejection as an expected application outcome; do not create an unbounded fallback executor.

## @BackpressureAware {#backpressureaware}
Marks code as honoring backpressure signals. **Targets:** method and type. **Attributes:** none.

```java
@BackpressureAware
public void consumeBoundedBatch() { }
```

The marker documents a cooperation contract. Actual stream credits, bounded buffers and demand belong to the relevant API. A method carrying this marker can still be incorrectly written; it is not a proof that its buffering is bounded.

## @Priority {#priority}
Declares an integer priority. **Targets:** method and type. **Required attribute:** `value`.

```java
@Priority(5)
String interactiveSearch(String query);
```

The selected workload policy defines whether and how it uses this metadata. Do not assume a global ordering or starvation guarantee. Explicit workload submission uses its supported task/options API.

## @RateLimited {#ratelimited}
Declares a rate-limit scope. **Targets:** method and type. **Attribute:** `value = ""`.

```java
@RateLimited("public-search")
String search(String query);
```

The host supplies the actual policy, counter scope and rejection behavior. An empty scope is the default declaration, not a promise of unlimited or cluster-wide rate accounting.

## @ResourceRequirement {#resourcerequirement}
Declares a requirement expression or policy key. **Targets:** method and type. **Required attribute:** `value`.

```java
@ResourceRequirement("image-processing")
String resize(String imageId);
```

Use a stable public policy identifier. The enforcing host adapter decides which controls are available. The annotation does not reserve operating-system memory or create a container isolation boundary.

## @LongRunning {#longrunning}
Declares long work. **Targets:** method and type. **Attributes:** none.

```java
@LongRunning
@Async
CompletionStage<String> rebuild(String partition);
```

In a managed calling contract, this makes intent explicit; the caller and host still bound execution and cancellation. It does not persist the work. Use `@DurableJob` with its factory when checkpoint recovery is required.

## @Benchmark {#benchmark}
Marks an optional safe latency-sampling method. **Target:** method. **Attributes:** none.

```java
@Benchmark
public String ping() { return "ready"; }
```

Only a configured consumer may use this metadata. Choose a cheap method without business effects. Absence must not prevent ordinary invocation, and this marker is not a replacement for reproducible performance testing.

## Example: bound an interactive search endpoint

This interface separates interactive work from a longer rebuild:

```java
public interface Search {
    @Backpressure(maxConcurrent = 8)
    @RateLimited("public-search")
    @Priority(5)
    @ResourceRequirement("interactive-search")
    String search(String query);

    @LongRunning
    @Async
    CompletionStage<String> rebuild(String partition);

    @Benchmark
    String ping();
}
```

The host must register policies that consume these declarations. With an enforced concurrency limit of eight, a ninth overlapping search is rejected rather than creating an unbounded executor. The application can present a temporary-busy response and let its caller choose a bounded retry. Priority is meaningful only inside the installed scheduler's scope.

Implement `ping()` as a cheap, side-effect-free operation returning `ready`; it samples availability/latency without rebuilding an index. A rebuild still needs a finite deadline and cooperative cancellation, and needs `@DurableJob` instead if restart recovery is required.

A backpressure-aware producer uses a real bounded API rather than just a marker:

```java
@BackpressureAware
public void emitPage(StreamEmitter<String> emitter, List<String> page) {
    for (String item : page) {
        emitter.emit(item, Duration.ofMillis(100));
    }
}
```

The caller supplies a bounded page and scheduler-managed emitter. A slow consumer cannot force indefinite buffering; emission must complete within its supplied capacity wait or follow the emitter's failure contract. See [workload APIs](../programming/workloads.md) and [configuration limits](../configuration/limits-observability.md).
