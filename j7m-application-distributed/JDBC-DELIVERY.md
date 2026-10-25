# Managed JDBC inbox and outbox

The qualified profile is H2 2.3.232 over TCP. These adapters reuse `JdbcDelivery`
and the [owned A11 JDBC transaction](TRANSACTIONS.md), not XA or a database/queue
distributed transaction. The trusted host supplies the connection registration,
tenant, logical namespaces, codec and authorized destination. No annotation carries
credentials or grants a new backend capability.

## Inbox

A queue callback marked `@InboxHandler` also declares `@JdbcTransaction`. Its host
queue profile selects INBOX and installs `ManagedInboxPolicy` with the **same JDBC
registration instance** as `ManagedJdbcTransactionPolicy`. The marker and business
mutation use that exact actual guarded Connection. Duplicate delivery finds the
marker and skips the body; rollback leaves neither marker nor DB effect. Async
children settle before the transaction finalizer commits, and native ACK waits
for actual physical completion. Lost ACK therefore permits safe SQL marker replay.

Inbox identity is a length-framed digest of host inbox namespace, authorized tenant,
source partition/queue, logical handler/version and native message ID. A new receipt
or generation does not change it. The same raw message ID on different authorized
queues or logical handlers remains distinct. External effects outside this owned
Connection are not covered by the inbox transaction.

## Outbox

An ordinary public producer interface declares
`@OutboxPublisher("logical-outbox") long enqueue(String key, String aggregate, Payload value)`.
Inject its provider with `@J7mResource`. `ManagedOutboxFactory` authorizes an exact
Class or an exact binary name; actual generated type, marker, full method/codec
contract and policy are checked before construction. The proxy always implements
the actual application interface. It stages only on the current exact JDBC
registration, never opens a second Connection inside the callback, and marks the
transaction rollback-only even if the application catches a staging failure.

An application-owned schema record uses an immutable per-definition/per-method codec
selected from that actual verified interface's generated indices and declared payload
types. It does not require the host to preload the application class or mutate a
shared schema registry. The inbox queue decoder follows the same scoped schema rule;
all native frame and declared-type bounds remain in force.

The selected constructor/factory/field dependency generates one finite methodless
`outbox-relay` feature. No no-op application callback or specialized application
interface is required. Only the installed factory's explicit backend-kind capability
allows this methodless feature. Multiple producer owners sharing the same exact
logical SQL configuration share one local relay; conflicting configurations fail.
Leaving one activation cannot close another admitted owner's relay. Final stop
waits for actual SQL/send/mark work before closing owned connections.

The original payload/trace intent is retained under D12 before staging. A persistent
reserved definition row fingerprints namespace, tenant and destination partition,
scope and queue. It is committed with initial staging or closed-admission relay
preparation. Reopening the same SQL scope with another destination fails closed;
it cannot reroute old pending rows. Definitions are non-publishable rows in a
separate reserved scope, with a transactionally checked 256-definition schema quota.
No extra table or format migration is introduced.

After global admission the relay reads bounded aggregate heads, sends their exact
stable native IDs, then marks confirmed rows. A crash after queue commit and before
SQL mark retries the same SEND identity. A crash after DB commit and before consumer
ACK reuses the inbox marker. These are two explicit recovery protocols, not one
cross-system atomic commit. Relay polling concerns bounded SQL outbox batches;
native consumer delivery remains reactive push without idle queue polling.

## Limits and failure boundary

The payload codec is explicitly schema allowlisted and bounded to 12 KiB before
base64/envelope overhead. Native IDs are derived from stable host publisher namespace,
tenant, logical publisher/version, full method and business key. Aggregate order,
retention and queue poison bounds come from finite trusted profiles.

JDBC transaction controls, unwrap/back references and unsupported SQL are guarded
by the A11 conservative H2 subset. Result data must be detached before settlement.
No new COMMIT or protected effect starts after caller cancellation/deadline using
a cleanup budget. Uncertain commit/send outcomes remain uncertain until their
own durable protocol establishes them. An external side effect is not made atomic
merely because the handler also uses an inbox or outbox.

Before first use rollback removes additive bindings. After durable use retain
the current database/queue state and profiles; this pre-release implementation
does not claim old-binary or database-format migration support. Actual signed
JPMS/H2/TCP process qualification and independent review are separate from the
local transaction tests and must pass before declaring A13 complete.
