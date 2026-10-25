# Artifacts and Directory Layout

Keep executable code, declared metadata, mutable runtime state and secrets in separate ownership domains. A signed application bundle is an immutable release; a consensus directory is live state; a TLS private-key store is protected host configuration. They have different retention and access requirements.

A deployable application declares `META-INF/j7m-application.json`. The CLI also recognizes the normal resources path `src/main/resources/META-INF/j7m-application.json`. The manifest identifies the application coordinate, Java/module requirements, bootstrap class and exported services.

For JPMS mode, declare the bootstrap module and all module JAR paths explicitly. Module resolution and generated service-provider declarations replace indiscriminate reflective `opens`. The host validates package/module origins before loading application code.

## Local packaging tools

From the repository root, inspect the supported local command interface:

```bash
./gradlew :j7m-deployer-cli:run --args='--help'
```

The `app` command family provides `package`, `sign`, `verify`, `deploy`, `plan`, repository inspection and local lifecycle-related commands. Its real options include `--project`, `--bundle`, `--repository`, `--coordinate`, `--profile`, `--signing-key` and `--json`. These are command options, not runtime environment variables.

For a project with compiled application classes and a valid manifest:

```bash
./gradlew :j7m-deployer-cli:run --args='app package --project /srv/build/orders --bundle /srv/releases/orders.j7m'
./gradlew :j7m-deployer-cli:run --args='app verify --bundle /srv/releases/orders.j7m --profile development'
```

The paths are deployment examples and must refer to your built application. Development verification enforces checksums but does not require a trusted production signature. Apply the [production signing policy](signed-applications.md) before making a production artifact eligible for activation.

Do not confuse a local CLI status record with a live remote process. Local lifecycle commands require an active `ApplicationManager` session for real activation; the CLI explicitly reports lifecycle unavailable where that capability is absent. Use the supported host administration client/provider for operations against a running nugget.

**Sources:** CLI implementation (repository source: `j7m-deployer-cli/src/main/java/org/j7m/middleware/deployer/cli/J7mCli.java`), bundle reader (repository source: `j7m-application-platform/src/main/java/org/j7m/middleware/application/J7mBundleReader.java`).
