# Generated J7M metadata (format 1)

This optional JSR269 processor generates explicit schema allowlists and component definitions. Put `j7m-annotation-processor` on the annotation processor path and `j7m-api` on the application compile path. Classpath processor discovery uses `META-INF/services`; modular compilation uses `provides javax.annotation.processing.Processor`. No container or application activation is started during compilation.

For a public top-level record `example.Order` annotated with `@SchemaType`, the generated public provider is `example.Order__J7mSchema`. Every component requires `@WireField(id=...)`. Its positive numeric ID becomes the canonical decimal D00 `SchemaField.name`; renaming the Java component preserves the wire identity. Versions must be positive; a combined allowlist supports adjacent N/N−1 versions only. Added/removed fields must be optional. Scalars, byte arrays and explicitly registered nested schemas are supported. Required primitives are boxed in descriptors. Optional scalar defaults are validated at compilation and loading; nested/array defaults are unsupported. Generic records and other DTO shapes are rejected.

Assemble explicitly trusted providers with `SchemaIndexes.registry(...)`, then pass the resulting `SchemaRegistry` to `DefaultValueCodec`. `SchemaIndexes.load(layer)` discovers providers from an explicitly trusted module layer. A modular application declares, without any `opens`:

```java
provides org.j7m.middleware.codec.api.SchemaIndex
    with example.Order__J7mSchema;
```

Loading validates index format, bounds, schema IDs, version compatibility, field identities, types, defaults and annotation/descriptor consistency before any DTO factory is invoked. Loading a provider still executes trusted provider code; this is an application allowlist, not an untrusted plugin sandbox. The registry never loads a class named by an incoming payload.

A public concrete non-generic `@J7mComponent` class `example.Service` generates `example.Service__J7mComponentIndex`, implementing `ManagedComponentIndex`. It describes constructors, injection fields, public `@Produces` factories, exact qualifiers, explicit scope/ownership and finite dependency edges. A declared ancestor scope must match the effective component scope; redeclare a non-default inherited scope explicitly. Conflicting scopes fail compilation. Generated helpers use direct public access and metadata-preserving `ManagedResolver` overloads. Private/final/static injection fields and inaccessible constructors fail compilation, including inherited fields. Lazy providers retain their declared graph edge. Qualifier defaults, scalar, class, enum and array members use the shared canonical representation; nested annotation members are rejected. Method metadata retains binary declaring owner, erased parameter/return types, overloads and inherited declarations; index format is version 1. `ManagedMethod` is internal component signature metadata: the index `formatVersion` is not a service `contractVersion`. Use the A00 preflight `Report` envelope for a contract ID/version now; managed hosting must bind a verified contract ID/version to the complete signature before wire or durable use.

Modular consumers declare a separate provider for each component:

```java
provides org.j7m.middleware.application.api.ManagedComponentIndex
    with example.Service__J7mComponentIndex;
```

`@ConfigValue` edges retain key/default/required/refresh metadata; primitive keys are boxed. `@ConfigProperties` edges retain prefix/refresh metadata. Refresh requires an explicit managed provider. These descriptors do not grant host capabilities, enforce policies, create a service proxy or change legacy injection. The managed container and subsequent hosting adapters consume this opt-in metadata separately.

Active managed surfaces accept only the implemented injection/factory/scope/config metadata and custom qualifiers. Other J7M behavior markers, unsupported annotation sites and composed policy metadata fail compilation, including inherited members; they never yield an unprotected ordinary object. Later adapters must deliberately extend this allowlist.

Stable `J7M_*` compiler error codes identify rejected contracts without echoing annotation values. Runtime loader failures use bounded, fixed diagnostic text. Ordinary unsupported legacy annotations are not rewritten into new runtime behavior.

Rollback before adoption: remove the processor dependency and opt-in index wiring; existing injector/runtime APIs remain unchanged. After deployment, retain the previous compatible schema version and restore the previous application artifact rather than reusing a wire ID for different data.

Author checks:

```sh
./gradlew :j7m-annotation-processor:test :j7m-annotation-processor:pmdMain \
  :j7m-annotation-processor:pmdTest :j7m-annotation-processor:javadoc \
  :j7m-api:pmdMain :j7m-api:javadoc
```

Tests run real javac, generated adapters, D00 codec evolution and standalone JPMS javac/java subprocesses without global opens. No distributed soak task is attached to this module's build.
