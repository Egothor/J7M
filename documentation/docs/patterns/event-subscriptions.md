# Durable events, replay and projection cursors

**Use case:** a read model must rebuild from a source snapshot and then follow committed changes without missing the updates that occur during snapshot processing.

=== "With annotations"

    ```java
    @EventSubscription(value = "orders-projection", policy = "order-events", filter = "BATCH")
    public void changed(ManagedEventDelivery delivery) {
        projection.accept(delivery);
    }
    ```

    The managed envelope distinguishes SNAPSHOT from EVENT. The application must replace its whole projection at a snapshot barrier, then apply subsequent event versions/cursors.

=== "Explicit API"

    ```java
    EventJournal.Page page = subscriptions.poll("orders-projection", 128, deadline);
    for (EventJournal.Event event : page.events()) {
        projection.apply(event);
    }
    subscriptions.ack("ack-page-42", "orders-projection",
            page.through(), 30_000, deadline);
    ```

    This uses an already established `EventSubscriptions` lease/source cursor. Explicit bootstrap must first capture and process the source snapshot/barrier correctly. Polling does not acknowledge a page.

## Cursor identity and restart

Use the full cursor, including partition, epoch and sequence, as the source position. ACK follows actual processing, including owned asynchronous work. A lost ACK can be confirmed without rerunning the body within one running binding; a process crash can still redeliver the same cursor.

`Restart.RESUME` assumes the application projection is durable. `Restart.SNAPSHOT` explicitly rebuilds it. Cursor/subscription expiry must stop or resnapshot according to a declared policy; transport uncertainty is not permission to silently reset history.

## Shared identity is not exclusive leadership

Concurrent JVMs sharing a subscription identity need a suitable durable projection and duplicate tolerance. The native event source does not grant an exclusive consumer claim. Use a queue or explicit fenced ownership if the application requires exclusive work ownership.

Configure separate source and subscription-metadata scopes, stable namespace, filters, leases, bounds and restart/expiry policies. See [distributed resources](../configuration/distributed-resources.md).

## Worked scenario: maintain a durable order projection

The application's projection store exposes two operations: atomically replace its image at a snapshot barrier, and apply an event once by its complete cursor. These are application storage operations, not methods supplied by the annotation.

```java
@EventSubscription(value = "orders-projection", policy = "order-events",
                   filter = "BATCH")
public void changed(ManagedEventDelivery delivery) {
    switch (delivery.kind()) {
        case SNAPSHOT -> projection.replaceAtomically(
                delivery.cursor(), delivery.payload());
        case EVENT -> projection.applyOnce(
                delivery.cursor(), delivery.operation(), delivery.payload());
    }
}
```

`projection` is an injected application repository. It decodes only the admitted versioned source format. The host supplies the event source, metadata backend, filters and stable subscription identity.

While the snapshot callback is running, another source mutation can commit. The source's snapshot barrier and subsequent tail preserve that mutation for delivery after the snapshot. Returning normally permits the host to acknowledge actual callback completion.

If the JVM dies after updating the projection but before ACK, its replacement can receive the same cursor. `applyOnce` must recognize that full partition/epoch/sequence identity durably or otherwise make repeat application safe. If the projection exists only in memory, choose explicit snapshot restart instead of RESUME.

After a lost ACK reply, recover the durable cursor according to the host protocol. Do not silently discard an expired cursor or skip history to make the subscriber appear healthy.
