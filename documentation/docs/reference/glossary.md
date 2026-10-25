# Glossary

| Term | Meaning in this guide |
| --- | --- |
| Application coordinate | Immutable release identity: group, name and version |
| Application instance | One actual activation of a release on a host |
| Nugget | A headless host/administrative boundary for runtime and application operations |
| Runtime | The invocation, export, proxy, registry and transport assembly owned by one host lifecycle |
| ObjectRef | A remote-object reference carrying identity, contract/endpoint and relevant lifecycle metadata |
| Registry context | Discovery scope used for matching/admitting registry records; not a credential |
| Bootstrap seed | An initially configured registry endpoint used to begin bounded discovery |
| Peer hint | An address/description that can extend discovery; not membership or authorization |
| Electorate | The authorized voting membership of a consensus group |
| Quorum | The required voting majority for the relevant consensus operation |
| Partition | A scope of state/ordering with a current owner and epoch |
| Epoch | A version of an ownership/topology generation |
| Fence | An authority token checked by a cooperating protected resource to reject obsolete owners |
| Lease | Time-bounded authority whose validity is enforced by its owning resource |
| Receipt | The native identity/version of a particular queue delivery/claim |
| Logical completion | The result visible to the caller, including timeout/cancellation |
| Physical completion | Actual exit of the tracked work and owned children/finalizers |
| Admission | Permission for new work to enter a bounded managed capability |
| Drain | Stop admission and wait for the relevant owned physical work to finish |
| Inbox | A durable deduplication marker committed with an application database effect |
| Outbox | A durable publication intent committed with an application database effect |
| Checkpoint | Explicit encoded safe-point state for durable work |
| Compensation | A declared inverse action with its own durable decision/result semantics |
| UNKNOWN | An explicitly uncertain effect/outcome requiring the contract's recovery or reconciliation |
| Reconciliation | An authorized, evidence-based decision resolving uncertain durable state |
| Named policy | A trusted host registration selected by application metadata or explicit configuration |
| Generated adapter | Compile-time generated code connecting declared application shapes to managed middleware boundaries |

When two APIs use a similar word with different boundaries, name the API as well: a registry lease, a queue visibility receipt and a protected ownership lease are not interchangeable.
