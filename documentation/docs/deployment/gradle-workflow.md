# Gradle Application Workflow

The `org.j7m.application` Gradle plugin packages, signs, verifies and publishes J7M bundles to a local application repository. Its runtime lifecycle tasks currently report unavailable state; use a live host's application manager or Nugget administration for real activation and shutdown.

## Configure packaging

This Groovy build fragment assumes the J7M plugin is available to the build through the project's configured plugin resolution:

```groovy
plugins {
    id 'java'
    id 'org.j7m.application'
}

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
