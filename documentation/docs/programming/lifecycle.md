# Application lifecycle and physical drain

A managed deployment separates construction from admission. Dependencies, configuration, policies and generated adapters must be valid before a service becomes available to callers.

```java
@J7mComponent
public final class CatalogLifecycle {
    @Initialize public void initialize() { /* prepare application state */ }
    @Startup public void start() { /* finish startup */ }
    @ReadinessCheck public boolean ready() { return true; }
    @Drain public void drain() { /* stop accepting application-owned work */ }
    @Shutdown public void stop() { /* release resources after drain */ }
}
```

This is a callback-shape fragment, not a complete deployment. The managed host performs the transition ordering and validates callback shapes before construction. A manually created `new CatalogLifecycle()` does not cause these methods to execute automatically.

## Health and readiness answer different questions

A health check reports whether the component can function. Readiness governs whether new application work should be admitted. Avoid expensive checks, mutable business effects or disclosure of secrets in either callback. Host policy still decides how a check affects publication and administration.

## Logical completion is not physical exit

A caller may time out while Java code or tracked child work is still running. J7M retains the owned CALL, component resources, permits and application class loader until physical work and finalizers finish. Cancelling a future does not prove that an external effect was undone.

When drain cannot finish within its budget, treat the application as draining rather than stopped. Releasing its class loader or running final shutdown while a callback still uses it would break ownership. Uncooperative code can therefore retain bounded capacity; the middleware does not claim to force arbitrary Java to terminate safely.

## Explicit lifecycle

When embedding without lifecycle annotations, the host performs the same responsibility explicitly: prepare dependencies, publish only after readiness, stop admission, await real completion, then close exports and owned resources. Use structured `try`/`finally` or `AutoCloseable` ownership rather than relying on JVM shutdown hooks.

## Drive lifecycle from an embedded host

The following application fragment uses the `GreetingComponent` and `GreetingFormatter` included below. The generated index classes come from compiling these components with J7M's annotation processor. `ManagedLifecycle` is in `org.j7m.middleware.application.api`.

```java
try (var container = ManagedContainer.activate(List.of(
        new GreetingFormatter__J7mComponentIndex(),
        new GreetingComponent__J7mComponentIndex()))) {
    container.lifecycle(ManagedLifecycle.STARTUP);
    try {
        var greetings = container.get(GreetingComponent.class, "", List.of());
        System.out.println(greetings.greet("Ada"));
    } finally {
        container.lifecycle(ManagedLifecycle.DRAIN);
        container.lifecycle(ManagedLifecycle.SHUTDOWN);
    }
}
```

```java title="GreetingFormatter.java"
--8<-- "documentation/examples/src/main/java/org/j7m/documentation/GreetingFormatter.java"
```

```java title="GreetingComponent.java"
--8<-- "documentation/examples/src/main/java/org/j7m/documentation/GreetingComponent.java"
```

The business method returns `Hello, Ada` while started and rejects use after shutdown. This small synchronous component has no child work; a hosted asynchronous service additionally needs the host's physical-drain protocol before final shutdown. See [deployment lifecycle](../deployment/lifecycle.md).
