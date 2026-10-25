# Dependency injection and ownership

Use constructor injection for required collaborators. It makes activation requirements visible and prevents a half-initialized object from escaping. Managed J7M injection uses finite generated component indexes; it does not scan the classpath for arbitrary classes.

```java
@J7mComponent
public final class InvoiceService {
    private final TaxRates rates;

    @Inject
    public InvoiceService(@Named("regional") TaxRates rates) {
        this.rates = rates;
    }
}
```

Selection uses the requested contract, exact name and complete qualifier set. A missing or ambiguous dependency fails activation. Custom qualifier annotations use `@Qualifier` and runtime retention; their member values participate in matching.

## Choose a lifetime deliberately

| Scope | Identity | Resource owner |
| --- | --- | --- |
| `ACTIVATION` | One instance per container activation | Activation |
| `CALL` | One instance per explicitly owned call | Call scope |
| `PROTOTYPE` | New instance per resolution | Resolving activation or call |

`ACTIVATION` does not mean a cluster-wide singleton. Two deployed JVMs have independent activation objects.

```java
@J7mComponent
@Scope(Scope.Kind.CALL)
public final class RequestAccumulator implements AutoCloseable {
    @Override public void close() { /* release call-owned resources */ }
}
```

An activation object must not directly capture a CALL dependency. Inject a `ManagedProvider<T>` when a value must be resolved from the actual current owned call. A provider defers resolution; it is not a transferable invocation context.

## Producers and borrowed resources

`@Produces` declares a generated factory product. Ownership is explicit: an owned `AutoCloseable` product is disposed by its scope; a borrowed result remains the supplier's responsibility. Closing a borrowing container must not close a host-owned connection pool or native group client.

## Explicit assembly

Without annotations, construct the collaborators in Java and pass them to the application. If you use the managed container directly, load the explicitly declared `ManagedComponentIndex` providers and call `ManagedContainer.activate(indexes)`. The generated indexes are trusted application code and must come from the approved application artifact.

The container validates the graph before construction. Cycles, conflicting names, unsupported metadata and invalid scope capture are rejected rather than repaired by an implicit fallback.

## Refresh is a separate contract

Default `@Inject` means local dependency injection. For a discoverable business interface, use `@ServiceRef` or the supported named dynamic binding form. A stable proxy may replace its selected target according to its refresh policy; a normal injected object is not rewritten concurrently. See [discovery and refresh](../patterns/discovery-refresh.md).

## Assemble and use a component

Here is a small complete business pair. Place each public class in its own source file and compile with J7M's annotation processor:

```java
@J7mComponent
public final class GreetingFormatter {
    public String format(String name) { return "Hello, " + name; }
}

@J7mComponent
public final class GreetingComponent {
    private final GreetingFormatter formatter;
    @Inject
    public GreetingComponent(GreetingFormatter formatter) {
        this.formatter = formatter;
    }
    public String greet(String name) { return formatter.format(name); }
}
```

The embedding application's entry point activates the generated indexes and retrieves the business object:

```java
try (var container = ManagedContainer.activate(List.of(
        new GreetingFormatter__J7mComponentIndex(),
        new GreetingComponent__J7mComponentIndex()))) {
    GreetingComponent greetings = container.get(
            GreetingComponent.class, "", List.of());
    System.out.println(greetings.greet("Ada")); // Hello, Ada
}
```

`ManagedContainer` is in `org.j7m.middleware.application.components`; `List` is `java.util.List`. The generated classes are in the same package as their component. The activation owns both objects. An absent formatter is rejected before construction; the application does not receive a partially wired greeting service.
