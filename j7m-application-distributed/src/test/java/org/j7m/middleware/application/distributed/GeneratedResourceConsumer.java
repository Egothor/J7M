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

package org.j7m.middleware.application.distributed;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.j7m.middleware.annotations.BindingRefresh;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.processor.J7mProcessor;

/**
 * Compiles the application's real annotated client; no manually manufactured dependency metadata.
 */
final class GeneratedResourceConsumer implements AutoCloseable {
    final URLClassLoader loader; final Class<?> consumer; final List<ManagedComponentIndex> indexes;
    ManagedContainer container;
    GeneratedResourceConsumer(Path directory, Class<?> clientType, BindingRefresh mode) throws Exception { this(directory, clientType, mode, "typed"); }
    GeneratedResourceConsumer(Path directory, Class<?> clientType, BindingRefresh mode, String resource) throws Exception {
        Files.createDirectories(directory);
        Path source = directory.resolve("Consumer.java"), classes = directory.resolve("consumer-classes"); Files.createDirectories(classes);
        Files.writeString(source, """
                package injected;
                import org.j7m.middleware.annotations.*;
                import org.j7m.middleware.application.api.*;
                @J7mComponent
                public class Consumer {
                    public static int created;
                    public final ManagedProvider<%s> client;
                    @Inject public Consumer(@J7mResource(value="%s",refresh=BindingRefresh.%s) ManagedProvider<%s> client) {
                        this.client=client;created++;
                    }
                }
                """.formatted(clientType.getName(), resource, mode.name(), clientType.getName()));
        var compiler = ToolProvider.getSystemJavaCompiler(); var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, manager, diagnostics, List.of("-classpath", System.getProperty("j7m.application.distributed.test.classpath"), "-d", classes.toString()), null, manager.getJavaFileObjects(source));
            task.setProcessors(List.of(new J7mProcessor())); assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader()); consumer = loader.loadClass("injected.Consumer");
        indexes = ServiceLoader.load(ManagedComponentIndex.class, loader).stream().map(ServiceLoader.Provider::get).toList(); assertEquals(1, indexes.size());
    }
    void activate(ResourceBindingFactory factory) {
        container = ManagedContainer.activate(indexes, 4096, null, new ManagedHostBindings(Map.of("resource.name", factory)));
    }
    Object value() throws Exception {
        Object instance = container.get(consumer, "", List.of()); return ((ManagedProvider<?>) consumer.getField("client").get(instance)).get();
    }
    @Override public void close() throws Exception { try { if (container != null) container.close(); } finally { loader.close(); } }
}
