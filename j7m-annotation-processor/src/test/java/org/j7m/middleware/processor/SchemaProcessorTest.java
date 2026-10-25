/*
 * BSD 4-Clause License
 *
 * Copyright (c) 2026, Leo Galambos and the Egothor team
 * Website of the Egothor team: https://www.egothor.org
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. All advertising materials mentioning features or use of this
 * software must display the following acknowledgement:
 *
 * This product includes software developed by Leo Galambos and the Egothor
 * team (https://www.egothor.org).
 *
 * 4. Neither the name of the copyright holder nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.j7m.middleware.processor;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.j7m.middleware.codec.api.SchemaIndex;
import org.j7m.middleware.codec.api.SchemaIndexes;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises real javac discovery, generated direct adapters and D00 schema evolution.
 */
class SchemaProcessorTest {
    @TempDir Path temporary;

    @Test
    void generatedAllowlistRoundTripsNestedBytesAndAdjacentRenamedFields() throws Exception {
        Path output = compile(List.of(
                source("Old", "@SchemaType(id=\"order\",version=1) public record Old(@WireField(id=7) String original) {}"),
                source("New", "@SchemaType(id=\"order\",version=2) public record New(@WireField(id=7) String renamed,@WireField(id=8,required=false,hasDefault=true,defaultValue=\"42\") Integer extra) {}"),
                source("Nested", "@SchemaType(id=\"nested\",version=1) public record Nested(@WireField(id=1) New child,@WireField(id=2) byte[] bytes) {}")), true);
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            List<SchemaIndex> indexes = new ArrayList<>();
            ServiceLoader.load(SchemaIndex.class, loader).forEach(indexes::add);
            assertEquals(3, indexes.size());
            var factories = new java.util.concurrent.atomic.AtomicInteger();
            assertThrows(IllegalArgumentException.class, () -> SchemaIndexes.registry(List.of(corrupt(indexes.getFirst().schemas().getFirst(), factories))));
            assertEquals(0, factories.get());
            assertThrows(IllegalArgumentException.class, () -> SchemaIndexes.registry(List.of(indexes.getFirst(), indexes.getFirst())));
            assertThrows(IllegalArgumentException.class, () -> SchemaIndexes.registry(List.of(new SchemaIndex() {
                public int formatVersion() { return 2; }
                public List<org.j7m.middleware.codec.api.SchemaDescriptor<?>> schemas() { throw new AssertionError("must check format first"); }
            })));
            DefaultValueCodec codec = new DefaultValueCodec(SchemaIndexes.registry(indexes));
            Class<?> old = loader.loadClass("fixture.Old");
            Class<?> current = loader.loadClass("fixture.New");
            assertEquals("order", old.getAnnotation(org.j7m.middleware.annotations.SchemaType.class).id());
            assertEquals(old.getAnnotation(org.j7m.middleware.annotations.SchemaType.class).id(),
                    current.getAnnotation(org.j7m.middleware.annotations.SchemaType.class).id());
            assertNotEquals(old.getRecordComponents()[0].getName(), current.getRecordComponents()[0].getName());
            assertEquals(7, old.getRecordComponents()[0].getAnnotation(org.j7m.middleware.annotations.WireField.class).id());
            assertEquals(old.getRecordComponents()[0].getAnnotation(org.j7m.middleware.annotations.WireField.class).id(),
                    current.getRecordComponents()[0].getAnnotation(org.j7m.middleware.annotations.WireField.class).id());
            Object original = old.getConstructor(String.class).newInstance("preserved");
            Object evolved = codec.decode(codec.encode(original, old, null), current, null);
            assertEquals("preserved", current.getMethod("renamed").invoke(evolved));
            assertEquals(42, current.getMethod("extra").invoke(evolved));
            Object downgraded = codec.decode(codec.encode(evolved, current, null), old, null);
            assertEquals(original, downgraded);
            Class<?> nested = loader.loadClass("fixture.Nested");
            Object value = nested.getConstructor(current, byte[].class).newInstance(evolved, new byte[]{1, 2});
            Object decoded = codec.decode(codec.encode(value, nested, null), nested, null);
            assertEquals(evolved, nested.getMethod("child").invoke(decoded));
            assertArrayEquals(new byte[]{1, 2}, (byte[]) nested.getMethod("bytes").invoke(decoded));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "@SchemaType(id=\"bad id\",version=1) public record Bad(@WireField(id=1) String x){}",
        "@SchemaType(id=\"bad\",version=0) public record Bad(@WireField(id=1) String x){}",
        "@SchemaType(id=\"bad\",version=1) public record Bad(@WireField(id=1) String x,@WireField(id=1) String y){}",
        "@SchemaType(id=\"bad\",version=1) public record Bad(String x){}",
        "@SchemaType(id=\"bad\",version=1) public record Bad(@WireField(id=1,required=false) int x){}",
        "@SchemaType(id=\"bad\",version=1) public record Bad(@WireField(id=1) java.util.List<String> x){}",
        "@SchemaType(id=\"bad\",version=1) public record Bad(@WireField(id=1,required=false,hasDefault=true,defaultValue=\"999\") Byte x){}",
        "@SchemaType(id=\"bad\",version=1) public class Bad {}"
    })
    void rejectsInvalidSchemaContractsAtCompilation(final String body) throws Exception {
        compile(List.of(source("Bad", body)), false);
    }

    @Test
    void rejectsDuplicateAndNonAdjacentOrRequiredEvolutionAtCompilation() throws Exception {
        String old = source("Old", "@SchemaType(id=\"same\",version=1) public record Old(@WireField(id=1) String value){}");
        for (String current : List.of(
            "@SchemaType(id=\"same\",version=1) public record New(@WireField(id=1) String value){}",
            "@SchemaType(id=\"same\",version=3) public record New(@WireField(id=1) String value){}",
            "@SchemaType(id=\"same\",version=2) public record New(@WireField(id=1) String value,@WireField(id=2) String required){}")) {
            compile(List.of(old, source("New", current)), false);
        }
    }

    @Test
    void standaloneNamedModuleDiscoversProcessorAndRunsWithoutOpens() throws Exception {
        Path sources = temporary.resolve("modsrc");
        Files.createDirectories(sources.resolve("fixture"));
        Files.writeString(sources.resolve("module-info.java"), "module consumer { requires org.j7m.api; provides org.j7m.middleware.codec.api.SchemaIndex with fixture.Value__J7mSchema; provides org.j7m.middleware.application.api.ManagedComponentIndex with fixture.Service__J7mComponentIndex; uses org.j7m.middleware.application.api.ManagedComponentIndex; }");
        Files.writeString(sources.resolve("fixture/Value.java"), source("Value", "@SchemaType(id=\"value\",version=1) public record Value(@WireField(id=1) String text){}"));
        Files.writeString(sources.resolve("fixture/Service.java"), source("Service", "@J7mComponent public class Service {public Service(){} public String echo(String x){return x;}}"));
        Files.writeString(sources.resolve("fixture/Main.java"), "package fixture; public class Main {public static void main(String[] args){var registry=org.j7m.middleware.codec.api.SchemaIndexes.load(ModuleLayer.boot());if(registry.forType(Value.class)==null)throw new AssertionError();var index=java.util.ServiceLoader.load(org.j7m.middleware.application.api.ManagedComponentIndex.class).findFirst().orElseThrow();try{if(!(index.components().getFirst().create(null) instanceof Service))throw new AssertionError();}catch(Exception error){throw new AssertionError(error);}System.out.println(\"JPMS_OK\");}}");
        Path output = temporary.resolve("mods");
        Files.createDirectories(output);
        String api = System.getProperty("j7m.processor.test.api");
        String processor = System.getProperty("j7m.processor.test.jar");
        Process compile = new ProcessBuilder(javaTool("javac"), "--module-path", api, "--processor-module-path", processor + java.io.File.pathSeparator + api,
                "-d", output.toString(), sources.resolve("module-info.java").toString(), sources.resolve("fixture/Value.java").toString(), sources.resolve("fixture/Service.java").toString(), sources.resolve("fixture/Main.java").toString()).redirectErrorStream(true).start();
        String diagnostics = new String(compile.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, compile.waitFor(), diagnostics);
        Process run = new ProcessBuilder(javaTool("java"), "--module-path", output + java.io.File.pathSeparator + api, "-m", "consumer/fixture.Main").redirectErrorStream(true).start();
        String result = new String(run.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, run.waitFor(), result);
        assertTrue(result.contains("JPMS_OK"), result);
    }

    @Test
    void componentIndexPreservesOverloadsInheritanceFactoriesAndDirectAccess() throws Exception {
        Path output = compile(List.of(
            source("Base", "public interface Base {Number call(String x); String call(int[] x);}"),
            source("Dep", "public class Dep {public Dep() {}}"),
            source("Component", "@J7mComponent public class Component implements Base {public final Dep dep; @Inject public Component(Dep dep){this.dep=dep;} @Inject public Dep field; public Integer call(String x){return 1;} public String call(int[] x){return \"array\";} @Produces public String product(Dep dep){return \"made\";} }")), true);
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            var index = (org.j7m.middleware.application.api.ManagedComponentIndex) loader.loadClass("fixture.Component__J7mComponentIndex").getConstructor().newInstance();
            assertEquals(1, index.formatVersion());
            assertEquals(2, index.components().size());
            assertEquals(4, index.methods().stream().filter(m -> m.name().equals("call")).count());
            assertTrue(index.methods().stream().anyMatch(m -> m.parameterTypes().equals(List.of("[I"))));
            assertTrue(index.methods().stream().anyMatch(m -> m.returnType().equals("java.lang.Number")));
            Object dep = loader.loadClass("fixture.Dep").getConstructor().newInstance();
            var resolver = new org.j7m.middleware.application.api.ManagedResolver() {
                public <T> T resolve(Class<T> type, String name, List<org.j7m.middleware.application.api.ManagedQualifier> qualifiers) { return type.cast(dep); }
                public <T> org.j7m.middleware.application.api.ManagedProvider<T> provider(Class<T> type, String name, List<org.j7m.middleware.application.api.ManagedQualifier> qualifiers) { return () -> type.cast(dep); }
            };
            var definition = index.components().getFirst();
            assertEquals(2, definition.dependencies().size());
            Object component = definition.create(resolver);
            inject(definition, component, resolver);
            assertSame(dep, component.getClass().getField("field").get(component));
            assertSame(dep, component.getClass().getField("dep").get(component));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void inject(org.j7m.middleware.application.api.ManagedComponentDefinition<T> definition, Object instance,
            org.j7m.middleware.application.api.ManagedResolver resolver) throws Exception {
        definition.inject((T) instance, resolver);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "@J7mComponent public class Bad {private Bad(){}}",
        "@J7mComponent public class Bad {public Bad(){} @Inject private String field;}",
        "@J7mComponent public class Bad<T> {public Bad(){}}",
        "@J7mComponent public class Bad {public Bad(java.util.List<String> values){}}",
        "@J7mComponent public class Bad {@Inject public Bad(String x){} @Inject public Bad(Integer x){}}"
    })
    void rejectsInaccessibleOrAmbiguousManagedPlans(final String body) throws Exception {
        compile(List.of(source("Bad", body)), false);
    }

    @Test
    void configEdgesPreserveMetadataAndBoxPrimitiveWithoutCallingResolverByType() throws Exception {
        Path output = compile(List.of(source("Settings", "@J7mComponent public class Settings { public final int port; public Settings(@ConfigValue(value=\"port\",required=true) int port){this.port=port;} }")), true);
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            var index = (org.j7m.middleware.application.api.ManagedComponentIndex) loader.loadClass("fixture.Settings__J7mComponentIndex").getConstructor().newInstance();
            var dependency = index.components().getFirst().dependencies().getFirst();
            assertEquals(Integer.class, dependency.type());
            assertEquals("port", dependency.attributes().get("config.key"));
            assertEquals("true", dependency.attributes().get("config.required"));
            var resolver = new org.j7m.middleware.application.api.ManagedResolver() {
                public <T> T resolve(Class<T> type, String name, List<org.j7m.middleware.application.api.ManagedQualifier> qualifiers) { throw new AssertionError("metadata lost"); }
                public <T> org.j7m.middleware.application.api.ManagedProvider<T> provider(Class<T> type, String name, List<org.j7m.middleware.application.api.ManagedQualifier> qualifiers) { throw new AssertionError(); }
                @SuppressWarnings("unchecked") public <T> T resolve(org.j7m.middleware.application.api.ManagedDependency edge) { assertEquals(dependency, edge); return (T) Integer.valueOf(8080); }
            };
            Object settings = index.components().getFirst().create(resolver);
            assertEquals(8080, settings.getClass().getField("port").get(settings));
        }
    }

    @Test
    void generatedQualifierDefaultsArraysAndPrimitiveMembersMatchRuntimeIdentity() throws Exception {
        Path output = compile(List.of(
            source("Label", "@Qualifier @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Label {String value() default \"west\"; byte small() default 2; int[] ports() default {3,4}; Class<?> target() default String.class; float nan() default Float.NaN; double infinity() default Double.POSITIVE_INFINITY;}"),
            source("Tagged", "@Label @J7mComponent public class Tagged { public Tagged(){} }")), true);
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            var index = (org.j7m.middleware.application.api.ManagedComponentIndex) loader.loadClass("fixture.Tagged__J7mComponentIndex").getConstructor().newInstance();
            var qualifier = index.components().getFirst().qualifiers().getFirst();
            assertEquals("fixture.Label", qualifier.annotationType());
            assertEquals(org.j7m.middleware.application.api.ManagedQualifier.value("west"), qualifier.members().get("value"));
            assertEquals(org.j7m.middleware.application.api.ManagedQualifier.value((byte) 2), qualifier.members().get("small"));
            assertEquals(org.j7m.middleware.application.api.ManagedQualifier.value(new int[]{3, 4}), qualifier.members().get("ports"));
            assertEquals(org.j7m.middleware.application.api.ManagedQualifier.value(String.class), qualifier.members().get("target"));
            assertEquals(org.j7m.middleware.application.api.ManagedQualifier.value(Float.NaN), qualifier.members().get("nan"));
            assertEquals(org.j7m.middleware.application.api.ManagedQualifier.value(Double.POSITIVE_INFINITY), qualifier.members().get("infinity"));
        }
    }

    @Test
    void malformedRuntimeDescriptorsFailBeforeApplicationFactoryRuns() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var descriptor = new org.j7m.middleware.codec.api.SchemaDescriptor<>("string", 1, String.class, List.of(),
                value -> java.util.Map.of(), fields -> {calls.incrementAndGet(); return "bad";});
        SchemaIndex invalid = () -> List.of(descriptor);
        assertThrows(IllegalArgumentException.class, () -> SchemaIndexes.registry(List.of(invalid)));
        assertEquals(0, calls.get());
        assertNotNull(SchemaIndexes.registry(List.of()));
        assertThrows(IllegalArgumentException.class, () -> SchemaIndexes.registry(java.util.Collections.nCopies(257, invalid)));
    }

    private static <T> SchemaIndex corrupt(org.j7m.middleware.codec.api.SchemaDescriptor<T> original,
            java.util.concurrent.atomic.AtomicInteger factories) {
        var first = original.fields().getFirst();
        var bad = new org.j7m.middleware.codec.api.SchemaField("999", first.type(), first.required(), first.defaultValue());
        var descriptor = new org.j7m.middleware.codec.api.SchemaDescriptor<>(original.schemaId(), original.version(), original.javaType(), List.of(bad),
                original.encoder(), fields -> { factories.incrementAndGet(); throw new AssertionError(); });
        return () -> List.of(descriptor);
    }

    @Test
    void rejectsInheritedPrivateInjectionInsteadOfSilentlyIgnoringIt() throws Exception {
        compile(List.of(source("Parent", "public class Parent { @Inject private String hidden; public Parent(){} }"),
                source("Child", "@J7mComponent public class Child extends Parent { public Child(){} }")), false);
    }

    @Test
    void generatedDefaultsEscapeControlCharactersAsJavaStringData() throws Exception {
        Path output = compile(List.of(source("Text", "@SchemaType(id=\"text\",version=1) public record Text(@WireField(id=1,required=false,hasDefault=true,defaultValue=\"line\\nnext\") String value){}")), true);
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            var index = (SchemaIndex) loader.loadClass("fixture.Text__J7mSchema").getConstructor().newInstance();
            assertEquals("line\nnext", index.schemas().getFirst().fields().getFirst().defaultValue());
            assertNotNull(SchemaIndexes.registry(List.of(index)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "@J7mComponent @SagaDefinition(name=\"workflow\") public class Bad {public Bad(){}}",
        "@J7mComponent public class Bad {public Bad(){} @DurableQueueConsumer(queue=\"jobs\") public void work(){}}"
    })
    void rejectsActiveUnsupportedManagedBehaviorRatherThanGeneratingAnUnprotectedObject(final String body) throws Exception {
        compile(List.of(source("Bad", body)), false);
    }

    @Test
    void rejectsInheritedAndComposedUnsupportedManagedPolicies() throws Exception {
        compile(List.of(source("Policy", "@SagaDefinition(name=\"meta\") @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Policy {}"),
            source("Child", "@Policy @J7mComponent public class Child {public Child(){}}")), false);
        compile(List.of(source("Parent", "public class Parent {public Parent(){} @DurableQueueConsumer(queue=\"jobs\") public void process(){}}"),
            source("Child", "@J7mComponent public class Child extends Parent {public Child(){}}")), false);
        compile(List.of(source("Child", "@J7mComponent public class Child {public Child(){} @Scope public void plain(){}}")), false);
    }

    @Test
    void rejectsUnsupportedPoliciesOnInheritedTypesBeforeGeneratingManagedIndex() throws Exception {
        compile(List.of(source("Secure", "@SagaDefinition(name=\"admin\") public interface Secure {void call();}"),
            source("Child", "@J7mComponent public class Child implements Secure {public Child(){} public void call(){}}")), false);
        compile(List.of(source("Secure", "@SagaDefinition(name=\"admin\") public class Secure {public Secure(){}}"),
            source("Parent", "public class Parent extends Secure {public Parent(){}}"),
            source("Child", "@J7mComponent public class Child extends Parent {public Child(){}}")), false);
    }

    @Test
    void rejectsInheritedScopeWideningInsteadOfSilentlySelectingActivation() throws Exception {
        compile(List.of(source("Parent", "@Scope(Scope.Kind.CALL) public class Parent {public Parent(){}}"),
            source("Child", "@J7mComponent public class Child extends Parent {public Child(){}}")), false);
    }

    @Test
    void explicitMatchingInheritedScopeIsPreservedButConflictingScopeIsRejected() throws Exception {
        String parent = source("Parent", "@Scope(Scope.Kind.CALL) public class Parent {public Parent(){}}");
        Path output = compile(List.of(parent, source("Child", "@Scope(Scope.Kind.CALL) @J7mComponent public class Child extends Parent {public Child(){}}")), true);
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            var index = (org.j7m.middleware.application.api.ManagedComponentIndex) loader.loadClass("fixture.Child__J7mComponentIndex").getConstructor().newInstance();
            assertEquals(org.j7m.middleware.annotations.Scope.Kind.CALL, index.components().getFirst().scope());
        }
        compile(List.of(parent, source("Child", "@Scope(Scope.Kind.PROTOTYPE) @J7mComponent public class Child extends Parent {public Child(){}}")), false);
    }

    @org.j7m.middleware.annotations.SchemaType(id="malformed", version=1)
    public record Malformed(@org.j7m.middleware.annotations.WireField(id=1, required=false, hasDefault=true,
            defaultValue="SYNTHETIC_SECRET_728") Integer value) { }

    @Test
    void runtimeMalformedNumericDefaultsNeverExposeRawValuesOrParserCauses() {
        var descriptor = new org.j7m.middleware.codec.api.SchemaDescriptor<>("malformed", 1, Malformed.class,
                List.of(new org.j7m.middleware.codec.api.SchemaField("1", Integer.class, false, 0)),
                value -> java.util.Map.of(), fields -> { throw new AssertionError("factory must not run"); });
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SchemaIndexes.registry(List.of(() -> List.of(descriptor))));
        assertFalse(failure.getMessage().contains("SYNTHETIC_SECRET_728"));
        assertNull(failure.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "@J7mComponent public class Bad {public Bad(@ConfigValue(\"port\") int port){}}",
        "@J7mComponent public class Bad {public Bad(){} @ConfigValue(\"enabled\") public boolean enabled;}"
    })
    void rejectsOptionalPrimitiveConfigWithoutExplicitDefaultBeforeConstruction(final String body) throws Exception {
        compile(List.of(source("Bad", body)), false);
    }

    @Test
    void explicitPrimitiveConfigDefaultsAndNullableWrappersRemainSupported() throws Exception {
        compile(List.of(source("Defaults", "@J7mComponent public class Defaults {public Defaults(@ConfigValue(value=\"port\",defaultValue=\"0\") int port, @ConfigValue(value=\"optional\") Integer optional){} @ConfigValue(value=\"enabled\",defaultValue=\"false\") public boolean enabled;}")), true);
    }

    @Test
    void managedCallingSignaturesRejectConflictsRawStagesAndUnsafeRetry() throws Exception {
        for (String method : List.of(
                "public java.util.concurrent.CompletionStage<String> work(){return null;}",
                "@Async public String work(){return null;}",
                "@Async public java.util.concurrent.CompletionStage work(){return null;}",
                "@Async @OneWay public java.util.concurrent.CompletionStage<String> work(){return null;}",
                "@OneWay public String work(){return null;}",
                "@Idempotent @NonIdempotent public String work(){return null;}",
                "@Deadline(millis=0) public String work(){return null;}",
                "@Retry(maxAttempts=1) public String work(){return null;}")) {
            compile(List.of(source("Bad", "@J7mComponent public class Bad {public Bad(){} @RemoteMethod " + method + "}")), false);
        }
        compile(List.of(source("Good", "@J7mComponent public class Good {public Good(){} @RemoteMethod @Async @Deadline(millis=100) public java.util.concurrent.CompletionStage<String> work(){return null;} @RemoteMethod @OneWay public void fire(){} @RemoteMethod @Async public java.util.concurrent.CompletionStage<byte[]> bytes(){return null;} }")), true);
    }

    Path compile(final List<String> sources, final boolean successful) throws Exception {
        Path directory = Files.createTempDirectory(temporary, "compile");
        List<Path> paths = new ArrayList<>();
        for (String source : sources) {
            var matcher = java.util.regex.Pattern.compile("public (?:record|class|interface|@interface) ([A-Za-z0-9_]+)").matcher(source);
            assertTrue(matcher.find());
            String name = matcher.group(1);
            Path path = directory.resolve(name + ".java");
            Files.writeString(path, source);
            paths.add(path);
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (var files = ToolProvider.getSystemJavaCompiler().getStandardFileManager(diagnostics, null, null)) {
            var units = files.getJavaFileObjectsFromPaths(paths);
            boolean result = ToolProvider.getSystemJavaCompiler().getTask(null, files, diagnostics,
                    List.of("-classpath", System.getProperty("j7m.processor.test.classpath"), "-processor", J7mProcessor.class.getName(), "-d", directory.toString()), null, units).call();
            assertEquals(successful, result, diagnostics.getDiagnostics().toString());
            if (!successful) { assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d -> d.getMessage(null).contains("J7M_"))); }
        }
        return directory;
    }

    static String source(final String name, final String body) {
        assertTrue(body.contains(" " + name));
        return "package fixture; import org.j7m.middleware.annotations.*; " + body;
    }

    private static String javaTool(final String name) {
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }
}
