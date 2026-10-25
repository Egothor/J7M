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
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.processor.J7mProcessor;

/**
 * Actual application compilation and generated direct-dispatch fixture, not a production demo.
 */
final class GeneratedDeduplicationService implements AutoCloseable {
    final URLClassLoader loader;
    final Class<?> service, business;
    final ManagedHostAdapter<Object> adapter;
    final ManagedContainer container;
    final Object instance;
    final ManagedServiceContract contract;
    @SuppressWarnings("unchecked")
    GeneratedDeduplicationService(Path classes, ResourceBindingFactory resources) throws Exception {
        loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
        service = loader.loadClass("dedup.Service"); business = loader.loadClass("dedup.Business");
        var indexes = ServiceLoader.load(ManagedComponentIndex.class, loader).stream().map(ServiceLoader.Provider::get).toList();
        adapter = (ManagedHostAdapter<Object>) indexes.stream().flatMap(index -> index.components().stream()).filter(definition -> definition.type() == service).findFirst().orElseThrow();
        container = ManagedContainer.activate(indexes, 4096, null, new ManagedHostBindings(Map.of("resource.name", resources)));
        instance = container.get(service, "", List.of());
        contract = new ManagedServiceContract("qualified-dedup", 1, adapter.remoteMethods().stream().filter(method -> method.declaringType().equals(business.getName())).findFirst().orElseThrow());
    }
    static Path compile(Path directory) throws Exception {
        Files.createDirectories(directory); Path classes = directory.resolve("classes"); Files.createDirectories(classes);
        Path business = directory.resolve("Business.java"), service = directory.resolve("Service.java");
        Files.writeString(business, """
                package dedup;
                import org.j7m.middleware.annotations.*;
                public interface Business {
                    @RemoteMethod @Deduplicated String apply(@IdempotencyKey String key, String input);
                }
                """);
        Files.writeString(service, """
                package dedup;
                import org.j7m.middleware.annotations.*;
                import org.j7m.middleware.application.api.*;
                import org.j7m.middleware.application.distributed.*;
                @J7mComponent @J7mService(name="qualified-dedup",contractId="qualified-dedup")
                public class Service implements Business {
                    private final ManagedProvider<ManagedDeduplicationContext> context;
                    @Inject public Service(@J7mResource("deduplication") ManagedProvider<ManagedDeduplicationContext> context) { this.context=context; }
                    public String apply(String key, String input) {
                        var current=context.get();
                        current.compareAndSet("protected-"+key,0,input);
                        if(input.equals("before-effect")) {
                            System.out.println("BODY_BEFORE_EFFECT"); System.out.flush();
                            try { new java.util.concurrent.CountDownLatch(1).await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                        }
                        try(var socket=new java.net.Socket("127.0.0.1",Integer.parseInt(System.getProperty("effect.port")))) {
                            var out=new java.io.DataOutputStream(socket.getOutputStream());out.writeUTF(key);out.writeUTF(current.invocationId());out.flush();
                            new java.io.DataInputStream(socket.getInputStream()).readBoolean();
                        } catch(java.io.IOException e) { throw new IllegalStateException(e); }
                        if(input.equals("after-effect")) {
                            System.out.println("BODY_AFTER_EFFECT"); System.out.flush();
                            try { new java.util.concurrent.CountDownLatch(1).await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                        }
                        return "result-"+input;
                    }
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler(); var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, files, diagnostics, List.of("-classpath", System.getProperty("j7m.application.distributed.test.classpath"), "-d", classes.toString()), null, files.getJavaFileObjects(business, service));
            task.setProcessors(List.of(new J7mProcessor())); assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        return classes;
    }
    @Override public void close() throws Exception { try { container.close(); } finally { loader.close(); } }
}
