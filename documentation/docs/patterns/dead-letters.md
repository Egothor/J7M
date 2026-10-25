# Poison messages, dead letters and controlled redrive

**Use case:** repeated known failures exhaust a message's allowed attempts. Operations need to inspect the original payload, diagnose the cause and deliberately request a new attempt after correction.

=== "With annotations"

    ```java
    @DeadLetterHandler(queue = "orders", value = "review", policy = "orders-dlq")
    public void inspect(Order order) {
        reviewDesk.record(order.orderId());
    }
    ```

    This callback observes the retained payload. A successful return is not permission to remove or redrive the original DLQ entry.

=== "Explicit API"

    ```java
    try (Stream<DeadLetter> entries = queue.deadLetterStream("orders", deadline)) {
        entries.forEach(reviewDesk::inspect);
    }
    String outcome = queue.redrive("redrive-42", "orders", originalId,
            originalReceipt, "order-42-retry", 3, deadline);
    ```

    `redrive` requires administrative authority. It validates the original receipt and creates the new child under the native atomic lineage/audit protocol.

## Inspect all pages deliberately

`deadLetterStream` loads pages lazily, keeps one page in memory and uses the original deadline and first read high-water mark. Later arrivals do not extend the scan forever. Explicit page consumers must preserve the authenticated continuation returned by the previous page rather than manufacture a numeric cursor.

## Recovery and operational controls

Correct the root cause before redrive. A new child preserves the parent and authenticated actor audit; it does not erase evidence or convert uncertainty into known failure. Keep maximum lineage depth, poison attempts and storage capacity finite. A worker credential must not be able to grant itself redrive permission.

## Worked scenario: review an order, then authorize redrive

A worker repeatedly fails to decode an order. Once its finite attempts are exhausted, the DLQ retains the payload and receipt. A review application inspects the bounded lazy stream:

```java
try (Stream<DeadLetter> entries = queue.deadLetterStream("orders", deadline)) {
    entries.forEach(reviewDesk::inspect);
}
```

`reviewDesk` is an application component that displays safe diagnostics and protects any sensitive payload. Inspection does not ACK, delete or redrive the message. Fix the schema/configuration or business cause before retrying.

An authorized operator then submits one stable administrative request:

```java
String result = administratorQueue.redrive(
        "redrive:order-42:1", "orders", originalId, originalReceipt,
        "order-42-retry", 3, deadline);
System.out.println("Redrive outcome: " + result);
```

`administratorQueue` is a separately authenticated, authorized facade; `originalId` and `originalReceipt` come from the retained entry. The worker's ordinary credentials must not perform this operation. The server checks the exact receipt and commits the child with its lineage/audit projection.

After a lost reply, repeat the same administrative request rather than inventing another child. The parent retains evidence of the redrive. New READY work is delivered through the ordinary worker path; successful observation alone never creates it. See [administration runbooks](../operations/runbooks.md).
