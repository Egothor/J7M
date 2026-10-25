# Leases, leadership and fenced ownership

**Use case:** one active worker may update a protected resource, but an old worker can continue running after a pause, network split or lease expiry. A fence must be enforced where the write happens.

=== "With annotations"

    ```java
    @LeaseGuard("accounts")
    public long revise(@ShardKey String account) {
        return ownership.get().write("effect-42", account, "revised");
    }
    ```

    `ownership` is an authorized `ManagedProvider<ManagedOwnershipContext>`. The host installs the ownership policy/profile and maps the actual authenticated invocation to an allowed tenant.

=== "Explicit API"

    ```java
    ManagedOwnershipContext current = ownership.get();
    long fence = current.fence();
    long version = current.write("effect-42", "account-7", "revised");
    ```

    This is explicit use of an already admitted ownership capability. A lower-level worker must acquire/renew its lease and carry the native fence to every protected mutation itself; merely reading a fence does not lock an unrelated database.

## Leadership is not a background scheduler

`@LeaderTask` gates authority for an invoked method. It does not start a periodic task by itself. Combine it with an appropriate explicit trigger if the application requires scheduled leadership work.

## Revocation and physical lifetime

Renewal uncertainty, expiry, cancellation or binding close revokes the capability. The managed context stays pinned to its captured group and epoch; it never follows a handoff while an old callback remains live. A handler may still execute Java after revocation, but native protected writes reject it.

Physical drain still waits for real work. A timeout cannot safely release the callback's component or class loader while it is in use. External effects need their own fencing/idempotency support.

Configure lease, renewal, operation/cleanup budgets and finite effects. See [resource configuration](../configuration/distributed-resources.md).

## Worked scenario: reject an old worker after takeover

An account service performs its mutation through the injected ownership context:

```java
@LeaseGuard("accounts")
public long revise(@ShardKey String account) {
    ManagedOwnershipContext current = ownership.get();
    return current.write("revise:account-42", account, "revised");
}
```

`ownership` is the authorized provider declared above. The host's `accounts` profile maps the authenticated tenant and route to a native protected resource.

Suppose worker A pauses long enough to lose renewal. Its local context is revoked and the resource eventually grants B a higher fence. A may still resume its Java method, but its old protected write is rejected. B can write using its own admitted context. Killing A is not required for that resource-side rejection.

Treat revocation as a failed ownership operation, stop issuing protected work, and let the host drain actual physical execution. Do not catch the rejection and write through an unfenced database connection instead.

The stable request ID identifies one intended revision. A real service derives it from its business command instead of using the fixed illustrative string for every revision. Leadership-gated maintenance uses `@LeaderTask` with the same enforced-authority principle and a separate explicit trigger.
