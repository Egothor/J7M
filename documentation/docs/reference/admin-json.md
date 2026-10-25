# Nugget Administration JSON Contract

The public artifact format is `j7m-nugget-admin-json`, format version **1**, protocol version **1**. The [JSON Schema](schemas/nugget-admin-contract.schema.json) describes bounded request/result envelopes and their exact DTO payload families. The schema identifier is a format identifier, not a live administration endpoint.

This is a transport-neutral contract for adapters and conformance checks. J7M does not infer an HTTP route, listener, authentication header, browser session or universal JSON dispatch server from this schema. An adapter must perform authenticated typed calls and enforce the same admission, authorization, target generation and deadline rules as the Java API.

## Envelope and payload

An envelope identifies `formatName`, `formatVersion`, `protocolVersion`, `requestId`, `correlationId`, `operationKind`, `payloadType` and `payload`. Family-specific variants add the appropriate context, target, outcome or result header. Extra properties and unsupported variants are rejected by the schema.

The following is the **query payload fragment**, not a complete request envelope:

```json
{
  "offset": 0,
  "limit": 50,
  "filters": [
    {"field": "STATE", "value": "RUNNING"}
  ],
  "sorts": [
    {"field": "STABLE_ID", "direction": "ASC"}
  ]
}
```

Bind it only to the supported typed query variant and operation family. Do not send arbitrary property names or CLI command strings as payloads. The [Java API example](admin-api.md) shows construction of the corresponding immutable query.

## Bounds and safe rendering

| Item | Contract bound |
|---|---|
| Collection page | Default 50, maximum 500. |
| Filters / requested sorts | 16 / 4, plus stable-identity tie-breaker. |
| Request metadata | 16 entries, 64-byte keys, 256-byte values, 2,048 aggregate UTF-8 bytes. |
| Generic token / text | 128 / 512 UTF-8 bytes. |
| Result warnings | 16 entries, each at most 160 UTF-8 bytes. |
| Client budget metadata | Default 30 seconds, maximum five minutes. |

Scalar duration/count/offset values have explicit representable bounds and semantic checks. JavaScript consumers must preserve large integer precision rather than silently round `long` values. Invalid offset-plus-limit arithmetic is rejected.

Retain partial, truncated, timed-out and outcome-unknown flags. Do not reinterpret a bounded first page as a complete result. Schema validity checks shape; it does not prove that the sender was authorized, that an operation completed, or that a target is still current.

PIN material travels separately from metadata in the Java admission API and must not appear in these artifacts. Redact credentials, confirmation secrets, protected paths, sensitive arguments and hidden object metadata before serialization. The CLI's rendered JSON has command-specific presentation; do not assume every CLI output is a schema envelope without checking its format/payload family.
