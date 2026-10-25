# Managed durable queue delivery

`ManagedQueuePublisherFactory` binds an ordinary public interface whose methods are
`@QueuePublisher(queue=...) long publish(String stableId, Payload value)`. Inject it
through `@J7mResource` and `ManagedProvider<YourInterface>`. The trusted host registers
the exact interface class, or its exact authorized binary name when the interface
belongs to the application module. Name registration never loads a class: preflight
checks the actual generated dependency, every annotated method and the codec before
constructors. Both producer and consumer use explicit finite backend/schema profiles.

For application-owned `@SchemaType` payloads, each verified actual definition selects
an immutable codec from its own generated schema indices and declared data graph.
Only those declared schemas are admitted; the borrowed host registry is not mutated
or replaced globally. Producer, consumer and DLQ observation retain the scoped codec
for their own binding lifetime. Unknown or unschematized data still fail preflight.

The producer persists a first publication intent in D12, including the original trace
carrier, then sends the same immutable envelope and native message identity. A
confirmed sequence means the native SEND committed. It does not make an unrelated
database or external operation atomic with publication. A conflicting reuse of the
business key fails; EXPIRED and uncertain results do not authorize a fresh effect.

`@DurableQueueConsumer(queue=..., value="logical-handler")` marks a public single
payload callback returning `void` or `CompletionStage<Void>`. `@Async` selects the
latter contract. The generated finite `queue-consumer` feature is hosted by
`ManagedQueueConsumerFactory`; the **same factory instance** must also be installed
as the REPLAY invocation policy. Missing or merely similar guard instances fail
before constructors. Calling semantics, security and transaction policies remain
separate explicitly installed capabilities.

## Direct push and completion

The native authenticated partition TCP session commits the claim and pushes complete
immutable payload, message ID, sequence, member, generation and receipt together.
There is no availability notification followed by client fetch/claim. `QueueSession`
is an owned single-subscriber `Flow.Publisher<QueueDelivery>`; closing it releases the
connection. The server accepts at most 64 sessions, reserves at most four 64 KiB
delivery frames per session and at most 16 MiB globally, including claimed frames
not yet written. Scope-bound HMAC, nonce and directional sequence checks protect the
session; non-loopback transport requires configured TLS authentication.

Commit, credit, reconnect and actual lease-expiry signals drive the dispatcher.
Idle subscriptions do not issue foreground queries, clock/heartbeat updates or
empty claims. A low-level explicit `claim(..., retainEmptyResult, deadline)` stores
EMPTY only when the flag is true; the ordinary overload defaults to false. A racing
claim can still enter the consensus log without retaining its empty result. This
is distinct from an idle push session, whose foreground traffic is zero.

The managed callback runs through the actual owned CALL/policy/finalizer pipeline.
Trace restoration and decoding occur inside that boundary. ACK follows physical
completion and a known durable outcome, not an early logical timeout. Receipt
renewal is fenced by member/generation/receipt and a finite total visibility window.
Stopping admission closes the session; drain retains the owner while real work or
protocol operations remain. Borrowed native endpoints and codecs stay host-owned.

The EXTERNAL_GUARD strategy claims D12 before entering the body. Its stable identity
includes host namespace, tenant, source partition/queue and logical handler/version,
not transient member, receipt or generation. Known completed outcomes can ACK a
redelivery without executing the body again. Known classified failures are cached
and NACKed up to the poison limit. Uncertain external work becomes visible UNKNOWN
and is never automatically repeated. `@Idempotent` is not durable deduplication.
The alternative INBOX strategy requires the actual JDBC and inbox policies described
in [JDBC-DELIVERY.md](JDBC-DELIVERY.md).

## DLQ inspection and explicit redrive

`@DeadLetterHandler` creates an observational `dead-letter-handler` feature. It
receives the retained payload under the same bounded push mechanism and D12 guard.
Success neither ACKs nor removes the original DLQ entry and never implicitly
redrives it. The original receipt remains authoritative.

`ReplicatedQueue.deadLetterStream(queue, deadline)` lazily traverses all pages with
one page in memory, one original deadline and the first quorum-read sequence
high-water. Later arrivals cannot extend the traversal indefinitely. Explicit
`deadLetterPage` returns at most 256 entries and also respects a 512 KiB encoded
page limit. Remote continuation tokens bind partition, tenant, queue, high-water
and last sequence and are server authenticated. Prefer the previous-page overload
for continuation; a remote numeric cursor cannot manufacture its own token.

Administrative `redrive` atomically checks the original receipt, creates one new
READY child and retains the parent plus server-authenticated actor audit. Maximum
lineage depth is 16. Ordinary worker credentials cannot grant this permission.
Every mutation checks count and encoded storage capacity before publishing changes;
the current queue image is bounded to 32 MiB. Historical result references retain
the original immutable payload rather than copying it into every claim reply.

## Recovery and qualification

This pre-release backend supports only its current queue snapshot format. Unknown
versions, corrupt images and malformed lineage fail closed. There is no downgrade
or mixed-binary migration promise. Before first use rollback is additive removal;
after use retain the current data and recovery tooling.

Author checks include actual three-voter TCP queue/D12 backends, generated activation,
owned children, poison/DLQ/redrive, and frontend termination before ACK and after an
uncertain external effect. Native 10,000-message/restart and 64-idle-session gates
are separate recorded tests. A12 completion still requires independent review;
the final D18 release soak must bind the latest native source hashes rather than
reuse historical results.
