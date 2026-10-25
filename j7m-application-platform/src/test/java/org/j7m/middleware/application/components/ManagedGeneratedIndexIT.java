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

package org.j7m.middleware.application.components;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.j7m.middleware.application.api.ManagedComponentIndex;
import org.j7m.middleware.processor.J7mProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedGeneratedIndexIT {
    @TempDir Path directory;
    private URLClassLoader compile(String repositoryName) throws Exception {
        Path sources = directory.resolve("src"); Path output = directory.resolve("classes"); Files.createDirectories(sources); Files.createDirectories(output);
        Files.writeString(sources.resolve("Repository.java"), """
                package fixture;
                import java.util.*;
                import org.j7m.middleware.annotations.*;
                @J7mComponent(name="repository")
                public class Repository implements AutoCloseable {
                    public static final List<String> EVENTS = new ArrayList<>();
                    public Repository(){EVENTS.add("repository-created");}
                    public void close(){EVENTS.add("repository-closed");}
                }
                """);
        Files.writeString(sources.resolve("Product.java"), """
                package fixture;
                public class Product implements AutoCloseable {
                    public final Repository repository;
                    public Product(Repository value){repository=value;}
                    public void close(){Repository.EVENTS.add("product-closed");}
                }
                """);
        Files.writeString(sources.resolve("Service.java"), """
                package fixture;
                import org.j7m.middleware.annotations.*;
                @J7mComponent(name="service")
                public class Service implements AutoCloseable {
                    public final Repository constructorValue;
                    @Inject @Named("repository") public Repository fieldValue;
                    @Inject public Service(@Named("%s") Repository value){constructorValue=value;Repository.EVENTS.add("service-created");}
                    @Produces(name="product") @Scope(Scope.Kind.CALL)
                    public Product product(@Named("repository") Repository value){return new Product(value);}
                    public void close(){Repository.EVENTS.add("service-closed");}
                }
                """.formatted(repositoryName));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler(); var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            List<Path> paths; try (var files = Files.list(sources)) { paths = files.toList(); }
            var units = manager.getJavaFileObjectsFromPaths(paths);
            var task = compiler.getTask(null, manager, diagnostics, List.of("-classpath", System.getProperty("j7m.artifact.test.classpath"), "-d", output.toString()), null, units);
            task.setProcessors(List.of(new J7mProcessor())); assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        return new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }
    private static List<ManagedComponentIndex> indexes(ClassLoader loader) {
        return ServiceLoader.load(ManagedComponentIndex.class, loader).stream().map(ServiceLoader.Provider::get).toList();
    }
    @Test void generatedPublicHelpersActuallyConstructInjectProduceAndDisposeWithoutReflectionOpens() throws Exception {
        try (var loader = compile("repository")) {
            var indexes = indexes(loader); assertEquals(2, indexes.size());
            Class<?> serviceType = loader.loadClass("fixture.Service"); Class<?> productType = loader.loadClass("fixture.Product");
            List<?> events = (List<?>) loader.loadClass("fixture.Repository").getField("EVENTS").get(null);
            assertEquals(List.of(), events);
            try (var container = ManagedContainer.activate(indexes)) {
                Object service = container.get(serviceType, "service", List.of());
                assertSame(serviceType.getField("constructorValue").get(service), serviceType.getField("fieldValue").get(service));
                try (var scope = container.openCall()) {
                    Object product = scope.get(productType, "product", List.of());
                    assertSame(product, scope.get(productType, "product", List.of()));
                    assertSame(serviceType.getField("constructorValue").get(service), productType.getField("repository").get(product));
                }
                assertEquals(List.of("repository-created", "service-created", "product-closed"), events);
            }
            assertEquals(List.of("repository-created", "service-created", "product-closed", "service-closed", "repository-closed"), events);
        }
    }
    @Test void generatedMissingNamedDependencyFailsBeforeAnyApplicationConstruction() throws Exception {
        try (var loader = compile("missing")) {
            ManagedContainerTest.code("MISSING_DEPENDENCY", () -> ManagedContainer.activate(indexes(loader)));
            assertEquals(List.of(), loader.loadClass("fixture.Repository").getField("EVENTS").get(null));
        }
    }
}
