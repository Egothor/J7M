# Credential Rotation

`CredentialProvider` supplies a current `CredentialSnapshot` and accepts `CredentialRotationListener` registrations. This gives transports and host components an explicit notification boundary. It does not mean that every connected channel automatically replaces its TLS identity or that an external CA has revoked the old credential.

## Provider contract

This host fragment attaches an observer to an existing production provider. The callback must avoid logging credential material:

```java
credentialProvider.addRotationListener(snapshot -> {
    // Notify the host's transport refresh coordinator.
    // Retain only approved version/fingerprint metadata in diagnostics.
});
```

Use a provider that reads the deployment's authoritative credential store, validates a new snapshot and publishes it consistently. `MutableCredentialProvider` offers an in-memory implementation for tests and local demonstrations; it is not an external secret-manager integration.

## Rotation procedure

1. Provision the next credential outside J7M and establish the required trust overlap.
2. Validate its identity, scope, validity period and trust path before publishing it.
3. Publish the provider snapshot and notify listeners.
4. Refresh/recreate the affected transport resources according to their provider contract.
5. Make new authenticated calls from another JVM; check approved version/fingerprint evidence and authorization results.
6. Revoke or retire the previous credential only after the intended clients can authenticate.

If the check fails, restore the last trusted provider snapshot and reconnect/recreate affected resources. Revoke the rejected credential through the external authority. Preserve safe rotation and failure evidence. A listener callback alone proves neither successful reconnection nor zero downtime.

## Administration reconnect

The Nugget CLI's `reconnect` resolves the selected exact Nugget identity again and validates the refreshed connection. It must not silently select a same-name replacement or replay an uncertain lifecycle mutation. Reauthenticate when the refreshed session requires admission. See [CLI](../reference/cli.md) and [incident recovery](incident-recovery.md).
