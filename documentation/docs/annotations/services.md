# Applications, services and export boundaries

Service declarations describe what an approved application intends to expose. Actual deployment, transport configuration, publication and authorization remain controlled by its host.

## @J7mApplication {#j7mapplication}
Declares application coordinates. **Target:** type. **Required attributes:** `group`, `name`, `version`, all strings.

```java
@J7mApplication(group = "example", name = "orders", version = "1.0")
public final class OrdersApplication { }
```

Coordinates identify the application; they do not sign a bundle or register host policies. Use the application platform's manifest and deployment APIs for the explicit alternative.

## @J7mService {#j7mservice}
Describes a managed service. **Target:** type. **Attributes:** required `name`; `exported = true`, `transport = "default"`, `contractId = ""`, `contractVersion = 1`.

```java
@J7mService(name = "pricing", contractId = "example.Pricing", contractVersion = 1)
public final class PricingService implements Pricing {
    public PricingService() { }
    public long price(String product) { return 100; }
}
```

The selected transport must exist on the host. For explicit publication, call `MiddlewareRuntime.export` or prepare a group with `prepareExport` and commit only after readiness. Merely constructing this class performs no export.

## @RemoteService {#remoteservice}
Marks a type's intent to be used as a remote service contract or implementation. **Target:** type. **Attributes:** none.

```java
@RemoteService
public interface Pricing {
    long price(String product);
}
```

This is descriptor metadata. It does not create a listener, authorize a caller or bypass the host's export validation.

## @RemoteMethod {#remotemethod}
Marks a method in a remote-service declaration. **Target:** method. **Attributes:** none.

```java
public interface Pricing {
    @RemoteMethod
    long price(String product);
}
```

The actual export still names a supported public interface. The method remains subject to deadline, codec, transport and server authorization checks. Explicit API export uses the interface contract without requiring this descriptive marker.

## @NoRemoteExport {#noremoteexport}
Declares that the annotated site must not be treated as an export capability. **Targets:** type, method and parameter. **Attributes:** none.

```java
@NoRemoteExport
public interface LocalKeyProvider {
    byte[] signingKey();
}
```

Use this declaration in a supported descriptor/validation path to make an inappropriate remote boundary fail visibly. It is not a JVM security sandbox; do not publish secret-bearing objects through an unrelated manual export path and expect a marker alone to protect them.

## @LocalOnly {#localonly}
Records a local boundary for a type or method. **Targets:** type and method. **Attributes:** none.

```java
@LocalOnly
public final class LocalFormattingRules {
    public String currencySymbol() { return "€"; }
}
```

Descriptor validation can reject inconsistent export combinations. A local method is not automatically available to a remote peer. Keep the actual public export interface small and explicit.

## Example: expose a small pricing contract

Put `Pricing` and `PricingService` above in separate public source files. Mark the implementation method `@RemoteMethod` if the application's descriptor conventions require it. Compile with the J7M processor, package the generated metadata and let the host admit and publish the application.

A client using an already configured runtime calls:

```java
Pricing pricing = client.lookup("pricing", Pricing.class, lookupOptions);
long amountMinor = pricing.price("book");
// amountMinor is 100 with the implementation above.
```

Here `client` is the host-owned runtime and `lookupOptions` its permitted lookup configuration. No listener is started by this fragment; the server must already be deployed with the selected transport and authorization policy. `@J7mApplication` supplies the application's coordinates, while `@RemoteService` describes the public remote contract.

Keep `LocalKeyProvider` and `LocalFormattingRules` on the server side. Their `@NoRemoteExport`/`@LocalOnly` declarations document and support validation of that boundary; they do not belong in `Pricing` method results. An unauthorized call is rejected even if the caller has obtained the object reference. See [services and references](../programming/services.md) for explicit publication and returned child objects.
