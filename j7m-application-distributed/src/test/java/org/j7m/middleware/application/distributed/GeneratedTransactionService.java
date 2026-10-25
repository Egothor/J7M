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
 * Compiles ordinary annotated application code and invokes the actual generated direct adapter.
 */
final class GeneratedTransactionService implements AutoCloseable {
    final URLClassLoader loader; final Class<?> service, business;
    final List<ManagedComponentIndex> indexes;
    final ManagedHostAdapter<Object> adapter; final ManagedServiceContract contract;
    ManagedContainer container; Object instance;
    @SuppressWarnings("unchecked")
    GeneratedTransactionService(Path classes) throws Exception {
        loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
        service = loader.loadClass("transaction.Service"); business = loader.loadClass("transaction.Business");
        indexes = ServiceLoader.load(ManagedComponentIndex.class, loader).stream().map(ServiceLoader.Provider::get).toList();
        adapter = (ManagedHostAdapter<Object>) indexes.stream().flatMap(index -> index.components().stream()).filter(definition -> definition.type() == service).findFirst().orElseThrow();
        contract = new ManagedServiceContract("qualified-transactions", 1, adapter.remoteMethods().stream().filter(method -> method.declaringType().equals(business.getName())).findFirst().orElseThrow());
    }
    void activate(ManagedInvocationPolicy policy, ResourceBindingFactory resources) {
        policy.validate(contract, service, business);
        policy.validateExposure(service, Map.of(contract, business));
        container = ManagedContainer.activate(indexes, 4096, null, new ManagedHostBindings(Map.of("resource.name", resources)));
        instance = container.get(service, "", List.of());
    }
    Object invoke(ManagedInvocationPolicy.Binding binding, String key, String value) throws Exception {
        return TransactionInvocation.invoke(container, binding, frame -> adapter.invoke(instance, contract.method(), new Object[]{key, value}), new Object[]{key, value});
    }
    static Path compile(Path directory, boolean jdbc) throws Exception {
        Files.createDirectories(directory); Path classes = directory.resolve("classes"); Files.createDirectories(classes);
        Path business = directory.resolve("Business.java"), service = directory.resolve("Service.java");
        Files.writeString(business, """
                package transaction;
                import org.j7m.middleware.annotations.*;
                public interface Business {
                    @RemoteMethod @%s String apply(String key,String value) throws Exception;
                }
                """.formatted(jdbc ? "JdbcTransaction" : "ResourceTransaction"));
        String body = jdbc ? """
                try(var statement=resource.get().prepareStatement("INSERT INTO business_value(id,amount) VALUES(?,?)")) {
                    statement.setString(1,key);statement.setInt(2,Integer.parseInt(value));statement.executeUpdate();
                }
                """ : """
                var transaction=resource.get();
                transaction.lockRange("left","orders/");
                transaction.write("left",key,value+"-left");transaction.write("right",key,value+"-right");
                """;
        Files.writeString(service, """
                package transaction;
                import org.j7m.middleware.annotations.*;
                import org.j7m.middleware.application.api.*;
                import org.j7m.middleware.application.distributed.*;
                @J7mComponent @J7mService(name="qualified-transactions",contractId="qualified-transactions")
                public class Service implements Business {
                    public static int constructed;
                    private final ManagedProvider<%s> resource;
                    @Inject public Service(@J7mResource("%s") ManagedProvider<%s> resource) { this.resource=resource;constructed++; }
                    public String apply(String key,String value) throws Exception {
                        %s
                        return "result-"+value;
                    }
                }
                """.formatted(jdbc ? "java.sql.Connection" : "ManagedResourceTransactionContext", jdbc ? "jdbc" : "transaction", jdbc ? "java.sql.Connection" : "ManagedResourceTransactionContext", body));
        var compiler = ToolProvider.getSystemJavaCompiler(); var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, files, diagnostics, List.of("-classpath", System.getProperty("j7m.application.distributed.test.classpath"), "-d", classes.toString()), null, files.getJavaFileObjects(business, service));
            task.setProcessors(List.of(new J7mProcessor())); assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        return classes;
    }
    @Override public void close() throws Exception { try { if (container != null) container.close(); } finally { loader.close(); } }
}
