# Gradle Application Workflow

The `org.j7m.application` Gradle plugin packages, signs, verifies and publishes J7M bundles to a local application repository. Its runtime lifecycle tasks currently report unavailable state; use a live host's application manager or Nugget administration for real activation and shutdown.

For a complete independent project, use [the developer starter](../getting-started/developer-project.md).
Its `run` task launches the executable platform host. Operators can instead
[download and install that host](installed-platform.md) without Gradle.

## Build version and configuration cache

The repository wrapper pins Gradle 9.8.2 and verifies the distribution's SHA-256 checksum. Use `./gradlew` with Java 25 for repository builds.

The application plugin supports Gradle's configuration cache for classpath and JPMS packaging, including the local lifecycle chain. To store and then reuse a packaging configuration:

```bash
./gradlew j7mVerify --configuration-cache --warning-mode fail
./gradlew j7mVerify --configuration-cache --warning-mode fail
```

Publishing remains immutable: rerunning `j7mDeployLocal` or a task that depends on it against an already published coordinate is rejected, even when the configuration cache is reused. Choose a new application version for a new publication.

The plugin workflows above are tested on Gradle 9.8.2 with configuration cache and deprecation warnings treated as failures. This does not certify Gradle 10 compatibility or configuration-cache support for every repository reporting and validation task. Keep `--warning-mode fail` in migration checks and test additional task paths with `--configuration-cache` before enabling it repository-wide.

## Configure packaging

Resolve the plugin implementation from Maven Central under `org.egothor`.
Replace `0.9.9` with a published J7M version. The existing plugin ID is applied
from that implementation; it does not require a plugin marker under `org.j7m`:

```groovy
buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath 'org.egothor:j7m-gradle-plugin:0.9.9'
    }
}

plugins { id 'java' }
apply plugin: 'org.j7m.application'

j7m {
    manifest.set(layout.projectDirectory.file(
        'src/main/resources/META-INF/j7m-application.json'))
    bundleFile.set(layout.buildDirectory.file('distributions/crawler.j7m'))
    repositoryDir.set(layout.buildDirectory.dir('j7m-repository'))
    verificationProfile.set('development')
    libraries.from(configurations.runtimeClasspath)
    documentation.from(layout.projectDirectory.dir('manual'))
    configurationFiles.from(layout.projectDirectory.file('config/application.properties'))
}
```

The manifest must match the actual libraries or JPMS modules in the bundle. `libraries`, `modules`, `documentation` and `configurationFiles` are file collections evaluated when the task executes. Keep secrets out of packaged configuration and documentation.

## Task semantics

| Task | Current behavior |
|---|---|
| `j7mPackage` | Builds required JARs, validates the manifest and writes the bundle. |
| `j7mSign` | Depends on packaging and signs through the bundled signing implementation. |
| `j7mVerify` | Depends on signing and verifies the selected profile. |
| `j7mDeployLocal` | Depends on verification and publishes the immutable coordinate to the local repository. |
| `j7mPlanLocal` / `j7mRunLocal` | Depend on publication / planning and write explicit unavailable runtime state. |
| `j7mStatusLocal` | Reads the local workflow state file; depends on the run task. |
| `j7mStopLocal` / `j7mUnloadLocal` | Continue the dependency chain and report unavailable lifecycle execution. |

```bash
./gradlew j7mDeployLocal
```

This executes the package → sign → verify → publish chain. It does not start an application server or prove that a service is running. The lifecycle/status dependency chain is not a remote operator console.

Profiles are `development`, `strict-production` and `strict-regulated`. Selecting a strict profile does not provision a production signing authority or trusted key material. `signingKeyId` is safe configuration metadata, not a private-key store; the bundled local signing workflow must not be presented as a production KMS integration. See [signed applications](signed-applications.md), [repositories](repositories.md) and [enterprise integrations](../security/enterprise-integrations.md).

For a bundle another installation will trust, configure explicit Ed25519 key
files rather than relying on the plugin's local development signer:

```groovy
j7m {
    signingKeyId.set('publisher')
    signingPrivateKey.set(layout.projectDirectory.file('signing/private.der'))
    signingPublicKey.set(layout.projectDirectory.file('signing/public.der'))
    verificationProfile.set('strict-production')
}
```

The private file is PKCS#8 DER; the public file is X.509 DER. The public key
must match the private key. Supply only the public file and key ID to the
operator. The standalone starter includes `generateSigningKey` as an example
project task; it is not an implicit key-provisioning service provided by the plugin.
