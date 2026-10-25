# Getting started

Start with a small executable example, then add remote communication and the host capabilities your application actually needs. You do not need to implement a framework-specific service interface to use generated component wiring.

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
    The built-in gRPC and RMI providers currently advertise loopback endpoints. The first multi-JVM exercise therefore runs on one host. Multicast/network-bootstrap registry discovery and native resource servers have separate network configuration. Cross-host business invocation needs an appropriate host transport integration; there is no undocumented built-in advertised-host setting to copy.
