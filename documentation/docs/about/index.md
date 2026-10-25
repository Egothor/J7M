# Project and Legal

J7M is Java middleware for distributed applications, with remote-object invocation, managed application hosting and optional durable distributed capabilities. This manual documents the current implementation and its explicit boundaries.

## From j5m to J7M

J7M builds on **j5m**, an earlier version of this middleware developed for a
high-capacity crawler and a virtual army. J7M carries those foundations forward
and provides the middleware base for new versions of related projects implemented
as distributed applications. This lineage explains the project's focus on
distributed application workloads; it does not substitute historical experience
for the current contracts and limits documented in this manual.

The documentation serves application developers, deployment engineers and operators. It connects practical tasks to the current public APIs, source contracts and tests. Test-derived examples are evidence for the behavior actually exercised; they are not a promise of unqualified production throughput or arbitrary external exactly-once effects.

| Topic | Read |
| --- | --- |
| Building and maintaining this manual | [Contributing](contributing.md) |
| Copyright, redistribution conditions and acknowledgement | [BSD 4-Clause license](license.md) |
| Product orientation | [J7M overview](../overview/index.md) |
| Current capabilities and boundaries | [Capabilities](../overview/capabilities.md) |

Documentation owner: the J7M project maintainers. Source review date for this edition: 25 October 2026. Review affected pages whenever a public API, annotation consumer, configuration default, durable protocol or deployment boundary changes. Historical development notes are background sources; current verified implementation facts govern the user-facing manual.
