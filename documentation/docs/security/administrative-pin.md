# Administrative PINs

A Nugget administrative PIN proves admission to an administration session. Authorization still decides which status, objects, applications and lifecycle actions that session may access. A discovery record is an untrusted location hint, not admission or authorization.

## Supply one source

When administration is enabled, configure exactly one active PIN source. Zero sources, multiple sources, a blank or oversized value, malformed verifier data, an inaccessible source or a policy-rejected source must fail startup. There is no default PIN. Prefer a protected secret provider or file; command-line values are exposed to process inspection. Explicit developer-only generation is not a production provisioning mechanism.

For an operator session, the CLI can obtain a PIN from a named environment variable without printing it:

```bash
j7m nugget status --target crawler-east --pin-env J7M_ADMIN_PIN --json
```

This command requires an installed Nugget CLI client provider and an unambiguous reachable target. Set `J7M_ADMIN_PIN` through the deployment's secret mechanism; do not put its value in a script, example command or support bundle. See [CLI targeting and reconnect](../reference/cli.md).

## Verifier and protected files

The `j7m-pin-v1` verifier uses `PBKDF2WithHmacSHA256`, a randomly generated 16-byte salt and a 32-byte derived verifier. Accepted iteration counts are 210,000–1,000,000. PIN input is limited to 256 UTF-8 bytes and the serialized verifier record to 1,024 UTF-8 bytes. Parsing rejects unsupported versions/algorithms, malformed encoding, duplicate or extra fields, invalid lengths and out-of-policy work factors. Verification compares fixed-length derived values in constant time.

Use the runtime's protected-file mechanism rather than inventing a verifier format. Provision an owner-controlled regular file and directory, restrict access, and reject symbolic links or unsafe file attributes. Protected updates write a restrictive temporary file in the same directory, flush it, recheck attributes and replace atomically. A failed update must preserve the previous usable verifier; there is no truncate-in-place fallback.

Persistent verifier replacement becomes active only after successful persistence. A restart must reopen that persistent source to retain the replacement. A provider or environment source follows its own provisioning lifecycle; in-memory changes are not a substitute for updating that source.

## Failed attempts and session controls

Admission throttling uses bounded, concurrency-safe in-memory buckets, monotonic time, a 15-minute failure window and a maximum delay of 60 seconds. It rejects/throttles callers without sleeping or queueing them. Untrusted remote metadata cannot create unlimited distinct buckets; excess keys share a bounded overflow bucket. Successful authentication clears only its matching bucket. Restart clears the in-memory throttle state, so network controls remain necessary.

Shutdown and kill use short-lived one-use challenges bound to the exact action, caller, Nugget/runtime generation and control state. A challenge does not bypass authorization or enable a disabled kill capability. Hidden objects remain hidden in read-only queries and counts.

Never export a PIN, verifier, salt, derived material, confirmation challenge or sensitive source path into status, logs or evidence. Protect environment snapshots and process diagnostics with the same care as the source itself.
