# Security Guide

J7M places enforcement at the host, transport, application boundary and durable-resource boundary. An application annotation describes a requirement or selects an authorized policy; it cannot manufacture trust, credentials or isolation.

Start with [identity and trust](identity-and-trust.md), configure [TLS and certificates](tls-and-certificates.md), then establish [application signing and secret handling](signing-and-secrets.md). Review [isolation and audit](isolation-and-audit.md) against the actual deployment boundary.

| Boundary | What must be established |
| --- | --- |
| Transport | Authenticated peer, appropriate confidentiality and a bounded protocol |
| Registry | Expected context plus valid, live publisher records under a configured verifier |
| Application activation | Verified artifact, allowed capabilities, policy identities and module origins |
| Invocation | Authenticated caller, required role/permission/trust domain and current context |
| Native resource | Server-bound principal/scope, current receipt/fence/epoch and permitted operation |
| Administration | Explicit administrative admission and permission, separate from application service access |

Security controls must remain active on cache hits, replayed durable results and generated adapter paths. A previously successful result does not grant a newly unauthorized caller access to it. Likewise, transport authentication does not prove application-artifact provenance.

The deployment owner must choose trust roots, credential sources, key rotation procedures and host/process isolation. Development defaults are not a production security profile. The examples explain the middleware mechanisms; they do not replace an environment-specific security assessment.

## Operational security

- [Production hardening](production-hardening.md): validate declared deployment controls against observed configuration.
- [Administrative PINs](administrative-pin.md): source selection, protected verifier persistence, throttling and confirmation.
- [Credential rotation](credential-rotation.md): provider notifications, transport refresh and rollback.
- [Isolation providers](isolation-providers.md): actual enforced, policy-only and advisory boundaries.
- [Audit verification and retention](audit-export.md): retained-chain checks and external immutable storage.
- [Security incident recovery](incident-recovery.md): registry poisoning, emergency deny and restored trust.
- [Enterprise integrations](enterprise-integrations.md): evidence, crypto, external policy and approval contracts.
