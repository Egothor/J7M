# Ownership and partitioning annotations

Routing selects an authoritative destination. A lease grants temporary ownership. A fence rejects stale operations at a cooperating resource. Keep these three responsibilities separate.

## @LeaseGuard {#leaseguard}
Selects a host-owned fenced lease policy. **Targets:** type and method. **Attribute:** `value = "default"`.

```java
@LeaseGuard("accounts")
public String revise(@ShardKey String account) {
    ownership.get().write("revision-42", account, "revised");
    return "revised";
}
```

Here `ownership` is the authorized `ManagedProvider<ManagedOwnershipContext>` supplied by the host. The installed ownership policy acquires/renews the lease and exposes a revocable context. Use a stable business revision ID in place of the illustrative `revision-42`. Route protected writes through the supporting fenced resource; the annotation cannot fence an arbitrary file write or external API.

## @LeaderTask {#leadertask}
Selects an ownership profile for leadership-gated work. **Targets:** type and method. **Attribute:** `value = "default"`.

```java
@LeaderTask("catalog-leader")
public void markCatalogRefresh(String refreshId) {
    ownership.get().write(refreshId, "catalog/refresh", "requested");
}
```

The host supplies `ownership` as the current authorized ownership-context provider and establishes leadership under `catalog-leader`. This method writes a protected refresh marker; it does not start a timer or refresh an unrelated external catalog automatically. A stale JVM can still be executing Java after losing its lease; downstream validation, rather than the annotation alone, prevents its protected mutation.

## @PartitionHandler {#partitionhandler}
Declares a pure encoded partition reducer. **Target:** type. **Attributes:** required `resource`; `policy = "default"`, `version = 1`.

```java
@PartitionHandler(resource = "balances", policy = "balances")
public class Balances {
    public Balances() { }
    public int initial() { return 0; }
    public int apply(int state, Adjustment command) { return state + command.delta(); }
    public int query(int state, String query) { return state; }
}
```

The host registers the generated reducer through `ManagedPartitionFactory`. State belongs to the partition, not to a mutable Java instance field. The callback contract must be deterministic, bounded and free of external effects. This is a programming obligation, not a Java sandbox.

## @ShardKey {#shardkey}
Declares the canonical route key. **Targets:** parameter, method and type. **Attribute:** `value = ""`.

```java
@SchemaType(id = "balances.Adjustment", version = 1)
public record Adjustment(@WireField(id = 7) String account,
                         @WireField(id = 8) int delta) { }

@PartitionHandler(resource = "balances", policy = "balances")
@ShardKey("7")
public class RoutedBalances {
    public RoutedBalances() { }
    public int initial() { return 0; }
    public int apply(int state, Adjustment command) { return state + command.delta(); }
    public int query(int state, String query) { return state; }
}
```

Managed lease guards support one scalar parameter with an empty selector. A generated partition reducer can select a required scalar field by its stable decimal `WireField` ID. Reflection paths and expression languages are not evaluated.

## @OrderedBy {#orderedby}
Declares an explicit ordering scope. **Targets:** method and type. **Required attribute:** `value`.

```java
@PartitionHandler(resource = "balances", policy = "balances")
@ShardKey("7")
@OrderedBy("7")
public class OrderedBalances {
    public OrderedBalances() { }
    public int initial() { return 0; }
    public int apply(int state, Adjustment command) { return state + command.delta(); }
    public int query(int state, String query) { return state; }
}
```

For the supported reducer profile, its selector must equal the shard selector. Persisted first-commit tickets establish order within the partition; a key observes a subsequence of that order. This is not global FIFO or client wall-clock invocation order.

## @DataLocality {#datalocality}
Selects a named host endpoint preference. **Targets:** method and type. **Required attribute:** `value`.

```java
@PartitionHandler(resource = "balances", policy = "balances")
@DataLocality("near")
public class NearbyBalances {
    public NearbyBalances() { }
    public int initial() { return 0; }
    public int apply(int state, Adjustment command) { return state + command.delta(); }
    public int query(int state, String query) { return state; }
}
```

Preference is restricted to eligible endpoints inside the current authoritative group. An unavailable preference can fall back within that group and original deadline. A security denial is not bypassed, and locality does not grant ownership.

## Example: route account adjustments to their owner

Use the `Adjustment` schema and `OrderedBalances` reducer above as the application's public data and deterministic state contract. Register that generated reducer with the `balances` host profile. An application adjustment of `("account-42", 5)` selects wire field 7 as its canonical route key; a following `("account-42", -2)` is routed to that key's current authoritative group.

The reducer's calculations are ordinary pure Java:

```java
OrderedBalances reducer = new OrderedBalances();
int initial = reducer.initial();
int credited = reducer.apply(initial, new Adjustment("account-42", 5));
int finalBalance = reducer.apply(credited, new Adjustment("account-42", -2));
// initial = 0; credited = 5; finalBalance = 3.
```

This local calculation explains the business rule. Distribution is provided by the admitted partition factory, request identity and authoritative routing protocol, not by manually constructing the reducer. `@OrderedBy("7")` requests the supported order within that partition. `@DataLocality("near")` can prefer an eligible endpoint of the same group; it cannot send a write to a convenient nonowner.

For a guarded service method, `@LeaseGuard` with scalar `@ShardKey` establishes the ownership context. A `@LeaderTask` gates leader work under its registered profile. In either case route mutations through the supporting fenced resource: a stale JVM must be rejected there even if its Java method has not stopped yet. See [leases and fencing](../patterns/leases-fencing.md) and [partitioned state](../patterns/partitioned-state.md).
