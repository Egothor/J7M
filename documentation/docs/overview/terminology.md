# Core terminology

| Term | Meaning in this manual |
| --- | --- |
| Application | A packaged unit of Java application code and declared deployment metadata |
| Nugget | A configured J7M application host with runtime and operational facilities |
| Runtime | The middleware instance that exports, discovers and invokes objects |
| Component | An application object whose construction and lifetime may be managed |
| `ObjectRef` | A concrete object reference containing identity, interface and routing metadata |
| Proxy | A Java interface implementation that performs middleware invocation |
| Registry | A source of service records and peer discovery information |
| Policy/profile | Explicit host configuration selected by a named application contract |
| Lease | Time-bounded ownership or availability that must be renewed as required |
| Fence | A monotonically advancing authority token checked by an effect boundary |
| Receipt | A queue delivery identity used to authorize operations on that delivery |
| Quorum | The configured voting majority needed for a consensus decision |
| UNKNOWN | An outcome whose effect cannot safely be assumed to have succeeded or failed |
| Logical completion | The caller-visible outcome, which can precede physical task exit |
| Physical completion | The tracked work has actually exited and its ownership can be released |

The [full glossary](../reference/glossary.md) expands these terms and relates them
to configuration and operational diagnostics.
