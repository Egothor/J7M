# Local messaging journal format

The log-backed `org.j7m.messaging` implementation records queue, inbox and outbox transitions using schema `version=2`. This format belongs to that local implementation; the native distributed resource journal is a separate protocol. Applications should use the public messaging APIs instead of writing journal records themselves.

## Recovery events

| Event | Recorded transition |
| --- | --- |
| `MESSAGE_ENQUEUED` | Durable message admission |
| `MESSAGE_LEASED` | Delivery ownership acquired |
| `MESSAGE_ACKED` | Delivery acknowledged |
| `MESSAGE_NACKED` | Delivery released or retried |
| `MESSAGE_DEAD_LETTERED` | Delivery moved to poison handling |
| `OUTBOX_RECORDED` | Publication intent retained |
| `OUTBOX_PUBLISHED` | Publication confirmed |
| `OUTBOX_FAILED` | Publication failure recorded |
| `INBOX_RECORDED` | Delivery identity accepted |
| `INBOX_DUPLICATE_SUPPRESSED` | Repeated delivery suppressed |
| `CHECKPOINT` | Recoverable state checkpoint |

Records contain a version, event type and validated event-specific fields. Unknown types, malformed encodings and invalid field sets fail decoding. The implementation bounds encoded records to 4 MiB, individual fields to 2 MiB and the field count to 96. These are codec bounds, not recommended business-payload sizes.

## Application recovery

Keep the journal and its checkpoints under the owning service's storage lifecycle. After restart, use the recovered service to inspect or resume work; do not recreate an uncertain message with a new identifier. Stable message identities preserve the deduplication boundary, while current lease/receipt checks reject stale acknowledgements.

For application code see [work queues](../patterns/work-queues.md) and [transactional inbox/outbox](../patterns/inbox-outbox.md). For the underlying storage lifecycle see [durable log](../programming/durable-log.md).
