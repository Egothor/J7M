# Remote-object streams

`StreamingTransportProvider` is an optional transport extension for streams addressed to a concrete `ObjectRef`. It supports server streams, a client item list with an aggregate response, and bidirectional calls with an initial outbound item list. This API is separate from durable queue sessions: stream consumption does not acknowledge persistent work.

## Read a server stream

The following host fragment is adapted from the transport integration scenario. Assume `streaming` is an installed `StreamingTransportProvider`, `target` is an authorized exported reference, and the public interface declares `List<String> serverItems()` returning `List.of("one", "two")`.

```java
Method method = StreamingApi.class.getMethod("serverItems");
try (StreamHandle<Object> stream = streaming.serverStream(
        target, MethodDescriptor.fromMethod(method), List.of(),
        InvocationOptions.defaults(), String.class)) {
    String first = (String) stream.poll(Duration.ofSeconds(2)).orElseThrow();
    String second = (String) stream.poll(Duration.ofSeconds(2)).orElseThrow();
    // first == "one"; second == "two"
}
```

The fragment illustrates the current API and assumes host setup; it is not a standalone program. A timed empty poll means no item arrived within that wait. Check `closed()` and the application's completion protocol rather than treating every empty poll as successful completion. `cancel()` requests cancellation; always close the handle to release owned resources.

## Send items and receive an aggregate

For `String aggregate(List<String> items)`, implemented by joining items with commas:

```java
Method method = StreamingApi.class.getMethod("aggregate", List.class);
Object result = streaming.clientStream(
        target, MethodDescriptor.fromMethod(method),
        List.of(client.encodeValue("a", String.class),
                client.encodeValue("b", String.class)),
        InvocationOptions.defaults());
// result is "a,b".
```

`client` is the configured runtime. `bidirectionalStream` similarly accepts encoded initial items and returns a `StreamHandle<Object>` for inbound items. The current public signature does not expose an indefinitely writable outbound channel; design the application contract around the actual provider capabilities.

## Preserve invocation boundaries

Streaming targets use the same object identity, authorization and server dispatch checks as unary calls. Supported explicit `ObjectRef` items remain references, rather than serialized proxy implementations. A transport that implements only `TransportProvider` does not acquire streaming support automatically.

Bound producer output, item size, buffers and waiting time. Slow consumers must exert backpressure or receive the provider's bounded failure; they must not cause unlimited memory growth. Scheduler-managed producers use `StreamEmitter.emit(item, timeout)` for bounded emission. See [workloads](workloads.md), [streaming and backpressure](../patterns/streaming-backpressure.md), and [invocation semantics](invocation.md).
