# Typed and live configuration (A-P04)

`LiveConfiguration` is a host-owned configuration service. It receives complete,
monotonically versioned string snapshots and exposes typed `ConfigBinding<T>`
providers. It is independent of any application, demo or external configuration
product. `ConfigurationView` and the existing explicit legacy injector retain
their activation snapshot behavior.

The host constructs the owner with an initial version and snapshot. It can
register scalar bindings through `bindValue`, annotated public records through
`bindProperties`, or pass the owner to the managed container's preactivation
configuration overload. The container owns its registrations; the host owns
the configuration service. Closing a container detaches its bindings without
closing a borrowed host service.

## Annotation contracts

| Declaration | Managed behavior |
|---|---|
| `@ConfigValue` | Typed conversion by the declared dependency type; required missing values fail. Optional values use the declared default, empty String, or null for absent nonprimitive scalars without a default. |
| Optional primitive values | Generated injection requires an explicit default, including `0` or `false`; otherwise compilation fails before any component can be constructed. Use a nullable wrapper when absence is meaningful. |
| `@ConfigValue(refresh=false)` | Stable initial value, preserving compatibility. |
| `@ConfigValue(refresh=true)` | Requires a managed provider. Subsequent provider reads observe newer valid snapshots. Direct refreshing fields/parameters are rejected before component creation. |
| `@ConfigProperties(prefix=..., refresh=...)` | Public immutable record, resolved as one whole value. Names are prefix plus component names; component `@ConfigValue` may specify relative names/default/required metadata. Component-level refresh is rejected; the record controls its refresh policy. |

Supported scalar types are String, primitive wrappers (including primitive
injection tokens), Duration and enums. Booleans accept only `true` or `false`;
nonfinite floating-point values are rejected. Record components must be
supported scalars. Public canonical constructors and record validation execute
as trusted application code. The package must be exported to the application
platform when JPMS requires it; no reflective `opens` is requested. Unsupported
record shapes/types fail explicitly.

## Publication, concurrency and validation

Every source update supplies a complete replacement snapshot. The service
converts and validates all refreshing registrations before one atomic state
publication. Failed conversion, a rejecting record constructor or a custom
validator preserves the previous version and all previously published values.
Stable registrations keep their original value and version. Older/equal
source versions are ignored. An absent key in an update is a removal, not an
instruction to retain an old key.

`ConfigBinding.snapshot()` observes the value and source version together.
Separate reads of different bindings can span different accepted updates; use
one properties record when fields must belong to the same version.

Conversions and validators run outside the service monitor. They must be
side-effect-free and may be retried after concurrent structural changes.
Reentrant mutation from a validator is rejected. Publication/registration
attempts are bounded to eight; persistent contention reports a safe retryable
state error instead of spinning indefinitely. User validation must not block
indefinitely.

## Sources, reconnect and cleanup

`ConfigurationSource` is the general host adapter SPI. `watch` supplies an
atomic initial-snapshot subscription and subsequent complete snapshots. A
provider performs its own connection and reconnect mechanics while preserving
the version namespace. `LiveConfiguration.attach` supports one active source;
its returned closeable detaches that source. A replacement source can replay
old snapshots: stale versions are ignored. A detached source cannot publish
even if an already delivered callback finishes validation after detach.

Invalid source deliveries preserve the current state and increment
`rejectedSourceUpdates`; subsequent valid deliveries can recover. Synchronous
initial delivery is supported. Owner shutdown detaches the source and all
bindings. The provider must clean up any partially failed subscription and
stop delivery on subscription close. The host guards racing late deliveries.
Provider close errors are redacted; the subscription remains detached.

Limits are 128 active typed bindings, 1024 entries per complete source snapshot,
256 characters per key and 8192 characters per text value/default. Binding close
is idempotent, releases capacity and rejects subsequent reads. Errors and
snapshot `toString` never print configuration values or validation exception
causes. Returned typed values remain application data and must not be logged
indiscriminately.

## Verification and rollback

`LiveConfigurationTest` covers atomic whole-record updates, rejecting updates,
concurrent publication, stable bindings, scalar/default rules, bounded capacity
and reentrant validation. `ConfigurationSourceTest` covers reconnect/replay,
subscription ownership, late delivery and detach during validation.
`LiveConfigurationJPMSIT` compiles and runs a separate named-module consumer
with a selective export and no opens. `ManagedConfigurationTest` covers the
actual managed-container bridge and preconstruction failure cleanup.

The service is opt-in and has no persistent format. A host can detach a source
and restore its previous configuration/profile; existing legacy injection
does not change. This layer is not service discovery, object failover or
transparent replay of an in-flight distributed operation (A-P05 and later).
