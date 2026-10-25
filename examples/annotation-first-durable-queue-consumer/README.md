# Annotation First Durable Queue Consumer

## Start Here
Start with `QueueConsumer`. It shows queue, outbox, inbox, retry,
backpressure, dead-letter, and isolation metadata while keeping durable
delivery owned by `DurableQueue`.

## Purpose
Demonstrates durable queue consumer annotations, outbox/inbox metadata,
backpressure metadata, and public capability injection.

## Architecture flow
The consumer declares queue, inbox, outbox, retry, and backpressure metadata
with annotations. The injected public `DurableQueue` API remains responsible
for real leasing, ack/nack, retry, dead-letter, and recovery behavior.

## Annotations demonstrated
`@J7mApplication`, `@Inject`, `@J7mResource`, `@DurableQueueConsumer`,
`@OutboxPublisher`, `@InboxHandler`, `@Backpressure`, `@Retry`,
`@Idempotent`, `@DeadLetterHandler`, and `@IsolationProfile`.

## Injected services demonstrated
The consumer receives the public `DurableQueue` API and a safe audit collector.
It does not receive queue internals, durable-log frames, transport internals,
or unredacted payload bytes.

## Enforcement status
Public capability injection is executed by the J7M injector. Queue, outbox,
inbox, retry, backpressure, dead-letter, and isolation annotations are
descriptor-only in this example; durable delivery remains in `DurableQueue`.

## Prerequisites
Use Java 25 and the local J7M build. No external broker, object store, cloud
SDK, Docker, Kubernetes, or vendor platform is required.

## Build
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Test
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Package
Run `./gradlew packageExamples --no-daemon`.

## Verify bundle
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Run / demo
Run `./gradlew validateAnnotationExamples --no-daemon`; the validator compiles
the source tree and compares deterministic metadata smoke output with
`EXPECTED_OUTPUT.txt`.

## Safe modification exercise
Rename the queue from `orders` to `billing-orders` in source and
`EXPECTED_OUTPUT.txt`, then run `./gradlew validateAnnotationExamples --no-daemon`.

## Deploy to local repository
The annotation demo validates queue descriptor metadata locally. Use the
standard deployable examples when repository publishing is required.

## Start / activate
The demo constructs the consumer through J7M-native injection.

## Invoke / verify behavior
The demo invokes an annotated consumer method and a dead-letter handler.

## Status / observability
The demo prints the safe queue result and audit summary.

## Stop / unload
The demo exits without external broker resources.

## Upgrade / rollback, where applicable
Use the existing upgrade and rollback examples for deployment strategy
coverage. This example focuses on durable queue annotation metadata.

## Verification
Run `./gradlew validateAnnotationExamples --no-daemon`.

## Expected output
See `EXPECTED_OUTPUT.txt`; the smoke output prints queue and dead-letter
metadata markers.

## Security notes
Queue annotations are descriptor-only in this example. Public durable queue
APIs still enforce actual delivery, lease, retry, and dead-letter semantics.

## Redaction notes
The example prints event ids only and never prints payload bytes or secrets.

## Troubleshooting
If descriptor validation fails, verify the queue name and outbox/inbox metadata
are stable logical names, not broker-specific identifiers.

## Cleanup
The demo uses in-memory example state and public capability proxies.

## Known limitations
This example does not start a broker or claim generic exactly-once delivery.
It shows annotation metadata and public queue capability injection.
