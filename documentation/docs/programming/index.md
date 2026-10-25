# Programming model

J7M lets an application expose ordinary Java interfaces, exchange typed values and references, and delegate distributed execution to explicitly installed middleware capabilities. Use annotations to describe the application contract; use host configuration to decide which capabilities are available and authorized.

The distinction matters. `@DurableJob` describes a job entry, but a trusted host still supplies its execution backend. `@ServiceRef` requests a binding, but the host still supplies discovery and authorization. This keeps application code compact without hiding operational decisions.

| Your task | Read next |
| --- | --- |
| Publish an interface and obtain a usable reference | [Services and references](services.md) |
| Construct components with explicit ownership | [Dependency injection](injection.md) |
| Start, admit, drain and stop an application | [Lifecycle](lifecycle.md) |
| Read configuration or current caller context | [Configuration and context](configuration-context.md) |
| Define safe data contracts and JPMS adapters | [Schemas and modules](schemas-jpms.md) |
| Understand asynchronous calls and physical completion | [Invocation semantics](invocation.md) |
| Consume bounded remote streams | [Remote streams](remote-streams.md) |
| Schedule local asynchronous work and multi-target calls | [Workload API](workloads.md) |
| Choose candidates and reduce remote-call overhead | [Selection and performance](selection-performance.md) |
| Append and replay local durable records | [Durable log](durable-log.md) |
| Use local durable CAS, leases and membership | [Local coordination](local-coordination.md) |
| Relate requests, callers and application activations | [Contexts and identities](contexts-identities.md) |
| Implement and evaluate a resource adapter | [Adapter SPI and TCK](adapters.md) |
| Assemble JPMS modules and providers | [Module dependencies](module-dependencies.md) |
| Choose an application architecture | [Distributed patterns](../patterns/index.md) |

The examples use Java 25. Code fragments show the relevant application contract; complete executable examples and their verification commands are collected in [Verified examples](../testing/verified-examples.md).

## Two complementary entry points

**Explicit APIs** are useful for embedding J7M, assembling a trusted host and controlling resource ownership directly. **Annotated components** remove repeated dependency resolution, dispatch metadata and lifecycle wiring from application classes. Both use the same security, transport and resource boundaries.

An ordinary Java call to another method on the same object remains an ordinary Java call. It does not establish a new authorization, transaction, retry or managed invocation boundary merely because the target method has an annotation.

## Four questions before writing a handler

1. Who owns its dependencies and when may they be closed?
2. What is the stable identity of the operation across retries or restart?
3. Which outcomes can be known after a timeout or lost reply?
4. Which resource actually enforces the fence, transaction or idempotency key?

Answer these questions at the application boundary. An annotation can make that answer visible in code; it cannot manufacture a guarantee that the underlying resource does not provide.
