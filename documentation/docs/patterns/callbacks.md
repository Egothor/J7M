# Callbacks and bidirectional collaboration

**Use case:** a remote service reports progress or a completed result to a caller-owned listener. The callback is another remote reference with its own owner and security boundary.

=== "With annotations"

    ```java
    @Callback
    public interface ProgressListener {
        void changed(String state);
    }

    public interface ImportService {
        String start(String source, @Callback ProgressListener listener);
    }
    ```

    The markers describe reference intent. The hosting/export path still controls actual callback publication and authorization.

=== "Explicit API"

    ```java
    ProgressListener listener = state -> received.add(state);
    String run = importer.start("catalog", listener);
    ```

    In a configured runtime invocation, the supported reference codec/export path carries the listener as a remote reference. The complete `RuntimeExample` demonstrates an actual gRPC callback rather than serializing a lambda object.

## Ownership and deployment

Keep the caller runtime and callback owner alive while the remote application may use the reference. Advertise a reachable return address. A network that permits only frontend-to-server connections may need explicit routing for callbacks; a callback annotation cannot change firewall rules.

Treat callback input as remote input. Apply the same authorization, deadline, payload and redaction rules as any exposed service. Avoid assuming callbacks arrive once or in global order; include correlation and version fields when the application needs them.

## Failure behavior

A successful service start does not guarantee all future callback deliveries. The listener may disappear, its lease may expire or a connection may fail after entry. Use durable events or a queryable durable job result when the caller must recover after restart.

## Worked scenario: display a result through a caller-owned listener

The client wants both the immediate reply and a callback. Its listener is a public interface:

```java
public interface Listener {
    void onResult(String value);
}
```

The server uses that interface without accessing the client's state:

```java
public String greet(String name, Listener listener) {
    String result = "Hello, " + name;
    listener.onResult(result);
    return result;
}
```

The client in the runnable manual application records incoming notifications:

```java
var received = new CopyOnWriteArrayList<String>();
Listener callback = value -> {
    received.add(value);
    System.out.println("Callback: " + value);
};
GreetingApi greeting = client.lookup(
        "greetings", GreetingApi.class, LookupOptions.defaults());
String reply = greeting.greet("Ada", callback);
System.out.println("Reply: " + reply);
```

The supported argument/reference path carries a callable callback identity, not a serialized list or lambda object graph. Output contains `Callback: Hello, Ada` and `Reply: Hello, Ada`. The server calls the listener before returning, so this implementation receives the callback first.

Keep the callback owner and client runtime alive until collaboration finishes. A longer-lived import should expose a durable job result or event subscription if it must outlive this caller. An unavailable listener cannot recover a missed notification from a durable mailbox.

```shell
./gradlew -I documentation/examples/compile.init.gradle documentationRunRuntime
```

This complete application uses actual gRPC between two runtimes in one JVM with development security. See [network deployment](../deployment/network-storage.md) before deploying independently hosted callers.
