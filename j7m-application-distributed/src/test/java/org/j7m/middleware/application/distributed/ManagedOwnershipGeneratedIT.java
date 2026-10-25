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
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.tools.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.processor.J7mProcessor;
import org.j7m.middleware.transport.api.InvocationCompletionScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual generated index, activation preflight, injected provider and direct policy dispatch.
 */
class ManagedOwnershipGeneratedIT {
    @TempDir Path directory;
    private static Object call(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { if (failure.getCause() instanceof Exception exception) throw exception; throw failure; }
    }
    private URLClassLoader compile() throws Exception {
        var source = directory.resolve("App.java"); var classes = directory.resolve("classes"); Files.createDirectories(classes);
        Files.writeString(source, """
                package owned;
                import org.j7m.middleware.annotations.*;
                import org.j7m.middleware.application.api.*;
                import org.j7m.middleware.application.distributed.*;
                @org.j7m.middleware.annotations.J7mApplication(group="test",name="ownership",version="1")
                @J7mService(name="owned",contractId="owned")
                public class App implements AppApi {
                    public static int constructors;
                    private final ManagedProvider<ManagedOwnershipContext> ownership;
                    @Inject public App(@J7mResource("ownership") ManagedProvider<ManagedOwnershipContext> ownership) { constructors++; this.ownership=ownership; }
                    public String guarded(String key) { return effect("guarded",key); }
                    public String leader(String key) { return effect("leader",key); }
                    private String effect(String id,String key) {
                        var current=ownership.get();
                        current.write(id,"key",key);
                        return current.read("key").value()+":"+J7mContext.currentInvocation().security().principal().id();
                    }
                }
                """);
        var api = directory.resolve("AppApi.java"); Files.writeString(api, """
                package owned;
                import org.j7m.middleware.annotations.*;
                public interface AppApi {
                    @RemoteMethod @LeaseGuard String guarded(@ShardKey String key);
                    @RemoteMethod @LeaderTask String leader(@ShardKey String key);
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler(); var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, files, diagnostics, List.of("-classpath",System.getProperty("j7m.application.distributed.test.classpath"),"-d",classes.toString()), null, files.getJavaFileObjects(source, api));
            task.setProcessors(List.of(new J7mProcessor())); assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        return new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
    }
    private Object activate(ClassLoader loader, ManagedHostingOptions options) throws Exception {
        var identity = new ApplicationIdentity(ApplicationCoordinate.parse("test:ownership:1"),"Ownership","test");
        var manifest = new ApplicationManifest(org.j7m.middleware.application.repository.ApplicationManifestValidator.SCHEMA_VERSION,identity,"0.1","25","CLASSPATH","owned.App",
                List.of(new ApplicationManifest.ServiceDeclaration("owned","owned.AppApi","owned.App","grpc",false,Map.of())),null,null);
        var application = (ApplicationContext) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ApplicationContext.class},(proxy,method,args) -> switch(method.getName()) {
            case "configuration" -> ConfigurationView.of(Map.of()); case "applicationIdentity" -> identity;
            case "activationPrincipal" -> org.j7m.middleware.security.api.Principal.runtime("test");
            case "runtimeIdentity" -> new RuntimeIdentity("test","test",Map.of());
            default -> throw new AssertionError("Unexpected context access "+method.getName());
        });
        var type = Class.forName("org.j7m.middleware.deployer.ManagedPojoActivation"); var constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        try { return constructor.newInstance(manifest,application,loader,null,(java.util.function.Consumer<Class<?>>) ignored -> {},options,"0".repeat(64),
                (org.j7m.middleware.transport.api.InvocationCompletionFactory) boundary -> { throw new AssertionError("No hosted callback"); }); }
        catch (InvocationTargetException failure) { if(failure.getCause() instanceof Exception exception) throw exception; throw failure; }
    }
    @Test void generatedLeaseAndLeaderMethodsUseInjectedCurrentContextAndRejectMissingCapabilitiesBeforeConstruction() throws Exception {
        try (var loader = compile()) {
            var endpoint = new OwnershipEndpoint(); var profile = ManagedOwnershipProfileTest.profile(endpoint,Duration.ofMillis(100));
            var resource = new ResourceBindingFactory(Map.of("ownership",ResourceBindingFactory.ownershipContext()),Set.of("default"));
            var bindings = new ManagedHostBindings(Map.of("resource.name",resource));
            try (var unknown = new ManagedOwnershipPolicy(Map.of("other",profile),Set.of("alice"), context -> "alice")) {
                assertThrows(MiddlewareException.class, () -> activate(loader,new ManagedHostingOptions(bindings,List.of(unknown))));
            }
            assertEquals(0,loader.loadClass("owned.App").getField("constructors").getInt(null));
            assertThrows(MiddlewareException.class, () -> activate(loader,new ManagedHostingOptions(bindings,List.of())));
            assertEquals(0,loader.loadClass("owned.App").getField("constructors").getInt(null));
            try (var policy = ManagedOwnershipContextTest.policy(endpoint)) {
                var activation = activate(loader,new ManagedHostingOptions(bindings,List.of(policy)));
                try {
                    assertEquals(1,loader.loadClass("owned.App").getField("constructors").getInt(null));
                    var api = loader.loadClass("owned.AppApi");
                    call(activation,"attachPrepared",new Class<?>[]{String.class,ObjectRef.class},"owned",new ObjectRef("owned","owned","owned","test","test",List.of(api.getName()),"grpc","",0,1,Map.of()));
                    for (String method : List.of("guarded","leader")) {
                        var call = (ManagedContainer.CallScope) call(activation,"openInvocationCall",new Class<?>[]{});
                        try (var executor = Executors.newSingleThreadExecutor()) {
                            var lifetime = new ManagedInvocationLifetime(call,TransactionInvocation.caller(),null,Duration.ofSeconds(5),executor,16,16,() -> {});
                            var scope = (InvocationCompletionScope) call(activation,"ownedScope",new Class<?>[]{InvocationCompletionScope.class},lifetime);
                            try { scope.call(() -> call(activation,"invoke",new Class<?>[]{String.class,Method.class,Object[].class},"owned",api.getMethod(method,String.class),new Object[]{"input"}));
                                assertEquals("input:alice",scope.logicalResult().toCompletableFuture().get(5,TimeUnit.SECONDS)); }
                            finally { scope.close(); scope.physicalCompletion().toCompletableFuture().get(5,TimeUnit.SECONDS); }
                        }
                    }
                    assertEquals(2,endpoint.effects.get()); assertEquals(0,policy.status().activeClaims());
                } finally { ((AutoCloseable)activation).close(); }
            }
        }
    }
}
