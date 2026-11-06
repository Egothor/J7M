<!-- Copyright (c) 2026, Leo Galambos and the Egothor team -->
<!-- SPDX-License-Identifier: BSD-4-Clause -->

# J7M installed platform

This archive contains the executable application host and its dependency JARs.
Install Java 25 and set `JAVA_HOME` if necessary. No Gradle, Maven or J7M source
checkout is required to run an application.

For a ready-to-run example, download `j7m-hello-VERSION.zip` and `SHA256SUMS`
from the same GitHub release as this platform. Verify its checksum and extract
it beside the platform directory. The Hello archive contains `hello.j7m`,
`publisher.der` and its own README; it does not contain a private key. That
public key is for the supplied example only.

From this platform directory, using the actual release version:

```bash
J7M_VERSION=0.9.9
mkdir -p applications trust var/repository
cp "../j7m-hello-${J7M_VERSION}/hello.j7m" applications/hello.j7m
cp "../j7m-hello-${J7M_VERSION}/publisher.der" trust/publisher.der
bin/j7m-platform --config conf/platform.properties
```

For your own application instead, obtain its signed `.j7m` bundle and the
publisher's trusted Ed25519 public key in X.509 DER format. Confirm that key
through your trusted application-delivery process; do not install the private
key. The following alternative uses key ID `publisher`:

From this extracted platform directory:

```bash
mkdir -p applications trust var/repository
cp /path/to/received/hello.j7m applications/hello.j7m
cp /path/to/received/public.der trust/publisher.der
bin/j7m-platform --config conf/platform.properties
```

Edit `conf/platform.properties` for another bundle, trust key, repository or
application configuration. Paths are relative to that file. The supplied
configuration activates `example:hello:1.0.0` and sets its greeting to
`Hello from installed J7M`.

Successful startup prints `HELLO_STARTED Hello from installed J7M` and
`J7M_PLATFORM_RUNNING example:hello:1.0.0`. The process remains in the
foreground. Ctrl+C or SIGTERM deactivates the application and closes the
runtime; expect `HELLO_STOPPED` and `J7M_PLATFORM_STOPPED`. A process supervisor
should preserve the writable repository and allow graceful shutdown.

The launchers include `bin/j7m-platform.bat` for Windows. Filesystem ownership,
service-account permissions and supervision remain operating-system setup.

The supplied host uses loopback transports and disables nugget administration.
Its strict signature verification establishes bundle trust; it does not
configure cross-host TLS, native resource groups or application-specific host
factories. A bundle requiring those capabilities needs an explicitly assembled
host. An untrusted, changed or unsupported bundle is rejected rather than
activated under a weaker policy.

Complete operator guide: <https://www.egothor.org/J7M/deployment/installed-platform/>

