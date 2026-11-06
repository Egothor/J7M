<!-- Copyright (c) 2026, Leo Galambos and the Egothor team -->
<!-- SPDX-License-Identifier: BSD-4-Clause -->

# Install the platform and run an application

Download the platform and the supplied Hello application to run the first
example entirely from release assets. You can then install a signed `.j7m`
bundle from your own application publisher. The platform archive contains the
executable host, J7M libraries and a configuration file. You need **Java 25**;
you do not need Gradle, Maven or a J7M source checkout on the application host.

The Hello application archive contains its signed bundle, demo public key and
offline instructions. It contains no private signing key. The
[developer procedure](../getting-started/developer-project.md) shows how to
produce and deliver your own application instead.

## Download and unpack a release

Choose a published J7M version from [GitHub Releases](https://github.com/Egothor/J7M/releases).
The commands use `0.9.9`; replace it with a release that lists the platform
archives, `j7m-hello-VERSION.zip` and `SHA256SUMS`. These URLs identify release assets produced by the
release workflow; an unreleased version is not downloadable merely because it
appears in this manual.

For Linux or another Unix-like host:

```bash
J7M_VERSION=0.9.9
J7M_RELEASE="https://github.com/Egothor/J7M/releases/download/release%40${J7M_VERSION}"
curl --fail --location --remote-name "$J7M_RELEASE/j7m-platform-${J7M_VERSION}.tar.gz"
curl --fail --location --remote-name "$J7M_RELEASE/j7m-hello-${J7M_VERSION}.zip"
curl --fail --location --remote-name "$J7M_RELEASE/SHA256SUMS"
grep "  j7m-platform-${J7M_VERSION}.tar.gz$" SHA256SUMS | sha256sum --check -
grep "  j7m-hello-${J7M_VERSION}.zip$" SHA256SUMS | sha256sum --check -
tar -xzf "j7m-platform-${J7M_VERSION}.tar.gz"
unzip "j7m-hello-${J7M_VERSION}.zip"
cd "j7m-platform-${J7M_VERSION}"
java -version
```

The ZIP asset is named `j7m-platform-VERSION.zip`. On Windows, extract it and
use `bin\j7m-platform.bat` with the same `--config` argument. Set `JAVA_HOME`
to the Java 25 installation if it is not already the default Java.

The installed layout separates executable code from application and runtime state:

```text
j7m-platform-VERSION/
  bin/                 platform launchers
  lib/                 platform and dependency JARs
  conf/                platform.properties
  applications/        signed application bundles supplied by publishers
  trust/               trusted public signing keys
  var/repository/      local application repository created by the host
```

## Install the supplied Hello application

The example archive extracts into `j7m-hello-VERSION/` beside the platform.
From the platform installation directory:

```bash
mkdir -p applications trust var/repository
cp "../j7m-hello-${J7M_VERSION}/hello.j7m" applications/hello.j7m
cp "../j7m-hello-${J7M_VERSION}/publisher.der" trust/publisher.der
```

The demo key authorizes this released Hello application under signing key ID
`publisher`. Use it only for this example. Your own publisher's applications
need the publisher key you have chosen to trust; the demo key is not a general
production signing authority.

Proceed to the configuration and startup below. No build step is needed.

## Install your own publisher's application instead

Receive these two files from the application publisher:

| File | Meaning |
| --- | --- |
| `hello.j7m` | Immutable signed bundle for `example:hello:1.0.0` |
| `public.der` | Publisher's Ed25519 public key in X.509 DER format |

Confirm the public key with the publisher through your normal trusted delivery
process. A key included beside a downloaded bundle does not establish who owns it.
The operator needs the public key only; keep the publisher's `private.der` off
the application host.

From the platform installation directory, copy the received files:

```bash
mkdir -p applications trust var/repository
cp /path/to/received/hello.j7m applications/hello.j7m
cp /path/to/received/public.der trust/publisher.der
```

Use the publisher's actual bundle name, signing key ID and configuration when
editing `conf/platform.properties`. These commands are an alternative to
installing the supplied Hello example, not an additional requirement for it.

Keep `bin/`, `lib/`, `conf/`, `applications/` and `trust/` under the operator's
control. The service account must be able to read them and write its own
`var/repository/`. Preserve that repository across an ordinary restart.

## Configure the host

Edit `conf/platform.properties`:

```properties
runtime.id=j7m-platform
application.bundle=../applications/hello.j7m
repository.path=../var/repository
trust.key-id=publisher
trust.public-key=../trust/publisher.der
application.environment=production
application.config.j7m.annotations.profile=managed-v1
application.config.greeting.message=Hello from installed J7M
```

Paths are relative to the configuration file, not to the shell's current
directory. The signing key ID must match the ID used when the publisher signed
the bundle. `application.config.*` values are passed to the application without
the prefix. The managed annotation profile selects generated component hosting;
it does not provision durable resource groups or grant additional permissions.

## Start and stop

Run the actual platform host in the foreground:

```bash
bin/j7m-platform --config conf/platform.properties
```

The host verifies the application against the configured public key, imports
it into the local repository and activates it through `ApplicationManager`.
The sample application writes:

```text
HELLO_STARTED Hello from installed J7M
```

The host also writes `J7M_PLATFORM_RUNNING example:hello:1.0.0` after activation
has entered `RUNNING`.

The process remains running. Stop it with **Ctrl+C**, or send **SIGTERM** from
your process supervisor. The host deactivates the application and closes the
runtime; the sample writes `HELLO_STOPPED`. For a service installation, configure
the supervisor's working directory, Java 25 environment, service account,
standard-output/error logging and sufficient graceful-stop time. Do not use
`kill -9` for an ordinary shutdown.

The supplied host uses loopback gRPC/RMI defaults and disables the nugget
administration endpoint. Its `application.environment=production` setting is
application metadata; it does not enable TLS or expose a remotely managed
production service. The properties shown here configure bundle trust,
application values and local storage. An application needing authenticated
remote endpoints or native resource factories requires an explicitly assembled
host with those capabilities; copying additional property names does not
install them.

The separate `j7m-deployer-cli` tool still provides packaging and repository
commands. Its legacy `app start` command and the Gradle plugin's `j7mRunLocal`
task do not replace this executable host.

## Diagnose startup failures

| Symptom | Check |
| --- | --- |
| Java class-version error | The launcher is using Java 25, including when started by a supervisor. |
| Configuration or file error | The configuration file exists; bundle/key paths resolve relative to it and are readable by the service account. |
| Signature rejection | Bundle bytes are unchanged, key ID matches, and the configured public key is the publisher key you trust. |
| Coordinate conflict | A different bundle cannot replace an already imported version; obtain a new application version from its publisher. |
| Activation failure | Inspect the application's startup error and provide the resources, configuration and host capabilities declared by that application. |

Use [upgrade and rollback](upgrade-rollback.md) for the application lifecycle
contract and [network configuration](network-storage.md) when moving beyond a
single-host installation. The supplied example proves this install/start/stop
path; it does not configure a native consensus cluster or a cross-host TLS topology.

