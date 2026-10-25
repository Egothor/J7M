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

package org.j7m.middleware.examples.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.BindingCandidateSource;
import org.j7m.middleware.application.api.BindingCandidates;
import org.j7m.middleware.application.api.BindingPolicy;
import org.j7m.middleware.application.api.ManagedComponentIndex;
import org.j7m.middleware.application.api.ManagedQualifier;
import org.j7m.middleware.application.components.LiveConfiguration;
import org.j7m.middleware.application.components.LiveServiceBindings;
import org.j7m.middleware.application.components.ManagedContainer;
import org.j7m.middleware.application.components.ManagedHostBindings;
import org.j7m.middleware.application.components.ServiceBindingFactory;
import org.j7m.middleware.codec.api.EncodedValue;
import org.j7m.middleware.codec.api.SchemaIndex;
import org.j7m.middleware.codec.api.SchemaIndexes;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.processor.J7mProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compiles the actual API-only reference and executes its generated production adapters.
 */
class ManagedAnnotationUsageTest {
    @TempDir Path directory;

    @Test
    void generatedFactoriesConfigurationScopesAndOptionalServiceUseRealManagedAdapters() throws Exception {
        AtomicInteger watchers = new AtomicInteger();
        BindingCandidateSource emptySource = (request, policy, observations) -> {
            watchers.incrementAndGet();
            observations.accept(new BindingCandidates(1, List.of(), true));
            return () -> watchers.decrementAndGet();
        };
        try (var loader = compileReference();
                var configuration = new LiveConfiguration(1, Map.of("reference.region", "eu", "reference.attempts", "3"));
                var owner = new LiveServiceBindings(emptySource,
                        (reference, contract, options) -> { throw new AssertionError("An empty source must not connect or invoke business code"); },
                        (request, reference) -> false, "reference-host", Map.of("optional", optionalPolicy()),
                        () -> Instant.now().plusSeconds(1))) {
            List<ManagedComponentIndex> indexes = indexes(loader);
            assertEquals(1, indexes.size());
            var dependency = indexes.stream().flatMap(index -> index.components().stream())
                    .filter(definition -> definition.type() == ManagedAnnotationUsage.RemoteConsumer.class)
                    .flatMap(definition -> definition.dependencies().stream()).findFirst().orElseThrow();
            assertEquals(ManagedAnnotationUsage.RemoteApi.class, dependency.type());
            assertTrue(dependency.provider());
            assertEquals("true", dependency.attributes().get("binding.optional"));
            assertEquals("examples.annotation.remote", dependency.attributes().get("service.contract.id"));
            var bindings = new ManagedHostBindings(Map.of("service.name", new ServiceBindingFactory(owner, "default")));
            try (var container = ManagedContainer.activate(indexes, 32, configuration, bindings)) {
                var application = container.get(ManagedAnnotationUsage.class, "", List.of());
                assertEquals("formal:eu:hello Ada", application.greet("Ada"));
                assertEquals(3, application.attempts());
                var style = new ManagedQualifier(ManagedUsageStyle.class.getName(), Map.of("value", ManagedQualifier.value("formal")));
                var first = container.get(ManagedAnnotationUsage.Formatter.class, "", List.of(style));
                var second = container.get(ManagedAnnotationUsage.Formatter.class, "", List.of(style));
                assertNotSame(first, second, "The generated factory's PROTOTYPE lifetime must be effective");
                assertEquals("formal:us:hello Bo", first.format("us", "Bo"));
                assertEquals("unavailable:Ada", application.greetOrFallback("Ada"));
                assertEquals("unavailable:Bo", container.get(ManagedAnnotationUsage.RemoteConsumer.class, "", List.of()).greetOrFallback("Bo"));
                assertEquals(1, owner.status().bindings());
                assertTrue(configuration.update(2, Map.of("reference.region", "us", "reference.attempts", "4")));
                assertEquals("formal:eu:hello Ada", application.greet("Ada"), "Direct config injection is an activation snapshot");
            }
            assertEquals(0, owner.status().bindings());
            assertEquals(0, watchers.get(), "Container shutdown must close the factory-owned service binding");
        }
    }

