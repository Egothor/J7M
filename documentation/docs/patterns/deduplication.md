# Safe retries and durable deduplication

**Use case:** a frontend submits an order command, loses the reply and needs to discover the original outcome without applying the command a second time.

=== "With annotations"

    ```java
    @Deduplicated(policy = "orders")
    public String confirm(@IdempotencyKey String commandId, String orderId) {
        return orderService.confirm(orderId);
    }
    ```

    The host supplies the native durable invocation policy, namespace, tenant binding, schema and finite retention profile. `orderService` must follow the profile's permitted effect protocol.

=== "Explicit API"

    ```java
    ExecutionResult accepted = rpc.invoke(
            "confirm-42", "order-command-42", canonicalInputSha256,
            60_000, "confirmed", "",
            List.of("order-42", "0", "confirmed"), deadline);
    ExecutionResult observed = rpc.status("order-command-42", deadline);
    ```

    Here `rpc` is an authorized `DeduplicatedRpc`. This native operation records the result and one same-partition protected CAS triple atomically. `canonicalInputSha256` is the stable digest of the business input. For non-atomic computation/external work, use its explicit claim/complete/UNKNOWN protocol instead of computing an effect before this call.

## Key discipline

Reuse the original key and identical input after a lost reply. A different payload under the same logical key is a conflict, not an update. Do not derive the business key from a new receipt, transport attempt, member or current generation.

`@Idempotent` is a declaration of safe-repeat behavior. Durable deduplication records an operation identity and outcome in a durable protocol. These solve related but different problems. A pre-business OPEN retry cannot recover a business effect whose reply was lost after entry.

## Effects and retention

Only effects inside the qualified native atomic boundary can be committed with the recorded result. External effects require an idempotent external protocol, same-Connection inbox, outbox or reconciliation. UNKNOWN must remain visible when the durable protocol cannot establish an outcome.

Configure namespace, canonical codec, retained count/bytes and expiry policy. Full retention returns explicit capacity outcomes rather than silently forgetting old identities and authorizing a second effect. See [distributed resources](../configuration/distributed-resources.md).

## Worked scenario: preserve the command identity after a lost reply

An order frontend submits a confirmation through this public contract:

```java
public interface Orders {
    @Deduplicated(policy = "order-commands")
    String confirm(@IdempotencyKey String commandId, String orderId);
}
```

The host installs the durable replay policy with stable namespace, tenant and method identity. The application stores the business command identity before sending:

```java
String commandId = "confirm:order-42";
String orderId = "42";
String outcome = orders.confirm(commandId, orderId);
System.out.println("Confirmation: " + outcome);
```

If the reply is lost, recover using the same `commandId` and canonical input. Once the original durable outcome is known, identical replay can return it instead of entering the body again. A concurrent frontend using the same command does not gain permission to execute a second external effect.

Do not change the input to `"43"` under the same key; conflicting reuse is rejected. Do not invent a new key to get past an uncertain outcome. The business body must use the supported protected-effect protocol or an external idempotency/reconciliation mechanism. A durable intent record alone cannot establish that an arbitrary HTTP payment happened exactly once.

Retain replay evidence for the application's retry window. After restart, the durable backend's history—not the frontend's in-memory cache—provides the recorded result.
