# Services and remote references

A remote service has a public Java interface, a logical service name and an exported instance. Its `ObjectRef` identifies a concrete runtime/object incarnation and carries transport routing information. A logical name selects a service; a concrete reference selects an object.

## Define a small public contract

```java
public interface Greeting {
    String greet(String name);
}
```

Prefer data values or explicitly supported references in method signatures. Keep transport classes, database connections, executors and host administration capabilities outside the business contract.

=== "With annotations"

    ```java
    @J7mService(name = "greeting", exported = true)
    public final class GreetingService implements Greeting {
        @RemoteMethod
        public String greet(String name) {
            return "Hello, " + name;
        }
    }
    ```

    The application platform consumes generated metadata and performs controlled publication. The annotation alone does not start a listener or make a manually constructed object discoverable.

=== "Explicit API"

    ```java
    try (ObjectRegistration<Greeting> registration = runtime.export(
            "greeting", name -> "Hello, " + name,
            Greeting.class, exportOptions)) {
        Greeting greeting = runtime.lookup(
                "greeting", Greeting.class, lookupOptions);
        String reply = greeting.greet("Ada");
    }
    ```

    `runtime`, `exportOptions` and `lookupOptions` are host-configured values. The registration owns the export; the runtime owns its transport and registry integration.

## Publish only when ready

For an application with several services, use prepared exports and commit their publication as a group. `prepareExport` does not make the object discoverable. On failed activation, close every uncommitted prepared handle. The application platform performs this preparation and admission coordination for a managed deployment.

## References are not credentials

Possessing an `ObjectRef` does not authorize a call. Server-side authorization remains authoritative. Do not put credentials in reference metadata. Returning a remote reference also creates a lifecycle obligation: the owning runtime must keep the referenced object valid for its advertised lifetime.

Use `runtime.reference(ref, Greeting.class, proxyOptions)` when you intentionally need one exact object rather than a new selection by service name. Exact-reference connection does not turn a failed business invocation into a retryable operation.

## Return a live child object

A service can return a supported remote reference through an interface-typed result. The receiver gets a proxy for that concrete child, rather than a copy of its implementation. This host fragment makes the export and ownership explicit:

```java
public interface Session {
    String id();
}
public interface SessionDirectory {
    Session current();
}

// server, client and their options are configured by the host.
try (ObjectRegistration<Session> child = server.export(
        "session-42", () -> "42", Session.class, exportOptions)) {
    Session childProxy = server.reference(
            child.ref(), Session.class, proxyOptions);
    try (ObjectRegistration<SessionDirectory> directory = server.export(
            "sessions", () -> childProxy,
            SessionDirectory.class, exportOptions)) {
        SessionDirectory remote = client.reference(
                directory.ref(), SessionDirectory.class, proxyOptions);
        Session session = remote.current();
        String id = session.id(); // "42", called on the exported child.
    }
}
```

This source-checked fragment omits host assembly and imports. The codec recognizes the middleware proxy's remote identity, encodes an `ObjectRef`, checks the expected result interface on receipt and constructs the client proxy. It does not serialize the proxy implementation or automatically export an arbitrary returned POJO. The child registration must outlive calls through the returned reference.

```mermaid
sequenceDiagram
    participant C as Client
    participant D as Directory object
    participant S as Exported session
    C->>D: current()
    D-->>C: ObjectRef for session
    Note over C: Validate expected interface; create proxy
    C->>S: id()
    S-->>C: "42"
```

An interface mismatch is rejected; an expired or closed child is no longer a healthy target. Later calls still undergo authorization. Keep returned interfaces narrow and avoid exposing administration capabilities through ordinary business results.

See [request–reply](../patterns/request-reply.md) and [callbacks](../patterns/callbacks.md) for application patterns.
