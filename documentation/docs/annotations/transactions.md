# Deduplication and transaction annotations

Choose the atomicity boundary first. A native participating-resource transaction, one JDBC connection and an external payment call are different resources with different recovery rules.

## @Deduplicated {#deduplicated}
Selects a host-owned durable invocation replay policy. **Target:** method. **Attribute:** `policy = "default"`.

```java
@Deduplicated(policy = "order-commands")
String confirm(@IdempotencyKey String commandId, String orderId);
```

The installed policy binds stable logical identity and canonical input to the native durable invocation primitive. Identical replay can return its recorded outcome; conflicting reuse is rejected. Uncertain external work is not automatically retried under a fresh key.

## @IdempotencyKey {#idempotencykey}
Marks the operation's stable application identity argument. **Target:** parameter. **Attributes:** none.

```java
@Deduplicated(policy = "order-commands")
String cancel(@IdempotencyKey String commandId, String orderId);
```

Keep the key unchanged after a lost reply. A transport request ID, retry count, queue receipt or worker generation is not the business identity. The host also binds namespace, tenant and method contract.

## @JdbcTransaction {#jdbctransaction}
Selects a trusted local JDBC unit-of-work profile. **Target:** method. **Attribute:** `policy = "default"`.

```java
@JdbcTransaction(policy = "orders-db")
public void updateInvoice(String orderId) throws SQLException {
    try (PreparedStatement statement = connections.get().prepareStatement(
            "INSERT INTO invoice(order_id) VALUES (?)")) {
        statement.setString(1, orderId);
        statement.executeUpdate();
    }
}
```

Here `connections` is an injected `@J7mResource("jdbc") ManagedProvider<Connection>` supplied by the same exact JDBC registration as `orders-db`. Opening a second connection in the body does not join it. Supported nesting and finalization rules belong to the policy; arbitrary Java self-calls introduce no new transaction boundary. The qualified adapter uses a conservative H2 TCP profile.

## @ResourceTransaction {#resourcetransaction}
Selects native participating-resource 2PC. **Target:** method. **Attribute:** `policy = "default"`.

```java
@ResourceTransaction(policy = "inventory-transfer")
public String recordTransfer(String transferId) throws IOException {
    ManagedResourceTransactionContext current = transactions.get();
    current.write("source", "transfers/" + transferId, "dispatched");
    current.write("destination", "transfers/" + transferId, "received");
    return current.transactionId();
}
```

Here `transactions` is an injected `@J7mResource("transaction") ManagedProvider<ManagedResourceTransactionContext>`. The host registers `source` and `destination` as native participants of `inventory-transfer`. Both writes are staged under the transaction's durable locking protocol; the host finalizer owns preparation and the commit decision. The returned ID supports authorized status/recovery if the outcome becomes uncertain. This is not XA for arbitrary JDBC, files or external HTTP calls. A remote hop does not carry transaction authority.

## Example: repeat a command without inventing another identity

A client keeps the same operation key when retrying a known deduplicated command:

```java
String commandId = "cancel:order-42";
String outcome = orders.cancel(commandId, "42");
// After a lost reply, retain commandId for the status/replay protocol.
String repeated = orders.cancel(commandId, "42");
```

Here `orders` is an admitted proxy implementing the `@Deduplicated`/`@IdempotencyKey` contract above, with its host-owned durable replay policy installed. Identical replay can return the recorded result. Reusing `cancel:order-42` with different canonical arguments is rejected rather than treated as another cancellation. External uncertain effects still require reconciliation.

For database work, the method must actually use its profile's guarded connection. An application repository can make that dependency explicit:

```java
public void insertInvoice(Connection current, String orderId) throws SQLException {
    try (PreparedStatement statement = current.prepareStatement(
            "INSERT INTO invoice(order_id) VALUES (?)")) {
        statement.setString(1, orderId);
        statement.executeUpdate();
    }
}
```

Call this helper from the admitted `@JdbcTransaction` method using the exact current connection supplied by its trusted profile. Do not open or commit another connection inside the helper. A thrown SQL exception follows the policy's rollback/finalization path; successful completion permits that owner to commit.

For `@ResourceTransaction`, the `recordTransfer` method above stages two native participant records. The host publishes them only under its durable commit decision; in-doubt recovery follows that decision rather than guessing from an exception. A JDBC connection or HTTP request does not join native 2PC automatically. See [deduplication](../patterns/deduplication.md) and [transactions](../patterns/transactions.md) for each resource boundary.
