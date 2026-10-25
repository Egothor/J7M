# Lifecycle and health annotations

Managed hosting validates lifecycle methods before construction and coordinates them with service admission. Bare `ManagedContainer` use has explicit lifecycle phases; merely adding annotations to a manually created object does not execute them.

## @Initialize {#initialize}
Marks a no-argument initialization method. **Target:** method. **Attributes:** none.

```java
@Initialize
public void initialize() {
    catalog.loadInitialState();
}
```

Prepare application state before publication. Do not retain a caller's invocation context in initialization state. Explicit embedding calls the initialization operation under its own controlled lifecycle.

## @Startup {#startup}
Marks a no-argument startup method after initialization. **Target:** method. **Attributes:** none.

```java
@Startup
public void start() {
    acceptingOrders = true;
}
```

Startup is not a constructor. The host's readiness and admission transition still controls when external work can enter.

## @ReadinessCheck {#readinesscheck}
Marks a readiness callback. **Target:** method. **Attributes:** none.

```java
@ReadinessCheck
public boolean ready() {
    return catalogLoaded;
}
```

Use a bounded, side-effect-free check. Readiness concerns admitting new work; it is not proof that all existing work has finished.

## @HealthCheck {#healthcheck}
Marks a health callback. **Target:** method. **Attributes:** none.

```java
@HealthCheck
public boolean healthy() {
    return !fatalStorageFailure;
}
```

Return only safe health information. A successful check does not grant authorization or guarantee a remote dependency will stay available for the next call.

## @Drain {#drain}
Marks the application drain callback. **Target:** method. **Attributes:** none.

```java
@Drain
public void drain() {
    acceptingOrders = false;
}
```

The host stops admission, invokes the drain hook, then waits for owned physical work before final shutdown and resource release. A callback that flips an application flag, or a public drain operation returning DRAINING, is not proof that every worker has exited. A timed-out drain retains the application resources needed by remaining work.

## @Shutdown {#shutdown}
Marks a no-argument final shutdown callback. **Target:** method. **Attributes:** none.

```java
@Shutdown
public void stop() {
    applicationOwnedCache.close();
}
```

Make cleanup idempotent. Close only application-owned resources; borrowed host clients remain their owner's responsibility. The hook does not control JVM shutdown.

## Example: publish a prepared catalog

This component makes the lifecycle transitions observable without external collaborators:

```java
@J7mComponent
public final class CatalogLifecycle {
    private volatile boolean loaded;
    private volatile boolean accepting;
    private volatile boolean stopped;

    public CatalogLifecycle() { }

    @Initialize
    public void initialize() { loaded = true; }

    @Startup
    public void start() { accepting = true; }

    @ReadinessCheck
    public boolean ready() { return loaded && accepting && !stopped; }

    @HealthCheck
    public boolean healthy() { return !stopped; }

    @Drain
    public void drain() { accepting = false; }

    @Shutdown
    public void stop() { stopped = true; loaded = false; }
}
```

Managed activation initializes and starts the component before admitting its services. Readiness then becomes true. During drain, readiness becomes false while health remains true: the instance can finish admitted work but should receive no new work. Final shutdown marks it stopped after the host's physical-work ownership checks.

Replace the initial flag assignment with bounded catalog loading and close actual application-owned resources in shutdown. Do not manually call these hooks from business methods to simulate host admission. See [application lifecycle](../programming/lifecycle.md) and [deployment lifecycle](../deployment/lifecycle.md).
