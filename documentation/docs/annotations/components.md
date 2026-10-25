# Components and dependency injection

These annotations describe a finite, generated dependency graph. The managed processor requires public concrete top-level component classes and supported public access paths. Host resources and remote services are separate from ordinary local dependencies.

## @J7mComponent {#j7mcomponent}
Declares a component included in the generated component index. **Target:** type. **Attribute:** `name = ""` by default. An empty name is an unnamed component; a nonempty name participates in exact selection.

```java
@J7mComponent(name = "regional")
public final class RegionalTaxRates {
    public RegionalTaxRates() { }
    public long tax(long amountMinor) { return amountMinor / 5; }
}
```

The host must load and admit the generated index. A component annotation does not start a classpath scan. Without it, construct `RegionalTaxRates` and pass it to its consumer explicitly.

## @Inject {#inject}
Requests dependency resolution. **Targets:** constructor, field, parameter. **Attributes:** `refresh = BindingRefresh.NEVER`, `policy = "default"`.

```java
@Inject
public InvoiceService(@Named("regional") RegionalTaxRates rates) {
    this.rates = rates;
}
```

Prefer constructor injection. Generated field injection requires a supported public, nonfinal, nonstatic field. Default injection resolves a local dependency; opting into a supported dynamic named binding requires a host binding factory and policy. Refresh is explained under [ServiceRef](#serviceref).

## @Named {#named}
Adds an exact string name. **Targets:** field, parameter, type. **Required attribute:** `value`.

```java
@Inject
public ReceiptPrinter(@Named("compact") ReceiptFormat format) {
    this.format = format;
}
```

The managed graph does not silently substitute an unnamed value for a missing named dependency. A type-level `@Named` and `@J7mComponent(name=...)` must agree when both names are nonempty.

## @Qualifier {#qualifier}
Marks an application-defined qualifier annotation. **Target:** annotation type. **Attributes:** none.

```java
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.PARAMETER, ElementType.FIELD})
public @interface Region {
    String value();
}
```

Use `@Region("eu")` on the provider and requested dependency. Matching uses the qualifier's binary type and complete member values, including defaults. Qualifiers are selection data, not caller authority.

## @Produces {#produces}
Declares a factory product in the generated graph. **Target:** method. **Attributes:** `name = ""`, `ownership = Produces.Ownership.OWNED`.

```java
@Produces(name = "clock", ownership = Produces.Ownership.BORROWED)
public Clock clock() {
    return hostClock;
}
```

An owned `AutoCloseable` result is disposed by its resolving scope. A borrowed result remains owned by its supplier. The factory receiver itself is a declared dependency with its own lifetime. Explicit Java construction and `try`/`finally` provide the corresponding non-annotated ownership model.

## @Scope {#scope}
Chooses managed object lifetime. **Targets:** type and producer method. **Attribute:** `value = Scope.Kind.ACTIVATION`; alternatives are `CALL` and `PROTOTYPE`.

```java
@J7mComponent
@Scope(Scope.Kind.CALL)
public final class InvoiceBuffer {
    public InvoiceBuffer() { }
}
```

CALL objects belong to an actual owned call. PROTOTYPE objects belong to the resolving call or activation. ACTIVATION is local to a deployed activation, not a distributed singleton. Use a supported `ManagedProvider<T>` rather than directly capturing a CALL object in an activation object.

## @J7mResource {#j7mresource}
Selects an explicitly registered host capability. **Targets:** field and parameter. **Attributes:** `value = ""`, `refresh = BindingRefresh.NEVER`, `policy = "default"`.

```java
@Inject
public OrderPublisher(@J7mResource("orders") ReplicatedQueue queue) {
    this.queue = queue;
}
```

The optional application-distributed adapter supports registered resource, partition, queue and execution clients. A name is not an endpoint or a credential. Fixed raw-group registrations support stable binding; supported dynamic partition registrations can refresh ownership without replaying an application operation.

## @ServiceRef {#serviceref}
Requests a stable binding to an ordinary business interface. **Targets:** field and parameter. **Attributes:** required `name`; `contractId = ""`, `contractVersion = 1`, `refresh = BindingRefresh.NEVER`, `policy = "default"`.

```java
@Inject
public Checkout(@ServiceRef(name = "pricing", contractVersion = 1,
        refresh = BindingRefresh.ON_FAILURE, policy = "pricing-client")
        Pricing pricing) {
    this.pricing = pricing;
}
```

An empty contract ID uses the requested interface's binary name. The host supplies discovery, readiness/authorization admission and the named binding policy. `ON_FAILURE` keeps a healthy selected incarnation; `ON_TOPOLOGY_CHANGE` re-evaluates newer authoritative snapshots. Replacing a target affects subsequent calls and never replays an entered business invocation. `BindingRefresh` is an enum, not an annotation.

## Example: inject one named implementation

An invoice calculator needs regional rates. The application contains the `RegionalTaxRates` component declared above and this consumer in a separate source file:

```java
@J7mComponent
public final class InvoiceService {
    private final RegionalTaxRates rates;

    @Inject
    public InvoiceService(@Named("regional") RegionalTaxRates rates) {
        this.rates = rates;
    }

    public long total(long amountMinor) {
        return amountMinor + rates.tax(amountMinor);
    }
}
```

Once the host admits the generated component index, `total(1000)` returns `1200`. Missing or ambiguous selection fails activation instead of choosing an arbitrary component. With explicit Java construction the equivalent is `new InvoiceService(new RegionalTaxRates())`; J7M removes that repeated assembly.

Use `@Region("eu")` instead of a string name when selection needs an application-defined qualifier. Put the same complete qualifier values on the provider and the requested dependency. A `@Produces` method can supply a shared clock; its BORROWED ownership leaves closing to the host. A CALL-scoped `InvoiceBuffer` must be obtained through a provider during the call, rather than captured permanently by this activation-scoped service.

For the `Checkout` binding above, suppose JVM A initially provides pricing and JVM B later joins. `ON_FAILURE` keeps A while healthy, then resolves an eligible replacement for a subsequent call after A becomes unavailable. `ON_TOPOLOGY_CHANGE` allows reevaluation on a newer authoritative topology. Neither repeats a price operation that already entered A. A `@J7mResource` selects a registered middleware resource rather than an ordinary local component. See [dependency injection](../programming/injection.md) and [discovery refresh](../patterns/discovery-refresh.md).