    @Test
    void generatedSchemaRoundTripsTypedRecordAndEnforcesNumericFieldsDefaultsAndRequiredValues() throws Exception {
        try (var loader = compileReference()) {
            var schemas = ServiceLoader.load(SchemaIndex.class, loader).stream().map(ServiceLoader.Provider::get).toList();
            assertEquals(1, schemas.size());
            var codec = new DefaultValueCodec(SchemaIndexes.registry(schemas));
            var original = new ManagedUsagePayload("hello", 42);
            var encoded = codec.encode(original, ManagedUsagePayload.class, null);
            assertEquals(original, codec.decode(encoded, ManagedUsagePayload.class, null));
            assertEquals("examples.annotation.payload", encoded.typeName());
            var fields = (Map<?, ?>) encoded.value();
            assertEquals(java.util.Set.of("1", "2"), fields.keySet());
            var absentOptional = new EncodedValue(encoded.kind(), encoded.typeName(), Map.of("1", fields.get("1")), encoded.metadata());
            assertEquals(new ManagedUsagePayload("hello", 7), codec.decode(absentOptional, ManagedUsagePayload.class, null));
            var absentRequired = new EncodedValue(encoded.kind(), encoded.typeName(), Map.of(), encoded.metadata());
            assertEquals(MiddlewareErrorCode.CODEC_ERROR,
                    assertThrows(MiddlewareException.class, () -> codec.decode(absentRequired, ManagedUsagePayload.class, null)).code());
        }
    }

    @Test
    void exampleMetadataCannotInstallItsOwnHostBindingCapability() throws Exception {
        try (var loader = compileReference();
                var configuration = new LiveConfiguration(1, Map.of("reference.region", "eu", "reference.attempts", "3"))) {
            var failure = assertThrows(ManagedContainer.Failure.class,
                    () -> ManagedContainer.activate(indexes(loader), 32, configuration, ManagedHostBindings.empty()));
            assertEquals("UNSUPPORTED_DEPENDENCY_METADATA", failure.code());
        }
    }

    @Test
    void factoriesRemainOrdinaryJavaAndConfigurationRecordsKeepTheirDomainValidation() {
        assertEquals("formal:eu:hello Ada", ManagedAnnotationUsage.formatter().format("eu", "Ada"));
        var available = ManagedAnnotationUsage.consumer(() -> java.util.Optional.of(name -> "remote:" + name));
        assertEquals("remote:Ada", available.greetOrFallback("Ada"));
        assertThrows(NullPointerException.class, () -> ManagedAnnotationUsage.consumer(null));
        assertEquals(1, new ManagedUsageSettings("eu", 1).attempts());
        assertThrows(IllegalArgumentException.class, () -> new ManagedUsageSettings("eu", 0));
        assertThrows(IllegalArgumentException.class, () -> new ManagedUsageSettings("", 1));
    }

    private URLClassLoader compileReference() throws Exception {
        Path output = Files.createDirectory(directory.resolve("classes"));
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            Path sourceRoot = Path.of(System.getProperty("j7m.examples.reference.source")).getParent();
            var sources = manager.getJavaFileObjectsFromPaths(List.of("ManagedAnnotationUsage.java", "ManagedUsageStyle.java",
                    "ManagedUsageSettings.java", "ManagedUsagePayload.java").stream().map(sourceRoot::resolve).toList());
            var task = compiler.getTask(null, manager, diagnostics,
                    List.of("-classpath", System.getProperty("j7m.examples.test.classpath"), "-d", output.toString()), null, sources);
            task.setProcessors(List.of(new J7mProcessor()));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        return new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private static List<ManagedComponentIndex> indexes(final ClassLoader loader) {
        return ServiceLoader.load(ManagedComponentIndex.class, loader).stream().map(ServiceLoader.Provider::get).toList();
    }

    private static BindingPolicy optionalPolicy() {
        return new BindingPolicy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.OPTIONAL,
                Duration.ofSeconds(1), Duration.ofMillis(20), Duration.ofMillis(200), Duration.ofSeconds(1), 8);
    }
}
