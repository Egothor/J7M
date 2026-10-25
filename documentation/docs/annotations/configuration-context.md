# Configuration and current-context annotations

Configuration bindings read a safe application view supplied by the host. Context bindings describe the current application or invocation; they are not a replacement for resource ownership.

## @ConfigValue {#configvalue}
Selects one application configuration key. **Targets:** field, parameter and record component. **Attributes:** required `value`; `defaultValue = ""`, `required = false`, `refresh = false`.

```java
@Inject
public ReceiptFormatter(@ConfigValue(value = "receipts.currency",
        defaultValue = "EUR") String currency) {
    this.currency = currency;
}
```

A required missing value fails activation. Managed primitive injection needs a required value or an explicit nonempty default; it does not silently invent zero or false. For live replacement, use the supported provider form with `refresh=true` and an installed `LiveConfiguration` owner.

## @ConfigProperties {#configproperties}
Groups configuration into a supported public immutable record. **Target:** type. **Attributes:** `prefix = ""`, `refresh = false`.

```java
@ConfigProperties(prefix = "pricing")
public record PricingSettings(String currency, int scale) { }
```

The host validates one complete configuration snapshot. Direct injection retains its initial value. A refreshable provider may observe a newer validated record; arbitrary component fields are not rewritten in place.

## @CurrentApplication {#currentapplication}
Selects the safe application context. **Targets:** field and parameter. **Attributes:** none.

```java
@Inject
public ApplicationReporter(@CurrentApplication ApplicationContext application) {
    this.application = application;
}
```

The application context exposes the public application capabilities and safe views supported by its API. It does not expose arbitrary class-loader or registry internals.

## @CurrentInvocation {#currentinvocation}
Selects invocation-local context. **Targets:** field and parameter. **Attributes:** none.

```java
@Inject
public RequestReporter(
        @CurrentInvocation ManagedProvider<InvocationContext> invocation) {
    this.invocation = invocation;
}
```

Resolve the provider inside an actual owned invocation. Do not retain a value across calls or expect a new thread to inherit it. The host clears invocation state after completion.

## @CurrentPrincipal {#currentprincipal}
Selects the current authenticated principal. **Targets:** field and parameter. **Attributes:** none.

```java
@Inject
public CallerReporter(@CurrentPrincipal ManagedProvider<Principal> principal) {
    this.principal = principal;
}
```

Use the J7M principal type from the current security API, not an application-supplied username. An injected principal is a view of the host's authenticated context; it does not let the application choose another caller's authority.

## Example: a configured receipt format

The host supplies `receipts.currency=EUR` in its application configuration view. This complete component stores the initial immutable setting:

```java
@J7mComponent
public final class ReceiptFormatter {
    private final String currency;

    @Inject
    public ReceiptFormatter(@ConfigValue(value = "receipts.currency",
            defaultValue = "EUR") String currency) {
        this.currency = currency;
    }

    public String format(long amountMinor) {
        return amountMinor + " minor units " + currency;
    }
}
```

`format(1250)` returns `1250 minor units EUR`. With no supplied value the declared default gives the same result. Changing host configuration does not rewrite this field. To observe validated replacement snapshots, request the supported refreshable provider form and resolve it at the point of use.

For `PricingSettings`, the host supplies the grouped `pricing.currency` and `pricing.scale` keys; injecting the record keeps the pair from one validated snapshot. Inject `@CurrentApplication ApplicationContext` for application-level reporting. Resolve the providers for `@CurrentInvocation` and `@CurrentPrincipal` only inside the currently owned invocation; a request from another caller gets its own context, never the previous caller's cached value.

Without annotations, pass settings to the constructor and caller context as explicit method arguments. See [configuration and context](../programming/configuration-context.md).
