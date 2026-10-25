# Identity, Permissions and Trust Domains

Identify the actor at each boundary. A human administrator, application caller, registry publisher, artifact publisher and native-resource principal are different identities even if one deployment provisions them together.

Managed `@RequiresRole`, `@RequiresPermission` and `@RequiresTrustDomain` declarations are enforced through the installed managed security policy and actual invocation boundary. The host chooses that policy. An application cannot bypass it by omitting a Java interface or using a generated POJO callback.

`@CurrentPrincipal` and the current invocation context expose the identity already established by the host. Do not accept a tenant or role string supplied as an ordinary business argument as proof of authority. A durable resource server's immutable principal binding determines its actual tenant/scope and administrative permissions.

## Registry trust

Use a configured `RegistryRecordVerifier` and strict received-record policy for remote discovery. Match the registry context, publisher signature, generation/freshness and lease. An unverified remote record may be rejected or downgraded according to policy; it must not silently acquire trusted-candidate status.

Replay checks can reject an identical signed heartbeat after a cache miss until a fresh valid publication arrives. Preserve the publisher's heartbeat/lease lifecycle instead of disabling freshness checks to hide an operational problem.

## Administrative admission

Nugget administration has explicit PIN-source and security configuration. Prefer a protected file/provider or approved environment-source mechanism over clear command arguments. The public `NuggetAdminRuntimeConfiguration` builder supports these choices; status and diagnostic views redact sensitive source material.

```java
NuggetAdminRuntimeConfiguration admin =
        NuggetAdminRuntimeConfiguration.builder()
                .nuggetName("orders-host-1")
                .security(security)
                .pinFromProtectedFile(Path.of("/run/secrets/j7m-admin-pin"))
                .build();
```

This fragment assumes `security` is the deployment's real `SecurityConfiguration` and that the host can securely read the protected file. It does not install a remote administration transport. The CLI's provider-backed nugget connection must also establish the required connection security and authorization.

Use separate least-privilege identities for application resource access, maintenance and administration. Revoking a client must affect future authorization; it must not rely only on whether a cached proxy still exists.

**Sources:** security configuration (repository source: `j7m-api/src/main/java/org/j7m/middleware/security/api/SecurityConfiguration.java`), administrative runtime configuration (repository source: `j7m-runtime/src/main/java/org/j7m/middleware/runtime/NuggetAdminRuntimeConfiguration.java`).
