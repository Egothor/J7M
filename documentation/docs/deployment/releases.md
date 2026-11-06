<!-- Copyright (c) 2026, Leo Galambos and the Egothor team -->
<!-- SPDX-License-Identifier: BSD-4-Clause -->

# Maven Central releases

The GitHub `Release` workflow runs when a tag matching `release@*` is pushed.
`release@1.2.3` prepares version `1.2.3`; `release@1.2.3-rc.1` prepares
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
The workflow uses Java 25, runs `clean check javadoc`, builds deployer CLI,
platform, starter and signed Hello example archives, validates an external consumer installation,
stages signed publications, validates the complete bundle, and uploads
it through the Central Publisher API with `publishingType=USER_MANAGED`.
Central validates the deployment, then waits for manual publication in the
Central Portal. The workflow does not trigger publication.

The 20 application-development and runtime modules in `gradle/central-publications.txt`
are published, using coordinates `org.egothor:<module-name>:<release-version>`.
They include the annotation processor, Gradle packaging plugin and executable
deployer/platform host as well as
the API, kernel, runtime, durable log, messaging, coordination,
consensus, distributed core, sagas, isolation, adapter SPI, gRPC/RMI transports,
application platform, integrations, cluster management and distributed application
support. Each module remains a separate JAR with its Maven dependency metadata.
For example:

```kotlin
dependencies {
    implementation("org.egothor:j7m-api:1.2.3")
    implementation("org.egothor:j7m-application-platform:1.2.3")
    annotationProcessor("org.egothor:j7m-annotation-processor:1.2.3")
}
```

The annotation processor runs during compilation and does not belong on the
application's runtime classpath. The Gradle plugin is available through its
implementation artifact; [the developer guide](../getting-started/developer-project.md)
shows explicit plugin resolution under the owned `org.egothor` Maven namespace.

The adapter TCK, testkit, repository examples, demos, test aggregation module
and empty root project are excluded from Maven Central.
The installable platform and CLI distributions are GitHub release assets.
See [platform installation](installed-platform.md) for the downloadable archive
and executable host. Java packages remain `org.j7m`.

For local release rehearsal, export the signing key and passphrase, then run:

```bash
python3 -m unittest discover -s tools -p 'test_*.py'
./gradlew clean check javadoc :j7m-deployer-cli:distTar :j7m-deployer-cli:distZip releaseChecksums validateConsumerDistribution --no-daemon -PreleaseVersion=1.2.3
./gradlew publishMavenJavaPublicationToCentralBundleRepository --no-daemon -PreleaseVersion=1.2.3
python3 tools/prepare_central_bundle.py --repository build/central-repository --output build/central-bundle.zip --version 1.2.3
```

The workflow also supports **Run workflow** with a `release_version` and
`dry_run=true` (the default). Rehearsal runs the same clean build, all tests,
Javadoc, CLI/platform/starter packaging and the external consumer procedure.
It requires no Maven Central signing key or Central credentials. The consumer
check creates its own application-signing key in a temporary directory; that
key is unrelated to Maven artifact signing. Maven signing, Central bundle
staging, Central upload and GitHub release creation are skipped. This mode
verifies the build and tests without exercising publication.

To exercise the workflow locally, provide a `workflow_dispatch` event file with
`{"inputs":{"release_version":"1.2.3","dry_run":"true"}}` and an artifact directory.
Use a string in the event JSON so `act` interprets the Boolean workflow input
correctly:

```bash
DOCKER_HOST="unix://${XDG_RUNTIME_DIR}/podman/podman.sock" act workflow_dispatch \
  -W .github/workflows/release.yml -e /tmp/j7m-release-event.json \
  --artifact-server-path /tmp/j7m-release-artifacts
```

Choose an `act` runner image with the workflow's required tools and keep the
event, logs and artifacts outside the repository. A publishing dispatch
(`dry_run=false`) must select the exact matching `release@VERSION` tag and
requires the normal signing and Central credentials. Pushes of release tags
always use the publishing path, which still requires manual **Publish** in the
Central Portal.

The bundle contains POMs, binary/source/Javadoc JARs, Gradle module metadata,
OpenPGP signatures and checksums. It excludes mutable Maven repository metadata.
The helper rejects incomplete inventories, stale versions, missing signatures,
incorrect coordinates and dependencies on unpublished J7M modules before upload. The normal development version remains
`0.1.0-SNAPSHOT`; releases override it with `-PreleaseVersion`.

The GitHub run retains the Central bundle as an artifact and attaches it and the
CLI/platform/starter/Hello distributions and `SHA256SUMS` to a GitHub release with generated notes. Its step summary
records the Central deployment UUID. Upload acceptance starts asynchronous
Central validation. The release owner opens the deployment in the Central Portal,
waits for `VALIDATED`, reviews the contents and clicks **Publish** to release it.
Until that manual action, the artifacts are not published to Maven Central.
A GitHub release or a successful workflow run does not indicate Maven Central
availability. After publishing, verify that the deployment reaches `PUBLISHED`
before announcing availability. If Central validation fails, inspect its
diagnostics before attempting another upload. Do not blindly rerun a successful
upload.

| Audience | Local build output | Public availability condition |
| --- | --- | --- |
| Application developer | `build/distributions/j7m-starter-VERSION.zip` and staged Maven publications | Starter asset exists on the GitHub release, and the required `org.egothor` version is `PUBLISHED` on Maven Central. |
| Application operator | `j7m-deployer-cli/build/distributions/j7m-platform-VERSION.zip` or `.tar.gz` | Matching platform archive and `SHA256SUMS` exist on the GitHub release. |
| Operator running the supplied example | `build/distributions/j7m-hello-VERSION.zip` | Hello archive and its checksum exist on the same GitHub release; the archive includes the signed bundle and demo public key, not its private key. |

A successful local consumer check establishes that the built artifacts work
together. It does not upload them or establish that any public download URL
already exists. Check both public destinations before announcing a release.

Before upload, revert the workflow/build changes to roll back this automation.
After publication Maven Central versions are immutable: publish a corrected
version under a new `release@…` tag and direct consumers to it. Do not move a
published tag or overwrite a released version.
