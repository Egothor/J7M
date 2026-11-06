<!-- Copyright (c) 2026, Leo Galambos and the Egothor team -->
<!-- SPDX-License-Identifier: BSD-4-Clause -->

# Maven Central releases

The GitHub `Release` workflow runs when a tag matching `release@*` is pushed.
`release@1.2.3` publishes version `1.2.3`; `release@1.2.3-rc.1` publishes
`1.2.3-rc.1`. Tags must contain three numeric components and an optional
alphanumeric prerelease suffix. Snapshot versions are rejected.

Configure these repository or organization Actions secrets, using the same
values and format as ZeroEcho:

| Secret | Value |
| --- | --- |
| `CENTRAL_BEARER_TOKEN` | Sonatype Central Publisher bearer token authorized for `org.egothor` |
| `MAVEN_SIGNING_KEY` | ASCII-armored private OpenPGP signing key |
| `MAVEN_SIGNING_PASSWORD` | Passphrase for the signing key |

The signing public key must be available to Maven Central's signature validator.
The workflow uses Java 25, runs `clean check javadoc`, builds deployer CLI
archives, stages signed publications, validates the complete bundle, and uploads
it through the Central Publisher API with `publishingType=AUTOMATIC`.

Only the 17 runtime middleware modules in `gradle/central-publications.txt`
are published, using coordinates `org.egothor:<module-name>:<release-version>`.
They cover the API, kernel, runtime, durable log, messaging, coordination,
consensus, distributed core, sagas, isolation, adapter SPI, gRPC/RMI transports,
application platform, integrations, cluster management and distributed application
support. Each module remains a separate JAR with its Maven dependency metadata.
For example:

```kotlin
dependencies {
    implementation("org.egothor:j7m-api:1.2.3")
    implementation("org.egothor:j7m-application-platform:1.2.3")
}
```

The annotation processor, deployer CLI, Gradle plugin, adapter TCK, testkit,
repository examples, demos, test aggregation module and empty root project are
excluded from Maven Central. CLI distributions remain GitHub release assets.
Java packages remain `org.j7m`.

For local release rehearsal, export the signing key and passphrase, then run:

```bash
python3 -m unittest discover -s tools -p 'test_*.py'
./gradlew clean check javadoc --no-daemon -PreleaseVersion=1.2.3
./gradlew publishMavenJavaPublicationToCentralBundleRepository --no-daemon -PreleaseVersion=1.2.3
python3 tools/prepare_central_bundle.py --repository build/central-repository --output build/central-bundle.zip --version 1.2.3
```

The bundle contains POMs, binary/source/Javadoc JARs, Gradle module metadata,
OpenPGP signatures and checksums. It excludes mutable Maven repository metadata.
The helper rejects incomplete inventories, stale versions, missing signatures
incorrect coordinates and dependencies on unpublished J7M modules before upload. The normal development version remains
`0.1.0-SNAPSHOT`; releases override it with `-PreleaseVersion`.

The GitHub run retains the Central bundle as an artifact and attaches it and the
CLI distributions to a GitHub release with generated notes. Its step summary
records the Central deployment UUID. Upload acceptance starts asynchronous
Central validation and publication; it does not prove the version is already
available. The release owner checks that deployment in the Central Portal before
announcing availability. If Central validation fails, inspect its diagnostics
before attempting another upload. Do not blindly rerun a successful upload.

Before upload, revert the workflow/build changes to roll back this automation.
After publication Maven Central versions are immutable: publish a corrected
version under a new `release@…` tag and direct consumers to it. Do not move a
published tag or overwrite a released version.
