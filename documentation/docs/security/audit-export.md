# Audit Verification and Retention

Tamper-evident records detect changes within a retained chain. Immutable storage, durable retention and independent custody are separate operational requirements.

`TamperEvidentAuditSink` is a bounded in-memory implementation. Each record carries a sequence number, previous/current SHA-256 hashes and an optional checkpoint hash. Capacity eviction preserves only a recent window; process termination loses the in-memory records. A valid retained window does not prove that older records were retained, that the first record is independently trusted, or that an attacker did not replace the entire chain.

## Verify one consistent snapshot

This Java fragment assumes `sink` is the host's `TamperEvidentAuditSink`. Taking one immutable snapshot avoids verifying one window and exporting another while events arrive:

```java
var retained = sink.chain();
var verification = TamperEvidentAuditSink.verify(retained);
if (!verification.valid()) {
    throw new IllegalStateException("Audit chain verification failed");
}
// Pass retained to the host's durable, access-controlled archive writer.
```

Verification checks record shape, contiguous sequences, hash linkage and each calculated record hash. `sink.exportChain()` provides compact newline-delimited sequence/hash/time/action/decision text for the currently retained window. It is not a full portable event archive, and it takes its own snapshot. Preserve complete records and a trusted checkpoint when independent re-verification is required.

## Export procedure

1. Record the source identity, collection interval, first/last sequence and retention capacity.
2. Capture one snapshot and verify its chain before export.
3. Check redaction of credentials, authentication envelopes, arguments and protected paths.
4. Export evidence in the required JSON, Markdown or CSV format through the evidence APIs, or use a full-record archive appropriate to the sink.
5. Store the export and checkpoint in an independently protected destination; record its reference, digest, retention deadline and access policy.
6. Test retrieval and re-verification. Export frequently enough to avoid silent loss through bounded-window eviction.

Attach a production `WormAuditSink` integration for external immutable storage. The bundled sink does not configure a WORM appliance, legal hold, SIEM, object-lock policy or external archive. Define those controls with the storage/service operator.

Repository publication has a separate persistent audit chain. Its bounded status summary is not a full verification; use [repository audit verification](../deployment/repositories.md) explicitly. Preserve the source and destination chains separately during replication.
