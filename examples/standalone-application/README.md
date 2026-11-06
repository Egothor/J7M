<!-- Copyright (c) 2026, Leo Galambos and the Egothor team -->
<!-- SPDX-License-Identifier: BSD-4-Clause -->

# Standalone J7M application

## Purpose

Build an annotation-processed application outside the J7M source tree, package
and sign it, then run it through the same host distributed to operators.

## Architecture flow

Maven API/processor/plugin → generated component metadata → signed `hello.j7m`
→ verified isolated activation in the platform host → lifecycle callbacks.

## Prerequisites

Use JDK 25. The released starter archive includes its Gradle wrapper and
resolves J7M from Maven Central; a J7M source checkout is not required. Set
`j7mVersion` in `gradle.properties` to a published J7M version.

## Build

From the extracted starter directory on Linux or another POSIX filesystem:

```bash
./gradlew clean build
```

## Test

The starter currently ships no unit-test sources, so its Gradle `test` task is
`NO-SOURCE`; a successful `./gradlew check` is not a behavioral test result.
The release's external-consumer validation separately exercises generated
metadata, signed packaging, repeated host startup and shutdown. The Hello
component is a startup/shutdown example, not a remote-service test suite.

## Package

`./gradlew j7mPackage` creates `build/distributions/hello.j7m` with the compiled
application JAR. J7M platform dependencies are resolved separately and are not
copied into that application's classloader.

## Verify bundle

```bash
./gradlew generateSigningKey j7mVerify
```

The first signing task creates an Ed25519 private/public key pair in
`build/signing/`, with owner-only POSIX permissions for the private key. On a
non-POSIX filesystem, supply externally protected PKCS#8 `private.der` and
matching X.509 `public.der` at those paths instead. Keep a publisher's durable
key outside disposable build outputs when adapting this example.

## Deploy to local repository

`conf/developer.properties` supplies the greeting and local platform settings.
The run task copies it into `build/`; relative paths resolve against that
generated file. It selects `build/platform-repository/<signed-bundle-SHA256>`
as a disposable development repository, allowing repeated runs and edit/rebuild
cycles without overwriting immutable entries. The application JAR and generated metadata are packaged in
`build/distributions/hello.j7m`, not added directly to the host classpath.

## Start / activate

```bash
./gradlew run
```

The task verifies the signed bundle and launches the real platform host. That
host imports the bundle into its development repository and activates it.

## Invoke / verify behavior

The application writes the configured greeting during its actual startup
callback. Change `greeting.message` in `conf/developer.properties` and repeat
the run to check configuration delivery. This example exports no business RPC.

## Status / observability

Look for `J7M_PLATFORM_RUNNING example:hello:1.0.0` and the numeric
`J7M_PLATFORM_PID`. Output goes to the foreground console; this host does not
enable a nugget administration endpoint.

## Stop / unload

For graceful shutdown, run `kill -TERM HOST_PID` in another terminal using
that printed PID. Expect `HELLO_STOPPED` and `J7M_PLATFORM_STOPPED`; Gradle may
report the signalled child's exit status. Cancelling Gradle can forcibly stop
its child, so use the host PID when checking shutdown.

## Upgrade / rollback, where applicable

Development stores are disposable and digest-scoped. For an operator's
persistent repository, deliver changed bytes under a new application coordinate
version and use the documented application lifecycle. This starter does not
implement an automatic rolling-upgrade controller.

## Expected output

```text
HELLO_STARTED Hello from Gradle
J7M_PLATFORM_RUNNING example:hello:1.0.0
J7M_PLATFORM_PID <host PID>
```

Graceful shutdown writes `HELLO_STOPPED` and `J7M_PLATFORM_STOPPED`.

## Security notes

To deliver the application, give its operator `hello.j7m`, the trusted
`build/signing/public.der` and key ID `publisher`. Keep `private.der` private.
The operator downloads the matching `j7m-platform-VERSION` distribution and
follows its included README. Unlike the starter's disposable digest-scoped
stores, an operator keeps a fixed persistent repository. Deliver a new
application coordinate version for changed bytes in that repository.

## Cleanup

After stopping the host, `./gradlew clean` removes generated bundles,
development repositories and generated signing keys under `build/`. Preserve a
publisher's required private key securely before cleaning; never distribute it.

## Known limitations

The supplied host uses loopback transports and no remote administration. Native
queue/job/saga backends and cross-host TLS require an explicitly configured host.
The starter's automatic signing-key creation requires POSIX permissions.

Complete developer guide: <https://www.egothor.org/J7M/getting-started/developer-project/>

