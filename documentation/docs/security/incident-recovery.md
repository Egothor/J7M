# Security Incident Recovery

Separate containment from restoration of trust. A reachable registry or authenticated channel does not by itself establish that all previously advertised instances, credentials or application artifacts remain acceptable.

## Registry poisoning or compromise

1. Isolate the suspected registry publisher/endpoint and preserve redacted audit, trust decisions and registry event evidence.
2. Stop admitting new candidates from the affected trust domain. Revoke or deny the compromised identity at the enforcing boundary.
3. Inspect signed-record verification, identity bindings, timestamps, replay state and allowed publishers. Do not restore trust by accepting unsigned replacement records.
4. Remove or expire poisoned records through authorized administration and republish independently verified instances.
5. Re-resolve clients and verify authenticated invocation against intended endpoint identities. Check that stale references and revoked identities remain rejected.
6. Restore traffic gradually; retain the incident window and explicit evidence of the restored trust configuration.

Discovery candidates cannot override a local application's visibility or upgrade its authorization. Do not copy raw registry metadata into public status reports as a shortcut for diagnosis.

## Emergency denial and recovery

Prefer a scoped deny/revocation to a global bypass. Record the affected identity, namespace, action, effective time and recovery owner. Any break-glass exception needs explicit scope, expiry and audit; it must not disable unrelated resource or transport checks.

Before removing the deny, correct the underlying credential/trust/policy problem and verify a positive authorized request and a negative unauthorized request. Confirm that cached proxies, registry refresh and reconnect paths apply the restored policy. Use [credential rotation](credential-rotation.md) for replacement and [backup/recovery](../operations/recovery.md) if durable state requires restoration.

## Evidence to retain

Retain correlation/request identities, safe principal and resource identifiers, policy decisions, affected artifact digests, clock/sequence information, containment changes and recovery checks. Export through the approved [audit retention](audit-export.md) path. Exclude clear credentials, PIN material, confirmation challenges, payloads and sensitive environment/process values.
