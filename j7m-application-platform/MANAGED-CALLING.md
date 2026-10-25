# Managed calling policies

A-P07 K/I/V passed for the documented profile on 2026-10-24. Independent evidence is in `INBOX/A-P07-A-P17-OVERENI/ap07-independent/`. This is scoped feature acceptance; final aggregate acceptance remains at A-P17. PMD is deferred until the final A-P17 verification, as requested.

## Host installation

Install `ManagedCallingPolicy` in the ordered `ManagedHostingOptions.policies` list on the trusted ApplicationManager host. A bundle descriptor cannot enable it. Existing two- and three-argument hosting constructors retain default invocation settings. Active unsupported calling metadata, conflicting interface declarations and implementation-only policies fail before application constructors. Public interface signatures must carry the calling contract visible to clients. Ordinary Java self-invocation does not create a middleware interception boundary.

`ManagedInvocationSettings` bounds host workers, pending actors and per-invocation tasks. Defaults are eight workers, 256 pending actors, 1024 total child/codec admissions and 128 pending tasks per invocation. Capacity rejection has no fallback thread. These are host-local limits, not cluster-wide semaphores.

## Asynchronous results and context

`@Async` supports concrete `CompletionStage<T>` and `CompletableFuture<T>` remote declarations. Raw stages, wildcards, type variables, custom future subclasses and nested future payloads are rejected. `T` uses the existing codec allowlist; the annotation grants no schema capability. The dedicated gRPC InvokeAsyncV2 method encodes T rather than a future object. An old peer fails before dispatch; no legacy fallback is attempted. Async exports require trusted physical ownership installed on the prepared registration before commit.

Use `J7mContext.currentInvocation().control().submit(callable)` for host-tracked child work. Return its stage rather than blocking on a queued child from inside the root CALL. Root, tracked tasks and encoding continuations share serial access to CALL values. Waiting for a result happens outside CALL/context ownership. Unhandled failures from void contextual-executor tasks fail the invocation before result finalization; explicit submit stages keep normal application composition/error-handling semantics. Null executor tasks reject synchronously. Arbitrary application executors do not inherit managed context: use `control.contextualExecutor()` for explicit continuations. The control is borrowed, host-local, nontransferable and is not a host completion-scope handle.

A public reply and physical completion are separate. Success waits for tracked work, encoding, CALL disposal and physical cleanup. Timeout/cancel may end caller waiting sooner; inflight and resource permits remain until actual completion. Cancelling an arbitrary application stage does not prove its hidden work stopped: outcome is UNKNOWN and ownership remains retained until the trusted physical contract can prove exit. Do not start untracked hidden work and use cancellation as a completion signal.

## Budget and cancellation

One monotonic budget covers selection, connector OPEN, backoff, local queues, root work and tracked children. Caller, parent control, parent wire deadline, method `@Deadline` and the finite host cap only shorten it. No refresh or retry restarts the clock. The development profile admits at most one day of remaining budget; ordinary caller defaults still apply. `@LongRunning` explicitly declares long work and never extends caller budget. `@Cancellable` declares application cooperation, not authority or a promise of termination or undo.

A cancelled client reply explicitly cancels the gRPC call. The server listener signals invocation control and detaches only after physical cleanup. It never interrupts an application worker to claim completion.

## OneWay

`@OneWay` requires void and conflicts with Async or any Retry annotation. Success means LOCAL_ENQUEUED in the caller runtime only. The existing local executor has 64 active workers and 1024 queued tasks; overflow rejects immediately. Queue waiting consumes the original budget. Worker threads have host class loaders and do not inherit application thread locals. Execution failures increment `oneWay.failureCount`; queue rejection increments `oneWay.rejectedCount` in runtime status security diagnostics. Shutdown may discard accepted pending work. There is no persistence, remote acknowledgement or durable delivery promise; use the queue adapters for those semantics.

## Safe client retry

Install a named finite `ClientRetryProfile` registry in `ManagedClientRetry`, pass the interceptor to the intended `LiveServiceBindings` and the matching `ManagedCallingPolicy` for preflight validation. The default host has no retry capability. `@Retry.maxAttempts` counts additional attempts after the first; the effective bound is the smaller annotation and host limit. Profiles permit at most 16 additional attempts and 30-second capped backoff, including jitter, within the original budget.

Retry requires Idempotent and rejects NonIdempotent conflicts. Only a private binding-owned attempt state can prove that a connector/readonly OPEN failed before business entry. The exact target, logical identity, arguments and principal stay pinned. Once business invocation starts, its failure or lost reply is returned without replay, even if application error metadata claims NOT_STARTED. Server acceptance of annotations never performs retry. Durable deduplication belongs to A-P08.
