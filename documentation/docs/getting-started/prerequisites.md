# Requirements and development environment

Application developers need a full **JDK 25**, including `javac` and `keytool`, and Gradle. The [standalone project guide](developer-project.md) shows the complete Maven dependency and annotation-processor configuration. A source checkout is required only to build J7M itself or run its repository examples; that checkout supplies the pinned Gradle wrapper. Documentation verification also uses Python 3.9 or newer.

Application operators use the [platform distribution](../deployment/installed-platform.md). Its launcher needs Java 25; running a supplied application bundle does not require Gradle, Python or the J7M source tree.

From a source checkout, start in the repository root:

```shell
java -version
python3 --version
./gradlew --version
```

The wrapper and dependency repositories require network access on first use. In an environment with an HTTP proxy, use its documented JVM/Gradle proxy configuration. The manual's example verifier can translate an existing `HTTPS_PROXY` setting into Java proxy options when they are not already supplied.

## Network and storage

Local integration examples bind loopback TCP sockets and create temporary state. TLS bootstrap tests use `keytool` to create test-only certificates. They require permission to start child JVMs and bind local ports.

Durable deployments additionally need actual persistent disks, authenticated native endpoints, quorum membership, clock policy and explicit recovery ownership. A local reducer adapter is useful for learning but does not establish those properties.

The development qualification described by the project uses Linux, Java 25 and three/five native voters where the test requires them. Do not infer qualification for another driver, transport profile, filesystem or deployment topology merely because Java source compiles there.

## Choose a safe initial scope

Begin on one host. The default gRPC/RMI configurations advertise `127.0.0.1` with allocated ports. gRPC can use explicit bind and advertised endpoints through its [network configuration](../configuration/transports.md); RMI remains loopback-only. Registry discovery across machines does not change those business endpoints. See [deployment topologies](../deployment/topologies.md) before moving the application to multiple machines.

Proceed to [building the examples](build.md).
