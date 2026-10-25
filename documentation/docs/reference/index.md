# Reference

Use the reference for exact names and contracts after the task-oriented guides establish the workflow.

| Reference | What it answers |
| --- | --- |
| [Module Javadoc](https://www.egothor.org/J7M/javadoc/) | Classes, interfaces, parameters, results and exception contracts for each module |
| [Configuration](configuration.md) | Which object owns a setting and how it is supplied |
| [Manifests and policies](manifests-policies.md) | Actual manifest fields and the host-policy boundary |
| [Errors](errors.md) | Stable middleware error codes and safe responses |
| [Distributed semantics](semantics.md) | What success, retry, ownership and uncertainty mean |
| [Limits](limits.md) | Concrete defaults/bounds and their implementing sources |
| [Glossary](glossary.md) | Terms used consistently throughout the manual |
| [Annotation reference](../annotations/index.md) | All current public annotations and usage examples |
| [CLI](cli.md) | Application tooling, Nugget targeting, admission, queries and exit status |
| [Typed administration API](admin-api.md) | Public Java DTO families, target generations, limits and uncertain outcomes |
| [Administration JSON](admin-json.md) | Version 1 envelopes, schema, payload bindings and adapter responsibilities |
| [Local messaging storage](local-messaging-storage.md) | Journal schema, recovery events and implementation bounds |

The public API is distributed across JPMS modules. `j7m-api` contains contracts; `j7m-kernel` implements the invocation core; `j7m-runtime` supplies first-party registry/runtime assembly; transport modules carry remote calls; `j7m-application-platform` handles verified application activation and managed components; `j7m-application-distributed` bridges those components to native durable resources. See the [module map](../overview/modules.md) for the complete orientation.

The current public API defines the supported contract. Application examples show the intended usage directly; generated adapters, named host profiles and operation-specific recovery rules determine the actual behavior.
