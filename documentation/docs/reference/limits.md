# Limits and Defaults

The following values are concrete current defaults or constructor bounds, not a deployment SLA. Confirm the owning API when overriding them and measure the target profile.

| Owner | Current value or bound |
| --- | --- |
| Multicast defaults | `239.7.0.1:47070`, TTL 1; 500 ms query, 25 ms gather, 2 s question TTL |
| Multicast default query bounds | 64 results, 32 peers, requested receive buffer 64 KiB, accepted message 60 KiB |
| Network bootstrap budgets | Configured operation/request budget 1 ms–5 s |
| Bootstrap network capacity | Client 64 active calls and 4 DNS workers; server 16 concurrent requests/no worker queue, 128 new connections per second-window |
| Bootstrap framing | Outer frame 256 KiB; at most 64 records or peers; record/query packet also subject to the smaller multicast field-codec bounds |
| Record/query field codec | 60 KiB packet, 48 KiB aggregate string decode budget, 2048-byte strings, 128-element maps/lists |
| Live configuration | 128 active typed bindings; 1024 snapshot entries; 256-character keys; 8192-character text/default values |
| Live configuration publication | At most 8 registration/publication attempts under contention |
| Network consensus validation | Peer key at least 32 bytes; election at least 150 ms; heartbeat positive and below one third of election timeout |
| Measured clock monitor | 500 ms alignment/uncertainty boundary; 2 stable aligned samples to recover |
| Local application-platform defaults | Bundle/compressed bytes 512 MiB; entry expansion 128 MiB; aggregate expansion 1 GiB; 4096 entries |
| Local manifest default | 1 MiB, within the application-platform metadata bounds |
| Network artifact contract | Artifact 1 byte–64 MiB; chunks 4–256 KiB; at most 16384 chunks; manifest/frame 2 MiB |

Local application bundle limits and network artifact-transfer limits are different APIs. An artifact accepted by one configured layer can still exceed a downstream transfer or activation bound. Keep the chosen limits consistent end to end.

Byte and character limits are not interchangeable. UTF-8 metadata, Java strings, authenticated framing and an encoded payload can have different sizes. Test at the boundary named by the applicable contract rather than assuming a character count is a byte count.

Bounded listings return only the requested/allowed result set. Follow explicit continuation/cursor contracts where provided. Do not describe a bounded first result set as an exhaustive deployment inventory.

**Sources:** multicast options (repository source: `j7m-runtime/src/main/java/org/j7m/middleware/registry/mcast/MulticastRegistryOptions.java`), bootstrap contract (repository source: `j7m-runtime/BOOTSTRAP-NETWORK.md`), local platform limits (repository source: `j7m-application-platform/src/main/java/org/j7m/middleware/application/repository/ApplicationPlatformLimits.java`).
