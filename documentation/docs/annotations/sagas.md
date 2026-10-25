# Saga annotations

A saga records forward decisions and compensations across an application workflow. It does not provide a global rollback transaction for arbitrary external systems.

## @SagaDefinition {#sagadefinition}
Declares a workflow holder. **Target:** type. **Attributes:** required `name`; `version = 1`, `policy = "default"`.

```java
@SagaDefinition(name = "checkout", version = 1, policy = "checkout-sagas")
public final class CheckoutWorkflow {
    public CheckoutWorkflow() { }
    // Forward, inverse and reconciliation callbacks are declared here.
}
```

The trusted host installs the same `ManagedSagaFactory` as factory and required policy. The verified artifact and semantic definition fingerprint identify the exact program allowed to resume persisted work. Definition metadata cannot grant administrative authority.

## @SagaStep {#sagastep}
Declares an ordinary typed forward callback. **Target:** method. **Attributes:** required `name` and zero-based `order`; `idempotent = false`.

```java
@SagaStep(name = "reserve", order = 0, idempotent = true)
public String reserve(String orderId) {
    return reservationGateway.reserve(orderId, sagaContext.get().idempotencyKey());
}
```

Here `sagaContext` is the authorized `ManagedProvider<ManagedSagaContext>` resolved inside the callback, and the application gateway must honor the supplied key. A parameter such as `orderId` is not automatically the complete external key. Orders are contiguous from zero. The profile supports a bounded chain of one to seven steps.

## @Compensation {#compensation}
Declares an inverse for a named forward step. **Target:** method. **Attributes:** required `step`; `idempotent = false`.

```java
@Compensation(step = "reserve", idempotent = true)
public void releaseReservation(String originalInput) {
    reservationGateway.release(originalInput, sagaContext.get().idempotencyKey());
}
```

The inverse receives its forward step's original input. Completed steps compensate in reverse order. An absent inverse does not mean successful undo: the workflow exposes the uncertainty. Mark idempotency only when the external protocol truly honors the context's stable compensation key.

## @ReconciliationHandler {#reconciliationhandler}
Declares the callback that evaluates independent evidence for an uncertain effect. **Target:** method. **Attributes:** none.

```java
@ReconciliationHandler
public ManagedSagaDecision reconcile(ManagedSagaUncertainty uncertainty) {
    return new ManagedSagaDecision(
            ManagedSagaDecision.Outcome.UNRESOLVED, null);
}
```

Returning UNRESOLVED is correct when evidence is insufficient. The other decisions are `SUCCEEDED` and `FAILED_NO_EFFECT`. The host requires an explicit administrative capability and commits a decision against the observed run version. A stale proposal cannot resolve a later inverse attempt. Successful administrative resolution records a separate payload-free audit projection.

## Example: reserve, then arrange shipment

A checkout workflow performs two forward steps. The reservation gateway must honor the stable saga idempotency key; the host provides the current `ManagedSagaContext` binding. These methods belong in the `CheckoutWorkflow` holder above:

```java
@SagaStep(name = "reserve", order = 0, idempotent = true)
public String reserve(String orderId) {
    return reservationGateway.reserve(
            orderId, sagaContext.get().idempotencyKey());
}

@SagaStep(name = "ship", order = 1, idempotent = true)
public String ship(String reservationId) {
    return shippingGateway.arrange(
            reservationId, sagaContext.get().idempotencyKey());
}

@Compensation(step = "reserve", idempotent = true)
public void releaseReservation(String orderId) {
    reservationGateway.release(
            orderId, sagaContext.get().idempotencyKey());
}
```

Here both gateways are application-owned integrations and `sagaContext` is the provider described above. Successful reservation returns the reservation ID used by the shipping step. If shipping fails without an unresolved effect, compensation receives the reservation step's original order ID and releases it. Compensation does not receive whichever value the latest step happened to return.

If a gateway times out after accepting work, do not guess that it failed. A `@ReconciliationHandler` inspects independent gateway evidence and proposes `SUCCEEDED`, `FAILED_NO_EFFECT` or `UNRESOLVED`. With no reliable evidence, the UNRESOLVED implementation above is the correct safe behavior. Authorized resolution is checked against the observed run version before continuing. See [saga operation and recovery](../patterns/sagas.md).
