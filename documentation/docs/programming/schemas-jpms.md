# Data schemas, generated adapters and JPMS

Distributed data contracts must be explicit. J7M's generated schema path supports public, non-generic records with stable type and field identities.

```java
@SchemaType(id = "orders.Order", version = 1)
public record Order(
        @WireField(id = 1) String orderId,
        @WireField(id = 2) long amountMinor) { }
```

The wire identity is independent of the Java component name. Every component requires a `@WireField`; primitive components must be required. Optional scalar defaults are declared explicitly and validated by the processor. Nested values must belong to the allowed schema set.

## Compile with the processor

The JSR 269 processor generates direct component, hosted callback and schema adapters. Generated access avoids a blanket `opens` of implementation packages. Public business contracts must still be accessible across their actual module boundary.

A generated adapter is executable application code. Signing and admitting an artifact remain host responsibilities. A schema annotation does not permit arbitrary class loading or construction of a class named by incoming data.

## Keep class-loader identity intact

Two applications can contain the same binary type name. Their classes and generated schemas are still different identities. Use the actual admitted application module and its generated providers; do not preload the application into a second URL class loader to make a registry entry.

Hosted queue, job and saga adapters resolve app-owned schemas from their actual definition/index. An unrelated global host registry is not a substitute for the application's class identity.

## Explicit alternative

Applications using the lower-level codec APIs can install an explicit `SchemaRegistry` and supported schema definitions. This retains the same allowlist, version and size obligations. It does not serialize arbitrary object graphs or Java stack frames.

## Encode an application value

After compiling the `Order` record above, the processor supplies `Order__J7mSchema` in its package. A host-side codec can register just this admitted schema:

```java
var schemas = new SchemaRegistry(new Order__J7mSchema().schemas());
var codec = new DefaultValueCodec(schemas);
var order = new Order("order-42", 1250L);
var frame = codec.encode(order, Order.class, null);
Order received = (Order) codec.decode(frame, Order.class, null);
System.out.println(received.orderId()); // order-42
```

`SchemaRegistry` is in `org.j7m.middleware.codec.api`; `DefaultValueCodec` is in `org.j7m.middleware.codec.defaultcodec`. This record contains only scalar values, so it needs no reference-conversion context. When a middleware invocation carries the value, its runtime supplies the codec context and admitted schema registration. An empty schema registry rejects this record instead of treating an incoming class name as permission to deserialize it.

See [serialization annotations](../annotations/serialization.md).
