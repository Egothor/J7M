# Managed event subscriptions

`@EventSubscription(value="orders-projection", policy="orders", filter="BATCH", version=1)` declares a public POJO method taking one `ManagedEventDelivery` and returning `void` or `CompletionStage<Void>`. The generated hosted adapter supplies a direct callback; applications implement no middleware handler interface. The operator installs `ManagedEventSubscriptionFactory` under the `event-subscription` kind in the trusted hosted-resource registry.

Each named `EventSubscriptionProfile` borrows a source endpoint and a separately scoped metadata endpoint. The metadata scope must be isolated from the application source projection. `sourceNamespace` is a stable host identity for the source scope, independent of its network address. Profiles declare finite deadlines, leases, page/snapshot bounds, restart and history-expiry behavior. The source's clock maintenance and authorization remain host responsibilities. The factory does not close borrowed endpoints.

The durable subscription identity survives endpoint refresh and application restart. A durable CAS manifest binds that identity to its version, exact filter and method contract. Changed incompatible definitions fail before invoking a handler. PENDING means bootstrap has not been established; ACTIVE is saved after the snapshot barrier has been authoritatively subscribed or acknowledged. A crash during bootstrap can repeat the complete snapshot callback.

Delivery explicitly distinguishes SNAPSHOT from EVENT. A snapshot replaces the whole application projection at its exact source barrier. Events committed during snapshot processing follow through the tail. Payloads are bounded, versioned source bytes; application code can decode map events using `ResourceEvent`. Arrays are copied, and no arbitrary class is loaded from incoming payload metadata.

Startup first arms the prepared binding without scheduling application work. Only the host's global admission commit opens delivery, after all activation preparations have succeeded. Actual annotation values and callback/component types must match the finite descriptor, including for handwritten indexes.

ACK follows the trusted hosted invoker's actual completion, including owned asynchronous work and finalizers. Reply timeout or cooperative cancellation does not prove physical completion. An application failure stops that binding without ACK. Drain first closes new admission and waits for actual work; a false drain result retains the binding and classloader. One serial page per subscription shares a fixed worker pool and bounded ready queue.

An uncertain source mutation retains exactly its native request identity and arguments. A lost ACK is confirmed without invoking the same handler again within that running binding. A process crash can redeliver the same cursor. Native ACK/subscription replay does not itself prove a fresh lease renewal, so the runner separately confirms durable progress through RESUME before continuing.

Delivery is at least once. Use the full cursor as an application deduplication/version identity. A subscription is one logical projection: concurrent JVMs sharing its identity must share a suitable durable projection and tolerate duplicate processing. The native source has no exclusive consumer claim. This adapter cannot fence arbitrary external writes or make ACK atomic with an unrelated database effect.

`Restart.RESUME` requires an already durable application projection. `Restart.SNAPSHOT` explicitly rebuilds it. The default expiry behavior should be `Expiry.FAIL`: CURSOR_EXPIRED or SUBSCRIPTION_EXPIRED stops processing with a fixed diagnostic reason. `Expiry.RESNAPSHOT` explicitly permits replacing the projection from a fresh image before tailing again. Quorum/transport uncertainty, permission failure, capacity exhaustion and endpoint refresh never authorize a silent history reset.

Native bounded request identities, tombstones and retention remain in force. CAPACITY/EXPIRED is surfaced rather than worked around with a new random resource or deletion. Polling without progress avoids unnecessary ACK; host lease/poll settings determine renewal frequency. Factory close requires every activation binding to be actually drained and closed first.

