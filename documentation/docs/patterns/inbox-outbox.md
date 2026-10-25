# Transactional inbox and outbox

**Use case:** a queue message updates a database and publishes a follow-up event. Two crash windows must be handled independently: database commit before consumer ACK, and queue SEND before marking an outbox row sent.

<figure class="j7m-diagram" markdown>
<div class="j7m-diagram-scroll" tabindex="0" role="region" aria-label="Delivery settlement boundary" markdown>

[![Delivery settlement boundary](../assets/diagrams/delivery.svg)](../assets/diagrams/delivery.svg){target="_blank" rel="noopener"}

</div>
<figcaption markdown="span">[Open full-size SVG](../assets/diagrams/delivery.svg). On small screens, scroll the diagram horizontally.</figcaption>
</figure>
## Inbox: commit the marker with the business mutation

=== "With annotations"

    ```java
    @DurableQueueConsumer(queue = "orders", value = "invoice", policy = "orders-inbox")
    @JdbcTransaction(policy = "orders-db")
    @InboxHandler(policy = "invoice-marker")
    public void receive(Order order) {
        invoices.insertUsingCurrentConnection(order);
    }
    ```

    The queue profile chooses INBOX; inbox and transaction policies share the exact JDBC registration. The application repository must use that guarded Connection.

=== "Explicit API"

    ```java
    boolean applied = delivery.consume(connection, messageId, envelope,
            (current, payload) -> invoices.insert(current, decodeOrder(payload)),
            Instant.now());
    ```

    Here `delivery` is `JdbcDelivery`, and the application owns the transaction. Its mutation callback receives that same Connection and the message payload. `decodeOrder` is the application's allowlisted decoder for that payload. The marker and mutation must commit together before native ACK. The qualified low-level API's transaction contract still applies.

Duplicate delivery finds the committed marker and skips the SQL body. Rollback leaves neither marker nor business mutation. Effects outside that connection are not covered.

## Outbox: stage now, relay after commit

=== "With annotations"

    ```java
    public interface InvoiceEvents {
        @OutboxPublisher(value = "invoice-events", policy = "orders-outbox")
        long enqueue(String stableId, String aggregate, Order payload);
    }
    ```

    Resolve the authorized producer provider inside the current JDBC transaction. The host supplies a methodless relay after global admission.

=== "Explicit API"

    ```java
    delivery.enqueue(connection, eventId, aggregateId, tracedMessage,
            createdAt, retainUntil);
    // Commit the owning SQL transaction before a separate relay publishes it.
    int sent = delivery.relayOnce(connections, queue, "invoice-events", 64, deadline);
    ```

    `JdbcDelivery` stages on the supplied connection. Its separate relay uses stable native SEND IDs, so a crash after queue commit and before SQL mark can recover without choosing a new message identity.

## Configuration and failure boundary

The qualified managed profile uses H2 over TCP and a guarded SQL subset. Register namespace, tenant, destination, schemas and the exact connection owner explicitly. The stored destination fingerprint prevents reopening pending rows against a different queue. SQL outbox batch polling is separate from reactive native queue delivery.

This is not one atomic DB+queue transaction. Keep uncertain commit/send outcomes visible until their own protocol establishes a result. See [transaction configuration](../configuration/distributed-resources.md).

## Worked scenario: insert an invoice and stage its event

The admitted handler uses the exact current JDBC registration for its inbox marker, invoice mutation and outbox row. The application's repository receives the guarded connection from that same profile; `events` is the authorized `ManagedProvider<InvoiceEvents>`.

```java
@DurableQueueConsumer(queue = "orders", value = "invoice",
                      policy = "orders-inbox")
@JdbcTransaction(policy = "orders-db")
@InboxHandler(policy = "invoice-marker")
public void receive(Order order) {
    invoices.insertUsingCurrentConnection(order);
    events.get().enqueue(
            "invoice:" + order.orderId(), order.orderId(), order);
}
```

The application supplies the `Order` schema and repository; the host installs the queue, shared JDBC registration, producer and relay profiles. Receiving order 42 inserts its invoice and stages event `invoice:42` in one SQL transaction.

| Interruption | Recovery action |
| --- | --- |
| Before SQL commit | Rollback leaves neither business row nor committed inbox/outbox state |
| After SQL commit, before native ACK | Redelivery finds the committed inbox marker and skips the SQL body |
| After queue SEND, before marking outbox sent | Relay retries the same immutable SEND identity and then confirms the SQL row |

The final application state is one invoice and one logical publication for that input. An external email, another connection or a payment made outside this transaction is not covered. Do not create a new event ID during relay recovery.
