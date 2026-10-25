# Security and observability annotations

These declarations select trusted host policies and describe public contract requirements. They never supply caller credentials, install a security provider or make an ObjectRef an authorization token.

## @RequiresRole {#requiresrole}
Declares a required caller role. **Targets:** type and method. **Required attribute:** `value`.

```java
@RequiresRole("order-reader")
String readOrder(String orderId);
```

The configured authorization policy checks the actual authenticated principal. A role string in a request or an application's annotation is not proof of membership.

## @RequiresPermission {#requirespermission}
Declares a required action and optional resource prefix. **Targets:** type and method. **Attributes:** required `value`; `resource = ""`.

```java
@RequiresPermission(value = "read", resource = "orders/")
String readOrder(String orderId);
```

Use stable public identifiers. The host resolves precedence and enforces the policy before protected execution or replay. Do not place credentials in action/resource strings.

## @RequiresTrustDomain {#requirestrustdomain}
Declares the required caller trust domain. **Targets:** type and method. **Required attribute:** `value`.

```java
@RequiresTrustDomain("operations")
String inspectJob(String jobId);
```

The value constrains the trusted security context. It does not create a trust domain, enroll a peer or bypass role/permission checks.

## @Confidential {#confidential}
Declares confidential-handling requirements. **Targets:** type and method. **Attributes:** none.

```java
@Confidential
String customerSummary(String customerId);
```

The managed security integration validates supported confidentiality requirements against actual capabilities. TLS, safe codecs, logging and authorization must still be configured. The marker alone does not enable encryption or suppress an unrelated logger.

## @Sensitive {#sensitive}
Marks a value or contract for redaction-aware consumers. **Targets:** parameter, field, method and type. **Attributes:** none.

```java
void verify(@Sensitive String accountToken);
```

Redact before data reaches logs, diagnostics, status and audit. This is not encryption, and arbitrary application code can still leak a value if it ignores the declaration.

## @Audit {#audit}
Selects a registered audit policy. **Targets:** type and method. **Attribute:** `policy = "default"`.

```java
@Audit(policy = "order-decisions")
String approve(String orderId);
```

The host supplies a safe sink and the enforcing policy boundary. Annotation presence alone does not persist an event. Keep event dimensions bounded and payload-free where the operation requires it; saga reconciliation has its own explicit administrative audit contract.

## @Observed {#observed}
Selects application observation policy. **Targets:** type and method. **Attribute:** `value = "default"`.

```java
@Observed("order-operations")
String approve(String orderId);
```

The managed observer uses the actual invocation lifetime and avoids a duplicate middleware span. Host policy bounds label cardinality and safe metadata. Do not use arbitrary customer IDs or payload values as metric labels.

## @IsolationProfile {#isolationprofile}
Selects an installed isolation/resource profile. **Targets:** type and method. **Required attribute:** `value`.

```java
@IsolationProfile("bounded-order-worker")
public final class OrderWorker { }
```

The actual provider reports which controls are enforced, advisory, policy-only or unsupported. The annotation is not an OS/container sandbox for hostile Java code.

## Example: protect an approval boundary

An approval service exposes only order IDs and safe outcomes:

```java
public interface Approvals {
    @RequiresRole("order-approver")
    @RequiresPermission(value = "approve", resource = "orders/")
    @RequiresTrustDomain("operations")
    @Confidential
    @Audit(policy = "order-decisions")
    @Observed("order-operations")
    String approve(String orderId);

    void verify(@Sensitive String accountToken);
}
```

The host admits this contract only with the required authorization, confidentiality, audit and observation capabilities. An authenticated operations caller with the role and permission can invoke `approve("42")`; a caller missing any enforced requirement is denied before the protected operation. The application returns a safe value such as `approved`, while the configured audit sink records the decision without serializing the customer's order.

Use bounded operation names as observation dimensions rather than order IDs. The token parameter is sensitive input: avoid printing it in the implementation, exception messages or logging wrappers. The declaration cannot undo a leak caused by application code.

Place `@IsolationProfile("bounded-order-worker")` on the worker selected for that host profile. Inspect its actual provider capabilities before relying on memory/process controls; the profile name alone does not establish an OS sandbox. See [security configuration](../security/index.md).
