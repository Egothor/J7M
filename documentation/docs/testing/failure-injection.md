# Deterministic clocks and failure injection

Inject the failure at a named protocol boundary and assert both the immediate result and the recovered state. A random process kill without an oracle is not a recovery test.

| Boundary | Injected failure | Required observation |
| --- | --- | --- |
| Before business entry | Connection/OPEN failure | Qualified retry can be bounded without entering twice |
| After external effect, before result storage | Worker/frontend termination | UNKNOWN or externally established result; no blind replay |
| After durable completion, before reply | Leader/frontend loss | Identical request returns the stored result/effects |
| After SQL commit, before ACK | Consumer termination | Inbox marker prevents a second SQL effect |
| After native SEND, before outbox mark | Relay termination | Identical SEND ID is recovered |
| After pending schedule metadata, before submit | Scheduler termination | Same planned occurrence is submitted |
| After submit, before cursor advance | Scheduler termination | Same submission is recovered, not a new occurrence |
| After ownership handoff | Old worker continues | Protected effects reject its old epoch/fence |

## Time is part of the protocol

Use injected clocks for pure scheduling/expiry decisions. Native lease and scheduling integration must also test the authenticated time/uncertainty contract. A local timer firing is not fresh ownership evidence. Test clock loss, backward movement and DST gaps/folds explicitly where the profile supports them.

## Synchronize on actual boundaries

Use latches, committed receipts or explicit child-process control messages. Do not use a longer sleep to hide a race. If a parent treats a marker file's existence as publication, the writer must publish the complete marker atomically; creation followed by incremental write does not provide that barrier.

## Source examples

`ManagedDeduplicationProcessIT` (repository source: `j7m-application-distributed/src/test/java/org/j7m/middleware/application/distributed/ManagedDeduplicationProcessIT.java`), `ManagedJobProcessIT` (repository source: `j7m-application-distributed/src/test/java/org/j7m/middleware/application/distributed/ManagedJobProcessIT.java`), `ManagedSagaProcessIT` (repository source: `j7m-application-distributed/src/test/java/org/j7m/middleware/application/distributed/ManagedSagaProcessIT.java`), `JdbcQueueProcessIT` (repository source: `j7m-application-distributed/src/test/java/org/j7m/middleware/application/distributed/JdbcQueueProcessIT.java`), `DurableSchedulesProcessIT` (repository source: `j7m-application-distributed/src/test/java/org/j7m/middleware/application/distributed/DurableSchedulesProcessIT.java`), `ManagedOwnershipMigrationIT` (repository source: `j7m-application-distributed/src/test/java/org/j7m/middleware/application/distributed/ManagedOwnershipMigrationIT.java`) and `ManagedPartitionMigrationIT` (repository source: `j7m-application-distributed/src/test/java/org/j7m/middleware/application/distributed/ManagedPartitionMigrationIT.java`) provide concrete recovery oracles. Their scopes differ: read the fixture and the asserted profile before reusing a result as evidence for another deployment.
