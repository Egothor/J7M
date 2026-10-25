# Error Codes and Safe Responses

`MiddlewareException` carries a transport-neutral `RemoteError` and stable `MiddlewareErrorCode`. Inspect the code and the operation's entry/identity contract. Do not decide retry safety from arbitrary message text.

| Code | Meaning and response |
| --- | --- |
| `NOT_FOUND` | The requested service/object/record is absent; verify identity, context and lifecycle |
| `UNAVAILABLE` | A target/subsystem cannot currently provide the operation; inspect the failed boundary |
| `DEADLINE_EXCEEDED` | The operation exceeded its budget; entered effects may still need reconciliation |
| `CANCELLED` | Cancellation was observed; physical exit/undo is not implied |
| `OVERLOADED` | A finite capacity limit rejected work; apply backpressure rather than unbounded retry |
| `PERMISSION_DENIED` | Authenticated caller lacks authority; fix policy/identity through the proper owner |
| `UNAUTHENTICATED` | Caller identity was not established; repair credentials/transport admission |
| `INVALID_ARGUMENT` | Invalid options, metadata or request shape; correct input before retrying |
| `METHOD_NOT_FOUND` | The target does not expose that method |
| `INTERFACE_MISMATCH` | The expected Java contract is absent or incompatible |
| `CODEC_ERROR` | Encoding/decoding failed; inspect allowed types, schema and size |
| `REMOTE_EXCEPTION` | Entered application code threw; do not infer safe replay |
| `LEASE_EXPIRED` | The reference/record lease expired; obtain valid current state |
| `DUPLICATE_OPERATION` | The reliability contract rejected a duplicate/conflicting operation |
| `STALE_FENCING_TOKEN` | Presented ownership is obsolete; stop using that authority |
| `REGISTRY_UNAVAILABLE` | Discovery/registry could not satisfy the request |
| `TRANSPORT_ERROR` | Failure before or during transport; entry status determines retry safety |
| `LOG_CORRUPTION` | Durable record/segment integrity failed; preserve evidence and follow recovery policy |
| `DURABLE_LOG_ERROR` | Durable I/O/sync/format operation failed |
| `RECOVERY_FAILED` | Recovery could not complete safely, including explicit bounded-work failures |
| `INTERNAL` | Unexpected middleware failure; retain safe diagnostics and investigate |

Native resource replies also have domain-specific states, such as stale generation/receipt or UNKNOWN. They are not additional members of this enum. Preserve the typed/resource-specific outcome rather than converting every non-success into a new request.

Network bootstrap deliberately exposes the fixed safe `Bootstrap endpoint unavailable` error for connection/protocol failures. Its diagnostics must not leak certificate secrets, raw payloads or arbitrary exception messages. Check reachability, trust, context and bounds using safe host observations.

**Source:** MiddlewareErrorCode (repository source: `j7m-api/src/main/java/org/j7m/middleware/api/MiddlewareErrorCode.java`).
