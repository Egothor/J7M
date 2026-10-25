# Messaging and event annotations

Managed messaging binds ordinary application methods and interfaces to explicit trusted host profiles. Native queue push claims and delivers the complete payload and receipt together; a callback is not a notification that the application must follow with a separate fetch.

## @QueuePublisher {#queuepublisher}
Declares a typed producer method. **Target:** method. **Attributes:** required `queue`; `policy = "default"`, `version = 1`.

```java
public interface OrderMessages {
    @QueuePublisher(queue = "orders", policy = "orders-publisher")
    long publish(String stableId, Order payload);
}
```

The host registers the exact authorized producer interface with `ManagedQueuePublisherFactory`. The managed resource dependency normally exposes a `ManagedProvider<OrderMessages>`. A confirmed sequence means native SEND committed, not that an unrelated database effect committed atomically with it.

## @DurableQueueConsumer {#durablequeueconsumer}
Declares a payload handler. **Target:** method. **Attributes:** required `queue`; `value = ""`, `policy = "default"`, `version = 1`.

```java
@DurableQueueConsumer(queue = "orders", value = "billing", policy = "orders-worker")
public void receive(Order order) {
    invoiceProcessor.accept(order);
}
```

Supported callbacks return `void`, or `CompletionStage<Void>` with the asynchronous calling contract. The host installs `ManagedQueueConsumerFactory` as both the factory and its required replay policy. ACK follows known durable outcome and actual physical completion. A timeout alone is not an ACK decision.

## @DeadLetterHandler {#deadletterhandler}
Declares an observational poison-message callback. **Target:** method. **Attributes:** `queue = ""`, `value = ""`, `policy = "default"`, `version = 1`.

```java
@DeadLetterHandler(queue = "orders", value = "billing-review", policy = "orders-dlq")
public void inspect(Order order) {
    reviewDesk.record(order.orderId());
}
```

The trusted profile must resolve an actual source queue. Successful observation neither removes the original DLQ entry nor implicitly redrives it. Redrive is an explicit authorized administrative operation bound to the original receipt and lineage.

## @InboxHandler {#inboxhandler}
Selects JDBC delivery deduplication. **Target:** method. **Attribute:** `policy = "default"`.

```java
@DurableQueueConsumer(queue = "orders", value = "invoice", policy = "orders-inbox")
@JdbcTransaction(policy = "orders-db")
@InboxHandler(policy = "invoice-inbox")
public void invoice(Order order) {
    invoiceRepository.insertUsingCurrentConnection(order);
}
```

The queue profile selects INBOX. The marker and business mutation must use the same exact owned JDBC registration/Connection; native ACK follows its commit and physical settlement. An external effect on another connection is outside this guarantee.

## @OutboxPublisher {#outboxpublisher}
Declares a producer staged on the current owned JDBC connection. **Target:** method. **Attributes:** `value = ""`, `policy = "default"`, `version = 1`.

```java
public interface InvoiceEvents {
    @OutboxPublisher(value = "invoice-events", policy = "orders-outbox")
    long enqueue(String stableId, String aggregate, Order payload);
}
```

`ManagedOutboxFactory` supplies the authorized proxy and a methodless hosted relay. It stages with the application's database mutation, then the relay sends stable native IDs and marks confirmed rows. Queue commit and SQL mark are separate recoverable steps, not one distributed transaction.

## @EventSubscription {#eventsubscription}
Declares a durable event callback. **Target:** method. **Attributes:** required `value`; `policy = "default"`, `filter = ""`, `version = 1`.

```java
@EventSubscription(value = "orders", policy = "order-events")
public void changed(ManagedEventDelivery delivery) {
    projection.accept(delivery);
}
```

The host supplies snapshot/tail behavior, source, filters, retention and a durable cursor identity. ACK follows actual callback completion. Cursor expiry is explicit; the application must follow the configured resnapshot/recovery contract rather than skip unseen history.

## Example: publish an order and consume its payload

A checkout component receives the authorized producer through a host resource binding. The host registers the `Order` schema, the exact `OrderMessages` interface, and the `orders-publisher`/`orders-worker` profiles shown above. These are application classes; place each public type in its own source file.

```java
@SchemaType(id = "orders.Order", version = 1)
public record Order(@WireField(id = 1) String orderId) { }

@J7mComponent
public final class Checkout {
    private final ManagedProvider<OrderMessages> messages;

    @Inject
    public Checkout(@J7mResource("order-messages")
                    ManagedProvider<OrderMessages> messages) {
        this.messages = messages;
    }

    public long place(String orderId) {
        return messages.get().publish("order:" + orderId, new Order(orderId));
    }
}

@J7mComponent
public final class Billing {
    public Billing() { }

    @DurableQueueConsumer(queue = "orders", value = "billing",
                          policy = "orders-worker")
    public void receive(Order order) {
        System.out.println("Received order " + order.orderId());
    }
}
```

Calling `place("42")` commits a native SEND and returns its sequence. An admitted billing worker receives `Order("42")` directly with the claimed delivery; it does not perform another fetch. Its normal return permits the host to settle that delivery. Printing illustrates payload access, not a durable invoice: a retry can print again.

For a real invoice, replace the print with the `@InboxHandler`/`@JdbcTransaction` method above and use the exact current guarded connection. Stage an `InvoiceEvents.enqueue("invoice:42", "42", order)` call from that same transaction when an outbox event is needed. The stable ID must survive retries. After commit the relay publishes it; rollback publishes neither SQL mutation nor staged event.

A poison-message review uses `@DeadLetterHandler` to record an alert without removing or redriving the entry. An `@EventSubscription` callback updates its projection from the delivered event and completes only after the update finishes; restart resumes from its durable cursor. See [work queues](../patterns/work-queues.md), [inbox/outbox](../patterns/inbox-outbox.md) and [event subscriptions](../patterns/event-subscriptions.md).
