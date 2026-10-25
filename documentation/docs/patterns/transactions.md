# JDBC and distributed resource transactions

**Use case:** an operation must update several participating native resources atomically, or must perform a local database mutation with a delivery marker. Choose the resource boundary that actually implements the required transaction.

| Requirement | Appropriate boundary |
| --- | --- |
| SQL mutation and inbox/outbox staging | One owned JDBC Connection |
| Supported native resources across groups | Recoverable native resource 2PC |
| SQL plus arbitrary external HTTP effect | Application workflow/outbox/idempotency; not implied XA |

=== "With annotations"

    ```java
    @ResourceTransaction(policy = "stock-transfer")
    public String transfer(String commandId) {
        return stock.moveWithinParticipatingResources(commandId);
    }

    @JdbcTransaction(policy = "orders-db")
    public void saveOrder(Order order) {
        orders.insertUsingCurrentConnection(order);
    }
    ```

    These are separate host profiles. The method body must use the exact participating resources or current guarded Connection; opening another connection or sending an external request does not make it join.

=== "Explicit API"

    ```java
    transactions.begin(txId, deadline);
    transactions.enlist(txId, "warehouse-a", deadline);
    transactions.prepare(txId, "warehouse-a",
            List.of("WRITE", "stock-7", "reserved"), deadline);
    transactions.commit(txId, deadline);
    ```

    `transactions` is a configured `RecoverableTransactions` with its durable coordinator and explicit participant inventory. Every observed read must also be represented correctly by the protocol; this small write-only fragment is not a general serializability recipe.

## In-doubt is a real outcome

A lost commit reply can occur after durable COMMIT. Do not assume abort because a call threw. Use `transactions.recover(txId, deadline)` and the authoritative decision protocol. A durable decision is not reversed by the caller's deadline.

Managed transaction finalizers run after actual owned USER work settles. Self-invocation does not create a new interception boundary, and a remote hop does not transfer transaction authority. An expired budget is not replaced with a cleanup budget to initiate new business effects.

## Configure and verify

The host registers exact JDBC/transaction capabilities, tenant mapping, participants, schemas, budgets and recovery policy. The qualified JDBC path uses the documented H2 TCP subset; do not generalize it to every JDBC driver or arbitrary SQL without qualification.

See [inbox/outbox](inbox-outbox.md) and [resource configuration](../configuration/distributed-resources.md).

## Worked scenario: distinguish prepared from committed

A native transfer uses separately registered coordinator and participating resources. An operator/recovery component queries the durable protocol rather than guessing from the frontend's last exception:

```java
var recovered = recovery.recover(transactionId, deadline);
System.out.println("Transfer state: " + recovered.status());
```

Here `recovery` is the authorized native transaction recovery client and `transactionId` is the original durable identity. If no durable decision exists after preparation, the state can remain IN_DOUBT. The application must not display the transfer as committed or independently spend prepared values. Follow the coordinator's authorized recovery/abort procedure.

If a COMMIT decision was persisted before the frontend disappeared, recovery completes that decision at the participants. A later abort request does not reverse a committed transaction.

For a local JDBC invoice, the application's SQL body instead uses one guarded connection:

```java
try (PreparedStatement statement = currentConnection.prepareStatement(
        "INSERT INTO invoice(order_id, amount_minor) VALUES (?, ?)")) {
    statement.setString(1, orderId);
    statement.setLong(2, amountMinor);
    statement.executeUpdate();
}
```

This fragment belongs inside the installed `@JdbcTransaction` callback; its owner controls commit/rollback. If the process disappears before COMMIT, a replacement sees no committed row. After COMMIT, the row is durable even if the reply was lost. Use stable command identity or the inbox protocol to recover without inserting an unintended duplicate.

Native 2PC and JDBC commit are separate contracts. Do not use an arbitrary second connection or HTTP request as though it automatically joined either boundary.
