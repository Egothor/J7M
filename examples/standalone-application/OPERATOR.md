<!-- Copyright (c) 2026, Leo Galambos and the Egothor team -->
<!-- SPDX-License-Identifier: BSD-4-Clause -->

# Run the released Hello application

This example archive contains the signed `hello.j7m` application for
`example:hello:1.0.0` and its demo Ed25519 public key `publisher.der`. It contains
no private signing key. Use this public key for the supplied example only.

Download the matching `j7m-platform-VERSION.zip` or `.tar.gz` and `SHA256SUMS`
from the same GitHub release. Verify the archive checksums, then extract the
platform and Hello archives beside each other. Install Java 25; no Gradle,
compiler or J7M source checkout is needed.

From the extracted platform directory, substitute the actual release version:

```bash
J7M_VERSION=0.9.9
mkdir -p applications trust var/repository
cp "../j7m-hello-${J7M_VERSION}/hello.j7m" applications/hello.j7m
cp "../j7m-hello-${J7M_VERSION}/publisher.der" trust/publisher.der
bin/j7m-platform --config conf/platform.properties
```

The included platform configuration uses signing key ID `publisher` and sets
`greeting.message` to `Hello from installed J7M`. Paths are relative to
`conf/platform.properties`.

Expect `HELLO_STARTED Hello from installed J7M` and
`J7M_PLATFORM_RUNNING example:hello:1.0.0`. The foreground process remains
running. Ctrl+C or SIGTERM triggers deactivation; expect `HELLO_STOPPED` and
`J7M_PLATFORM_STOPPED`.

The supplied platform uses loopback transports and disables its administration
endpoint. Running this example does not provision a distributed resource group
or a cross-host TLS deployment.

For another application, obtain the publisher's bundle and independently
trusted public key, then configure the platform for that application's key ID,
bundle and required values. Keep the operator repository persistent; changed
bundle bytes need a new application coordinate version.

Complete operator guide: <https://www.egothor.org/J7M/deployment/installed-platform/>

