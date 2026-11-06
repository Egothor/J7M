# Getting started

If you are building your own application, start with the [standalone Gradle project](developer-project.md). If you have an application bundle to operate, use [the installed J7M platform](../deployment/installed-platform.md). Neither procedure requires a J7M source checkout once the matching release artifacts are available.

The source-checkout exercises below explain the individual mechanisms before adding remote communication and trusted host capabilities. You do not need to implement a framework-specific service interface to use generated component wiring.

| Step | Outcome |
| --- | --- |
| [Check requirements](prerequisites.md) | A usable Java 25 development environment |
| [Build and verify examples](build.md) | Current source compiled with its real annotation processor |
| [Run the first component](first-application.md) | Equivalent annotated and explicit dependency graphs |
| [Exercise separate JVMs](separate-jvms.md) | Actual same-host process communication and failure behavior |
| [Understand packaging and deployment](package-and-deploy.md) | The boundary between application code and a trusted host |

## Pick the right first example

The component example executes generated constructor injection and lifecycle callbacks. The runtime example performs real gRPC request/reply and callback traffic between two runtimes in one JVM. The schema example compares generated and explicit allowlists. The queue example executes the native queue reducer locally; it does not pretend to be a multi-voter cluster. The [failover example](../patterns/availability-failover.md#run-the-same-idea-as-a-small-application) shows sticky selection, topology refresh and replacement after target loss, with effect counters proving that a failed business operation was not replayed.

For actual generated queue, job and saga hosting, run the additional [integration verification](../testing/verified-examples.md). Each example states what it proves so a short tutorial does not become an unsupported production guarantee.

!!! important "Separate JVMs and separate hosts"
    The default gRPC and RMI configurations advertise loopback endpoints, so the first multi-JVM exercise runs on one host. gRPC also supports explicit bind and advertised endpoints; see [transport configuration](../configuration/transports.md). Multicast/bootstrap registry discovery and native resource servers have separate configuration and do not change invocation endpoints. RMI has no equivalent public bind/advertise configuration.
