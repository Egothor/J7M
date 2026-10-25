# Delivery, Consistency and Failure Semantics

Distributed guarantees belong to a specific operation and resource boundary. Avoid using a single label such as “exactly once” for an entire application.

| Mechanism | Established boundary | What it does not establish |
| --- | --- | --- |
| Registry discovery | Matching live/trusted candidates within configured bounds | Global synchronized membership or a consensus electorate |
| Service refresh | A policy-selected target for eligible future calls | Replay or undo of already-entered business code |
| Safe predispatch retry | Another attempt when policy proves the qualified failure is safe | General retry after an uncertain external effect |
| Durable deduplication | Retained logical request identity and canonical input/result contract | Atomicity with arbitrary external systems |
| Queue claim/receipt | Current ownership of a native delivery | Exactly-once handler execution under process failure |
| JDBC inbox | Marker and application DB effect on one actual Connection/transaction | A single transaction with the queue ACK |
| JDBC outbox | Durable publication intent with the application's DB effect | One atomic DB-plus-native-queue commit |
| Fenced ownership | Rejection of stale authority by the cooperating protected resource | Fencing of arbitrary HTTP, filesystem or JDBC writes |
| Event cursor | Durable acknowledged position within retention semantics | Infinite retention or acknowledgment before physical handling |
| Job checkpoint | Explicit encoded safe-point state under current authority | Serialization of a Java stack or arbitrary heap |
| Saga compensation | Durable decisions and declared inverse actions | A guaranteed inverse for every real-world effect |

## Logical outcome and physical lifetime

The caller can receive success, failure, timeout or cancellation independently of when all tracked physical work exits. Admission permits, classloaders and owned child tasks must remain associated with that physical lifetime. A cancelled future is not evidence that a database driver or external service stopped.

## UNKNOWN is meaningful

A lost response can leave an operation's outcome unknown. Preserve the original durable identity where the protocol supports retry/recovery. For an entered external effect, use idempotency or authorized reconciliation. Starting a fresh identity to avoid an uncertain receipt can repeat the business effect.

For sagas, track whether uncertainty concerns the forward operation or compensation. Reconciliation observes a version and must not apply a stale decision to newer state. A missing inverse is not successful compensation.

## Ordering, partitions and clocks

Ordering is scoped by the configured key/partition/program contract. It is not implicit global FIFO across all services or queues. A current owner pins its partition epoch; migration can revoke that authority even if the same physical group later becomes owner again.

Lease and scheduling decisions require the configured healthy clock authority and native validation. A local estimate helps choose a wakeup; it does not replace the durable resource's current fence/expiry check.

These boundaries apply equally to [annotated applications](../annotations/index.md) and explicit API use. An annotation reduces repeated application code; it does not extend the underlying transaction or failure model.
