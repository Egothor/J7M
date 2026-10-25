# Sagas, compensation and UNKNOWN reconciliation

**Use case:** checkout reserves inventory and arranges payment. The systems cannot share a local database transaction, so the workflow must persist decisions and compensate completed steps where possible.

=== "With annotations"

    ```java
    @SagaDefinition(name = "checkout", policy = "checkout-sagas")
    public final class CheckoutWorkflow {
        private final ManagedProvider<ManagedSagaContext> context;
        private final ReservationGateway gateway;
        @Inject
        public CheckoutWorkflow(@J7mResource("saga") ManagedProvider<ManagedSagaContext> context,
                                ReservationGateway gateway) {
            this.context = context;
            this.gateway = gateway;
        }
        @SagaStep(name = "reserve", order = 0, idempotent = true)
        public String reserve(String orderId) {
            return gateway.reserve(orderId, context.get().idempotencyKey());
        }
        @Compensation(step = "reserve", idempotent = true)
        public void release(String originalInput) {
            gateway.release(originalInput, context.get().idempotencyKey());
        }
        @ReconciliationHandler
        public ManagedSagaDecision reconcile(ManagedSagaUncertainty uncertainty) {
            return new ManagedSagaDecision(ManagedSagaDecision.Outcome.UNRESOLVED, null);
        }
    }
    ```

    `ReservationGateway` is an application-specific external protocol, supplied as an ordinary dependency. It must actually send the provided key to an endpoint that honors it. The complete host supplies the authorized saga context resource and exact saga factory/profile.

=== "Explicit API"

    ```java
    ExecutionResult started = sagas.start("start-42", "checkout-42",
            inputFrame, 3, 1000, deadline);
    ExecutionResult current = sagas.status("checkout-42", deadline);
    ```

    `sagas` is an authorized `DurableSagas` client. An explicit runner records claim, fresh authority, step intent/result and compensation using the native protocol. Managed hosting removes this repeated dispatch/ownership machinery from the business class.

A saga is not an ACID transaction and does not provide generic exactly-once external effects. Compensation is a separately recorded business operation whose outcome can also be uncertain.

## Compensate decisions, not elapsed time

Completed forward steps are compensated in reverse order. The inverse receives the original forward input. A missing inverse is visible UNKNOWN rather than a successful undo. A failed or uncertain inverse must be resolved before earlier inverses proceed.

Durable intent does not prove an external effect happened, and a lost reply does not prove it did not. Non-idempotent uncertainty remains UNKNOWN. A separately authorized reconciliation path commits independent evidence against the observed durable run version and records safe audit data.

## Configuration and verification

Install the same `ManagedSagaFactory` as hosted factory and required policy. Pin namespace, definition/version, allowed data graph, verified program fingerprint and finite lease/budgets. Ordinary clients must not inherit reconciliation administration. See [security](../security/index.md).

## Worked scenario: reconcile an uncertain reservation release

The checkout workflow above reserves inventory using the stable saga key and has a matching release compensation. The gateway is an application integration with idempotent commands and an independent status query.

If a release request times out after the remote inventory system accepted it, the replacement workflow must determine whether it happened. A reconciliation callback can use that system's independently stored result:

```java
@ReconciliationHandler
public ManagedSagaDecision reconcile(ManagedSagaUncertainty uncertainty) {
    String recorded = gateway.findResult(uncertainty.idempotencyKey());
    if (recorded == null) {
        return new ManagedSagaDecision(
                ManagedSagaDecision.Outcome.UNRESOLVED, null);
    }
    return new ManagedSagaDecision(
            ManagedSagaDecision.Outcome.SUCCEEDED, recorded);
}
```

`gateway.findResult` is an application-defined read-only query, not another attempt to release inventory. It must return authoritative evidence for the exact stable operation key. A missing result does not by itself prove no effect; this implementation leaves that case unresolved.

The host grants reconciliation separately and checks the observed durable run version when committing the decision. After the inverse is known to have succeeded, earlier compensations can proceed in reverse order. Until then the application displays a pending manual/reconciliation state rather than claiming rollback completed.

If the external system cannot provide reliable evidence or honor stable keys, keep the uncertainty visible and use its supported operator procedure. Saga persistence cannot manufacture that external guarantee.
