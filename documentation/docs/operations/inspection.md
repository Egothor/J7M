# Inspecting Runtimes and Applications

Start with the real target identity and an authenticated administrative connection. A local status file, a cached discovery record and a live connected nugget are different observations.

Within host code, the public runtime exposes a safe snapshot:

```java
RuntimeStatus status = runtime.status();
String runtimeId = status.identity().runtimeId();
int visibleRecords = status.registry().recordCount();
int knownPeers = status.registry().peerCount();
long failures = status.invocationFailureCount();
```

This is a read-only observation, not a reservation or health guarantee. A snapshot can become stale immediately after it is returned. Inspect the relevant authorization and application probe state before admitting a consequential action.

## Nugget CLI

The `J7mCli` application has a provider-backed `nugget` command family. Its concrete connection/discovery provider must be installed; the terminal parser alone is not a remote administration transport. An unavailable provider reports that condition rather than manufacturing a connection.

Build the standard Gradle application distribution from the repository root:

```bash
./gradlew :j7m-deployer-cli:installDist
```

Use the generated launcher with the configured provider. Inside an authenticated nugget console, supported commands include:

```text
discover
nuggets
talk --id orders-host-1
whoami
info
status
ps --limit 50 --json
apps
objects
health
readiness
metrics
errors
```

Target names and IDs are deployment values. Use `help` for the actual command/options contract. Request bounded/paginated listings, inspect the returned continuation state where the API provides it, and do not interpret a first page as the whole deployment.

`ApplicationManager` additionally provides host-side application listings, activation records, service interfaces, probe snapshots and upgrade history. Its operations address a real `ApplicationInstanceId`; a release coordinate and a running instance ID are not interchangeable.

**Sources:** runtime status (repository source: `j7m-api/src/main/java/org/j7m/middleware/api/RuntimeStatus.java`), CLI command help (repository source: `j7m-deployer-cli/src/main/java/org/j7m/middleware/deployer/cli/NuggetCliConsole.java`).
