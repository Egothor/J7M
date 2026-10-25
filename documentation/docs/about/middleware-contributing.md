# Contributing to the Middleware

Changes to middleware semantics need a clear contract, bounded resource behavior and evidence for failure as well as success. Keep the public manual and API documentation synchronized with implementation.

## Design and implementation

1. Place public contracts in the appropriate API package and implementation in its owning module; preserve the [module dependency boundaries](../programming/module-dependencies.md).
2. Specify ownership, thread safety, blocking behavior, deadlines, cancellation, overload and closure. Distinguish logical outcome from physical completion.
3. Define stable errors, safe diagnostics, payload/storage limits and redaction before adding a new execution path.
4. For distributed operations, state the durable commit point, quorum/partition behavior, fencing, retry identity, recovery and uncertainty. Do not describe an external effect as exactly once without an enforcing resource contract.
5. Document public classes/methods and include readable application examples. An annotation needs an actual consumer and a documented enforcement boundary.

## Verification

Run the focused module tests first, then the relevant integration/architecture checks. Add tests for material behavior and regressions rather than duplicating implementation details. Distributed changes should cover the relevant loss, restart, stale-owner, duplicate, timeout and overload cases with bounded deterministic tests where practical.

From the repository root, the normal Gradle checks include:

```bash
./gradlew test
./gradlew validateJ7mArchitecture validatePublicApiDocs
./gradlew pmdMain pmdTest
```

Check task availability for the affected modules with `./gradlew tasks --all`; individual subsystems also expose dedicated validation tasks. PMD is a required static-analysis gate, not a replacement for tests or review. Do not suppress findings broadly to hide a new issue.

For a public API change, check JavaDoc and compile realistic callers. For JPMS or packaging changes, verify module descriptors, classpath and module-path packaging, and the affected smoke/integration scenarios. For isolation changes, inspect actual enforced versus advisory limits and run the isolation validation suite.

Update and [validate the manual examples](contributing.md#verify-runnable-examples) when a documented contract changes. Run the strict documentation build after navigation and links are updated. A passing original JUnit test does not automatically prove that a modified snippet is runnable.

## Review and release

Have an implementer review API/configuration correctness and an architect review ownership, failure and trust boundaries. Report the checks that actually ran and any remaining limitations. Publication must not expose credentials, private paths, operational evidence payloads or internal planning records. Follow the project's [license](license.md) when contributing code or reused material.
