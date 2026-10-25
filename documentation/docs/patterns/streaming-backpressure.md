# Streaming, backpressure and bounded concurrency

**Use case:** workers receive messages at a rate they can actually process. Admission must cover queued and claimed-but-not-written deliveries, not merely the callback currently executing.

=== "With annotations"

    ```java
    @DurableQueueConsumer(queue = "images", value = "thumbnail", policy = "image-workers")
    public void resize(ImageCommand command) {
        thumbnailService.resize(command);
    }
    ```

    The managed queue adapter owns the native credit-controlled session and requests subsequent credit after actual owned completion. The host controls worker capacity and visibility bounds.

=== "Explicit API"

    ```java
    try (QueueSession session = queue.subscribe(
            "images", "workers", "worker-a", 30_000, deadline)) {
        session.subscribe(subscriber);
        applicationLifetime.awaitShutdown();
    }
    ```

    `subscriber` is the application's `Flow.Subscriber<QueueDelivery>`. It must use bounded demand, settle the receipt according to actual processing outcome and own its lifecycle. Closing the session is not an ACK.

## Demand is a protocol

The server commits a claim and delivers full payload, sequence, member/generation and receipt together. A client does not receive a reservation notice and then fetch the work. Idle push sessions do not poll empty claims into the foreground journal.

`@Backpressure` can declare supported admission metadata and `@BackpressureAware` documents application cooperation, but the actual limit comes from the enforcing API/profile. A marker does not make an unbounded list, executor or buffer safe.

## Failure boundary

A disconnected consumer may still have entered application work. Delivery receipts and visibility fences determine subsequent settlement. Do not ACK merely because the caller timed out or because network delivery was closed. See [queues](work-queues.md) and [capacity configuration](../configuration/limits-observability.md).

## Worked scenario: request another message only after settlement

A native queue subscriber owns a Flow demand handle. The following callback core assumes a configured `ReplicatedQueue queue`, a bounded `process` operation and an absolute per-operation deadline supplied by the host:

```java
@Override
public void onSubscribe(Flow.Subscription subscription) {
    this.subscription = subscription;
    subscription.request(1);
}

@Override
public void onNext(QueueDelivery delivery) {
    try {
        process(delivery.payload());
        String outcome = queue.ack(
                "ack:" + delivery.messageId() + ":" + delivery.receipt(),
                delivery.queue(), delivery.group(), delivery.member(),
                delivery.generation(), delivery.messageId(),
                delivery.receipt(), Instant.now().plusSeconds(2));
        if (!"ACKED".equals(outcome)) {
            subscription.cancel();
            return;
        }
        subscription.request(1);
    } catch (IOException | RuntimeException failure) {
        subscription.cancel();
        // Report failure to the application's supervisor; do not ACK blindly.
    }
}
```

This is the core of an application `Flow.Subscriber<QueueDelivery>`; its surrounding class also implements `onError`/`onComplete` and owns session closure. Keep `process` bounded and avoid blocking provider event loops: the host must select the supported application dispatch/executor boundary. For managed consumers the adapter already supplies the owned processing/credit lifecycle.

With one credit, the session pushes the complete claimed payload and receipt. It does not send an availability notice requiring another fetch. During inactivity the native push path does not append empty foreground claims.

After processing, ACK uses the exact delivered member, generation and receipt. Only a confirmed settlement permits this example to request more work. On uncertain ACK, stop the loop and recover that delivery outcome with the same stable request identity before proceeding; cancelling demand is not settlement. See [restart before ACK](work-queues.md#worked-scenario-restart-before-ack).
