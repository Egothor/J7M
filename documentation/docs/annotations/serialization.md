# Serialization annotations

The generated schema path describes explicit data, not arbitrary Java object graphs. Application constructors/accessors remain trusted code, and the host admits the actual application schema providers.

## @SchemaType {#schematype}
Declares an allowlisted public, non-generic record. **Target:** type. **Required attributes:** `id`, `version`.

```java
@SchemaType(id = "orders.Payment", version = 1)
public record Payment(
        @WireField(id = 1) String orderId,
        @WireField(id = 2) long amountMinor) { }
```

The ID follows the public schema identifier grammar and the version is positive. Generated providers perform direct access without reflective opening of implementation packages. A registry's version rules still apply; an annotation is not a data-migration tool.

## @WireField {#wirefield}
Declares a stable numeric field identity. **Target:** record component. **Attributes:** required `id`; `required = true`, `hasDefault = false`, `defaultValue = ""`.

```java
@SchemaType(id = "orders.Note", version = 1)
public record OrderNote(
        @WireField(id = 1) String orderId,
        @WireField(id = 2, required = false,
                   hasDefault = true, defaultValue = "none") String note) { }
```

Every component needs a field ID. Primitive components must be required. Scalar defaults are parsed and validated at compilation; nested schemas and byte arrays cannot use a nonnull scalar text default. An omitted optional field with no declared default yields null according to the supported schema contract.

## Example: exchange a payment value

The `Payment` record above is an application data contract, not a remote object. A service can accept and return it directly:

```java
public interface PaymentValidation {
    Payment normalize(Payment payment);
}

@J7mService(name = "payment-validation")
public final class PaymentValidationService implements PaymentValidation {
    public PaymentValidationService() { }

    @RemoteMethod
    public Payment normalize(Payment payment) {
        if (payment.amountMinor() < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
        return new Payment(payment.orderId(), payment.amountMinor());
    }
}
```

Compile the record with the schema processor and package/admit its generated schema provider on the participating application paths. A call with `new Payment("42", 1250)` returns a decoded payment with the same field values in the receiving application's allowed class identity. It does not copy an arbitrary implementation graph.

For `OrderNote`, an omitted optional wire field 2 decodes to the declared `"none"` default. Java construction still requires its normal constructor arguments; wire defaults do not rewrite `new OrderNote("42", null)`. Stable numeric IDs identify fields independently of source parameter names. See [schemas and JPMS](../programming/schemas-jpms.md).
