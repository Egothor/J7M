# Distributed application patterns

Choose a pattern by the failure you need to handle, not by the number of annotations it uses. Every pattern below explains its use case, the J7M realization, annotated and explicit entry points, configuration and observable failure boundary.

| Application need | Pattern |
| --- | --- |
| Find instances and replace a failed dependency | [Discovery and refresh](discovery-refresh.md) |
| Invoke a service or retain one exact object | [Request–reply](request-reply.md) |
| Let a remote service call the caller | [Callbacks](callbacks.md) |
| Compose work without confusing timeout with exit | [Async and cancellation](async-cancellation.md) |
| Bound producers, consumers and buffers | [Streaming and backpressure](streaming-backpressure.md) |
| Select available instances safely | [Availability and failover](availability-failover.md) |
| Recognize repeated business commands | [Durable deduplication](deduplication.md) |
| Distribute recoverable work | [Durable queues](work-queues.md) |
| Investigate poison messages | [Dead letters](dead-letters.md) |
| Coordinate message handling with SQL | [Inbox and outbox](inbox-outbox.md) |
| Maintain a projection from durable events | [Event subscriptions](event-subscriptions.md) |
| Route deterministic state updates by key | [Partitioned state](partitioned-state.md) |
| Reject work from stale owners | [Leases and fencing](leases-fencing.md) |
| Recover long work from a checkpoint | [Durable jobs](durable-jobs.md) |
| Run persistent timed occurrences | [Persistent schedules](persistent-schedules.md) |
| Coordinate compensatable business steps | [Sagas](sagas.md) |
| Choose an actual atomicity boundary | [Transactions](transactions.md) |

## Read every comparison in context

An annotated handler is ordinary application code hosted through generated metadata. Its explicit counterpart exposes the operations that the host adapter otherwise owns. The two columns are not claims that the smallest fragment independently implements a full production host.

Start from the [runnable applications](../testing/verified-examples.md), then follow each page's practical flow and recovery decisions. Install the named trusted host profiles before deploying annotated handlers. Do not copy development security settings into a deployed host.
