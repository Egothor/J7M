<!-- Copyright (c) 2026, Leo Galambos and the Egothor team -->
<!-- SPDX-License-Identifier: BSD-4-Clause -->

# Develop and run your own Gradle project

This procedure builds a standalone application using J7M Maven artifacts. It
does not use Gradle `project(...)` dependencies, a composite build or classes
from a J7M source checkout. The application runs through the same platform host
that an operator installs from a release archive.

You need a **JDK 25**, an Internet connection for the first build and a published
J7M version. The starter includes the Gradle wrapper. Operators who only need
to run the finished application should follow [platform installation](../deployment/installed-platform.md).

## Obtain the complete starter

Choose a version from [GitHub Releases](https://github.com/Egothor/J7M/releases)
whose Maven Central deployment is `PUBLISHED` and whose release includes
`j7m-starter-VERSION.zip`. The commands use `0.9.9`; substitute an available
published version. A GitHub release can exist while Central is still waiting
for manual publication.

```bash
J7M_VERSION=0.9.9
J7M_RELEASE="https://github.com/Egothor/J7M/releases/download/release%40${J7M_VERSION}"
curl --fail --location --remote-name "$J7M_RELEASE/j7m-starter-${J7M_VERSION}.zip"
curl --fail --location --remote-name "$J7M_RELEASE/SHA256SUMS"
grep "  j7m-starter-${J7M_VERSION}.zip$" SHA256SUMS | sha256sum --check -
unzip "j7m-starter-${J7M_VERSION}.zip"
cd "j7m-starter-${J7M_VERSION}"
java -version
./gradlew --version
```

The commands below use a POSIX/Linux environment. The archive also includes
`gradlew.bat`, but the example's automatic signing-key creation requires POSIX
file permissions. On another filesystem, provide an externally protected
Ed25519 key pair as described below rather than assuming the key-generation
task configures Windows ACLs. The source form of the same starter is in `examples/standalone-application` in
the J7M repository. It remains an independent Gradle project.

## Set up Gradle dependencies

`settings.gradle` gives the application its own build identity:

```groovy
rootProject.name = 'hello'
```

Keep the J7M release in `gradle.properties`:

```properties
j7mVersion=0.9.9
```

The build must resolve both the packaging plugin and application dependencies
from Maven Central. The plugin implementation is published under
`org.egothor:j7m-gradle-plugin`; the plugin ID remains `org.j7m.application`.
Load that implementation explicitly instead of assuming an `org.j7m` plugin
marker exists on the Gradle Plugin Portal:

```groovy
buildscript {
    repositories {
        if (project.hasProperty('j7mRepository')) {
            exclusiveContent {
                forRepository { maven { url = uri(project.property('j7mRepository')) } }
                filter { includeGroup 'org.egothor' }
            }
        }
        mavenCentral()
    }
    dependencies {
        classpath "org.egothor:j7m-gradle-plugin:${providers.gradleProperty('j7mVersion').get()}"
    }
}

plugins { id 'java' }
apply plugin: 'org.j7m.application'

def j7mVersion = providers.gradleProperty('j7mVersion').get()

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories {
    if (project.hasProperty('j7mRepository')) {
        exclusiveContent {
            forRepository { maven { url = uri(project.property('j7mRepository')) } }
            filter { includeGroup 'org.egothor' }
        }
    }
    mavenCentral()
}
configurations { j7mPlatform }

dependencies {
    compileOnly "org.egothor:j7m-api:$j7mVersion"
    annotationProcessor "org.egothor:j7m-annotation-processor:$j7mVersion"
    j7mPlatform "org.egothor:j7m-deployer-cli:$j7mVersion"
}
```

Maven Central is the normal source. `j7mRepository` is an optional Maven
repository URL for testing an unpublished release candidate; it must contain
the complete matching J7M publication, including its processor, plugin and host.
Omit that property for a published release.

| Dependency/configuration | Purpose |
| --- | --- |
| `compileOnly` J7M API | Compiles the application against the API provided by the platform. It is not copied into the application's bundle. |
| `annotationProcessor` | Runs the real processor at compilation and generates component/schema metadata. It is not a runtime dependency. |
| Plugin `buildscript` classpath | Provides the packaging, signing and verification tasks. |
| `j7mPlatform` | Resolves the executable platform host and its runtime dependencies for the developer's `run` task. |

Add another `org.egothor:j7m-*` compile dependency when application source uses
that module's API. Add third-party application libraries to the bundle's
declared libraries. Do not copy platform implementation JARs into an application
bundle simply to satisfy compilation; the installed platform supplies them.

Append the signing, packaging and run configuration to the same `build.gradle`:

```groovy
tasks.register('generateSigningKey') {
    def privateFile = layout.buildDirectory.file('signing/private.der')
    def publicFile = layout.buildDirectory.file('signing/public.der')
    outputs.files(privateFile, publicFile)
    doLast {
        if (privateFile.get().asFile.exists() || publicFile.get().asFile.exists()) {
            if (!privateFile.get().asFile.isFile() || !publicFile.get().asFile.isFile()) {
                throw new GradleException('Incomplete existing signing key pair')
            }
        } else {
            def pair = java.security.KeyPairGenerator.getInstance('Ed25519').generateKeyPair()
            def keyDirectory = privateFile.get().asFile.parentFile.toPath()
            java.nio.file.Files.createDirectories(keyDirectory)
            if (java.nio.file.Files.getFileStore(keyDirectory).supportsFileAttributeView('posix')) {
                java.nio.file.Files.setPosixFilePermissions(keyDirectory,
                    java.nio.file.attribute.PosixFilePermissions.fromString('rwx------'))
                def permissions = java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString('rw-------'))
                java.nio.file.Files.createFile(privateFile.get().asFile.toPath(), permissions)
            } else {
                throw new GradleException('Use externally protected Ed25519 key files on non-POSIX systems')
            }
            java.nio.file.Files.write(privateFile.get().asFile.toPath(), pair.private.encoded)
            publicFile.get().asFile.bytes = pair.public.encoded
        }
    }
}

j7m {
    manifest.set(layout.projectDirectory.file('META-INF/j7m-application.json'))
    bundleFile.set(layout.buildDirectory.file('distributions/hello.j7m'))
    signingKeyId.set('publisher')
    signingPrivateKey.set(layout.buildDirectory.file('signing/private.der'))
    signingPublicKey.set(layout.buildDirectory.file('signing/public.der'))
    verificationProfile.set('strict-production')
}

tasks.named('j7mSign') { dependsOn 'generateSigningKey' }

tasks.register('preparePlatformConfiguration') {
    dependsOn 'j7mVerify'
    inputs.file(layout.projectDirectory.file('conf/developer.properties'))
    inputs.file(j7m.bundleFile)
    outputs.file(layout.buildDirectory.file('platform.properties'))
    doLast {
        def properties = new Properties()
        file('conf/developer.properties').withInputStream { properties.load(it) }
        def digest = java.security.MessageDigest.getInstance('SHA-256')
        j7m.bundleFile.get().asFile.withInputStream { input ->
            byte[] block = new byte[65536]
            int count
            while ((count = input.read(block)) != -1) digest.update(block, 0, count)
        }
        // Keep mutable development builds separate from immutable operator releases.
        properties.setProperty('repository.path', 'platform-repository/' + digest.digest().encodeHex().toString())
        layout.buildDirectory.file('platform.properties').get().asFile.withOutputStream {
            properties.store(it, 'Paths relative to this generated configuration')
        }
    }
}

tasks.register('run', JavaExec) {
    dependsOn 'preparePlatformConfiguration'
    classpath = configurations.j7mPlatform
    mainClass = 'org.j7m.middleware.deployer.cli.J7mPlatform'
    args '--config', layout.buildDirectory.file('platform.properties').get().asFile.absolutePath
    jvmArgs '--enable-native-access=ALL-UNNAMED', '--sun-misc-unsafe-memory-access=allow'
}
```

Together, the two build fragments are a complete `build.gradle` for the source,
manifest and `conf/developer.properties` supplied in the starter. Keep `build/`
and `.gradle/` excluded from version control; the generated private key must not
be committed.

## Build generated metadata and a signed bundle

The sample application has coordinate `example:hello:1.0.0`. Its manifest and
annotated component are included in the starter. The manifest selects the
managed annotation profile so the platform loads the generated metadata.

```bash
./gradlew clean build
./gradlew generateSigningKey j7mVerify
```

`generateSigningKey` creates an Ed25519 key pair in `build/signing/`:

| File | Handling |
| --- | --- |
| `private.der` | PKCS#8 private key used by the developer's signing task; keep private. |
| `public.der` | X.509 public key supplied to the operator through a trusted channel. |

The build configures `signingPrivateKey`, `signingPublicKey` and
`signingKeyId = 'publisher'` on the J7M plugin. It signs
`build/distributions/hello.j7m` and verifies it with the matching public key under
the strict-production signature policy. A signature policy checks artifact
trust; its name does not certify your application's operational security.

For later builds of the same publisher, protect and reuse your signing key
outside disposable build outputs. Do not distribute the private key or make
an operator trust a key solely because it arrived inside an application bundle.

Automatic key creation makes `build/signing/` owner-only and creates the private
file with owner read/write permission before writing any private-key bytes.
On a non-POSIX filesystem, first install your externally protected PKCS#8
`private.der` and matching X.509 `public.der` at the configured paths, or change
the two plugin file properties to your protected locations. The example task
keeps an existing complete key pair; it does not manage operating-system ACLs
or a production signing service.

## Run during development

```bash
./gradlew run
```

The starter's `run` task verifies the bundle, writes its platform configuration
and launches `org.j7m.middleware.deployer.cli.J7mPlatform` from the separate
`j7mPlatform` configuration. Application classes are loaded from the verified
bundle, not from the host's classpath.

The supplied developer configuration is `conf/developer.properties`. It is
copied to `build/platform.properties`; paths in the generated file resolve
relative to `build/`, so `distributions/hello.j7m` and `signing/public.der`
refer to the generated bundle and public key. The default greeting is
`Hello from Gradle`.

The sample writes `HELLO_STARTED Hello from Gradle`, and the host reports
`J7M_PLATFORM_RUNNING example:hello:1.0.0` and `J7M_PLATFORM_PID` followed by
the host process ID. It remains running. For a graceful development shutdown,
open a second terminal and signal that printed host PID:

```bash
kill -TERM HOST_PID
```

Replace `HOST_PID` with the numeric value printed by this host. Shutdown writes
`HELLO_STOPPED` and `J7M_PLATFORM_STOPPED`. Gradle may report the signalled Java
process's nonzero exit status; that is distinct from an application shutdown
failure. Cancelling Gradle itself with Ctrl+C can forcibly terminate its
`JavaExec` child, so it is not the shutdown proof used by this procedure.

Edit the sample developer configuration to change the greeting and rerun the
task. The starter gives each signed bundle digest its own development repository
under `build/platform-repository/`, so repeated runs and edit/rebuild cycles do
not collide with a previous bundle at the sample's coordinate. These stores are
disposable development state; the task does not delete or overwrite an existing
immutable repository entry.

When delivering changed bundle bytes to an operator's persistent repository,
publish a new application coordinate version. The installed platform keeps its
fixed repository path and rejects different bytes for an existing coordinate.

Do not substitute `j7mRunLocal` for this task. That legacy plugin task reports
unavailable lifecycle execution; the starter's `run` task launches the actual host.

## Deliver the application

Give the operator:

- `build/distributions/hello.j7m`;
- `build/signing/public.der`, with key ID `publisher`;
- the required J7M version and the application's configuration requirements.

The operator obtains the matching [platform archive](../deployment/installed-platform.md),
installs the bundle and public key, then starts `bin/j7m-platform`. Gradle and
the annotation processor remain on the developer side. The sample platform
configuration does not provision a native queue, job or saga backend; those
features require the explicit [host and resource configuration](../configuration/distributed-resources.md)
described by the corresponding application.

