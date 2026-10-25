# Production Hardening

Treat a production security profile as a deployment requirement, then check it against the host's actual configuration. `SecureConfigurationProfile.productionBaseline()` describes the desired posture; it does not enable TLS, install a verifier or attach an audit destination.

The runtime's `SecureConfigurationValidator` evaluates an explicit profile and returns findings with stable control identifiers. This host fragment assumes `observedProfile` was assembled from the actual transport, registry, policy and audit configuration:

```java
var validation = new SecureConfigurationValidator().validate(observedProfile);
if (!validation.valid()) {
    throw new IllegalStateException("Deployment security requirements are not met");
}
```

Record the findings separately using a safe operational report. Do not place credential material or private configuration values in that report.

## Required controls

| Check | Deployment obligation |
|---|---|
| Production mode | Explicitly select production mode and disable insecure gRPC. |
| Mutual TLS | Configure mTLS when required; validate peer trust and intended endpoint identity. |
| Registry trust | Install the required registry verifier for strict trust and signed records. |
| Replay and timestamps | Enable replay protection and timestamp validation; operate within the configured clock tolerance. |
| Audit | Attach an audit sink and tamper-evident recording; establish durable external retention. |
| Redaction | Enable sensitive-data redaction throughout diagnostics and evidence. |
| Resource protection | Configure limits and a resource policy, including overload behavior. |
| Administration | Protect status/introspection as well as lifecycle mutations. |

Missing required controls produce errors. Missing an external PDP, evidence export or credential rotation produces warnings. `valid()` means there were no validator errors; it is not a penetration test, certification, certificate validation or proof that the declared facts match the deployed host.

## Deployment checks

1. Use separate credentials and trust configuration for service invocation, registry records, application bundles and administration.
2. Restrict inbound listeners and outbound destinations. Bind advertised endpoints to reachable, intended addresses.
3. Provision private durable directories with exclusive ownership and sufficient disk space.
4. Exercise a denied request and verify it is rejected before work starts, including cached and replayed paths.
5. Verify an authenticated cross-JVM call, credential rotation, audit export and recovery before admitting production traffic.

For the individual mechanisms, see [identity and trust](identity-and-trust.md), [administrative PINs](administrative-pin.md), [audit retention](audit-export.md), [credential rotation](credential-rotation.md) and [isolation providers](isolation-providers.md).
