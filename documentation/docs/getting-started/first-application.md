# Your first component: annotations and explicit APIs

This exercise creates a greeting component, resolves its formatter and executes lifecycle transitions. It deliberately starts with local component ownership before adding transport and deployment.

## Run the paired example

```shell
./gradlew -I documentation/examples/compile.init.gradle documentationRunContainer
```

Expected application output:

```text
ContainerExample OK: generated and explicit graphs, lifecycle, missing dependency
```

## Annotated application code

The component is a public top-level class with a public constructor. Generated metadata declares its dependency and direct lifecycle adapters:

```java title="GreetingComponent.java"
--8<-- "documentation/examples/src/main/java/org/j7m/documentation/GreetingComponent.java"
```

`@Inject` removes the formatter lookup from the component. `@Startup` and `@Shutdown` remove repeated callback mapping. The class still contains its application-level admission check; annotation presence does not automatically execute a method on a manually created object.

## Explicit host assembly

The same example also constructs annotation-free `PlainFormatter` and `PlainGreeting` products using explicit `ManagedComponentDefinition` objects. The host calls `start()` itself and the owned product closes through `AutoCloseable`.

```java title="ContainerExample.java — both complete variants"
--8<-- "documentation/examples/src/main/java/org/j7m/documentation/ContainerExample.java"
```

In the generated variant, the bare container explicitly runs lifecycle phases. In production managed deployment, the application host owns that sequencing and coordinates it with readiness and export admission.

## What to observe

Both variants produce the same greeting. The activation returns the same activation-scoped component identity. A call before startup or after shutdown is rejected. The example also removes a required dependency and verifies a safe missing-dependency failure instead of publishing a partially constructed graph.

This is not yet a signed deployed application or a refreshable remote service. Continue with [services and references](../programming/services.md), [dependency injection](../programming/injection.md) and [separate-JVM exercises](separate-jvms.md).
