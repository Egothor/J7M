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
import java.nio.file.Path;
import java.util.List;
import org.j7m.middleware.application.api.ManagedHostAdapter;
import org.j7m.middleware.application.api.ManagedComponentIndex;
import org.j7m.middleware.application.api.ManagedLifecycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real compiler checks for direct lifecycle and exact remote invocation adapters.
 */
class HostAdapterProcessorTest {
    @TempDir Path directory;

    @Test
    void directAdapterExportsOnlyExplicitOverloadAndRunsOwnedLifecycleWithoutDuplicateClose() throws Exception {
        Path output = compile(List.of(
            source("Api", "@RemoteService public interface Api {@RemoteMethod String echo(String value); String echo(int value);}"),
            source("Service", "@J7mService(name=\"service\") public class Service implements Api,AutoCloseable {public static String events=\"\"; public Service(){} public String echo(String value){return value;} public String echo(int value){return Integer.toString(value);} @Initialize public void init(){events+=\"I\";} @Startup public void start(){events+=\"S\";} @Drain public void drain(){events+=\"D\";} @Shutdown public void close(){events+=\"C\";} }")), true);
        try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            var index = (ManagedComponentIndex) loader.loadClass("fixture.Service__J7mComponentIndex").getConstructor().newInstance();
            @SuppressWarnings("unchecked")
            ManagedHostAdapter<Object> adapter = (ManagedHostAdapter<Object>) index.components().getFirst();
            Object target = adapter.create(null);
            for (ManagedLifecycle phase : ManagedLifecycle.values()) { adapter.lifecycle(target, phase); }
            assertEquals("ISD", target.getClass().getField("events").get(null));
            ((AutoCloseable) target).close();
            assertEquals("ISDC", target.getClass().getField("events").get(null));
            var method = adapter.remoteMethods().stream().filter(m -> m.declaringType().equals("fixture.Api")).findFirst().orElseThrow();
            assertEquals(List.of("java.lang.String"), method.parameterTypes());
            assertEquals("answer", adapter.invoke(target, method, new Object[]{"answer"}));
            var denied = new org.j7m.middleware.application.api.ManagedMethod("fixture.Api", "echo", List.of("int"), "java.lang.String");
            assertThrows(IllegalArgumentException.class, () -> adapter.invoke(target, denied, new Object[]{1}));
        }
    }

    @Test
    void remoteIntentAloneDoesNotExportMethodsAndConflictingEffectiveDeclarationsFail() throws Exception {
        Path output = compile(List.of(source("Intent", "@J7mComponent @RemoteService public class Intent {public Intent(){} public void local(){}}")), true);
        try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            var index = (ManagedComponentIndex) loader.loadClass("fixture.Intent__J7mComponentIndex").getConstructor().newInstance();
            assertTrue(((ManagedHostAdapter<?>) index.components().getFirst()).remoteMethods().isEmpty());
        }
        compile(List.of(source("Api", "public interface Api {@RemoteMethod void work();}"),
            source("Conflict", "@J7mComponent public class Conflict implements Api {public Conflict(){} @LocalOnly public void work(){}}")), false);
        compile(List.of(source("Invalid", "@J7mComponent public class Invalid {public Invalid(){} @Initialize public void init(String invalid){}}")), false);
        compile(List.of(source("Invalid", "@J7mService(name=\"service\",contractVersion=0) public class Invalid {public Invalid(){}}")), false);
    }

    @Test
    void noRemoteExportAcceptsByValueOperationsAndRejectsAutomaticCallbackReferences() throws Exception {
        compile(List.of(source("Safe", "@J7mComponent public class Safe {public Safe(){} @RemoteMethod @NoRemoteExport public String echo(@NoRemoteExport String value){return value;} @RemoteMethod public org.j7m.middleware.api.ObjectRef handle(@NoRemoteExport org.j7m.middleware.api.ObjectRef ref){return ref;} }")), true);
        compile(List.of(source("LocalValue", "@NoRemoteExport @J7mComponent public class LocalValue {public LocalValue(){} @RemoteMethod public String value(){return \"local\";}}")), true);
        compile(List.of(source("Api", "public interface Api {void call();}"),
            source("Unsafe", "@J7mComponent public class Unsafe {public Unsafe(){} @RemoteMethod public void echo(@NoRemoteExport Api callback){}}")), false);
    }

    @Test
    void currentPrincipalCannotBeCapturedInAnActivationFieldButProviderAndCallScopeAreExplicit() throws Exception {
        compile(List.of(source("Unsafe", "@J7mComponent public class Unsafe {public Unsafe(){} @CurrentPrincipal public org.j7m.middleware.security.api.Principal principal;}")), false);
        compile(List.of(source("Safe", "@J7mComponent public class Safe {public Safe(@CurrentPrincipal org.j7m.middleware.application.api.ManagedProvider<org.j7m.middleware.security.api.Principal> principal){} }")), true);
        compile(List.of(source("Call", "@Scope(Scope.Kind.CALL) @J7mComponent public class Call {public Call(@CurrentInvocation org.j7m.middleware.application.api.InvocationContext context){} }")), true);
    }

    @Test
    void unrelatedLocalInterfaceDefaultsDoNotBleedIntoRemoteBusinessMethods() throws Exception {
        compile(List.of(source("Local", "@LocalOnly public interface Local {void local();}"),
                source("Remote", "public interface Remote {@RemoteMethod String remote();}"),
                source("Mixed", "@J7mComponent public class Mixed implements Local,Remote {public Mixed(){} public void local(){} public String remote(){return \"remote\";}}")), true);
    }

    @Test
    void hostProbesAreFiniteDirectBooleanCallsAndNeverImplicitRemoteMethods() throws Exception {
        Path output = compile(List.of(source("Probed", "@J7mComponent public class Probed {public Probed(){} @HealthCheck public boolean healthy(){return true;} @ReadinessCheck public boolean ready(){return false;} }")), true);
        try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            var index = (ManagedComponentIndex) loader.loadClass("fixture.Probed__J7mComponentIndex").getConstructor().newInstance();
            @SuppressWarnings("unchecked") ManagedHostAdapter<Object> adapter = (ManagedHostAdapter<Object>) index.components().getFirst();
            Object value = adapter.create(null);
            assertTrue(adapter.remoteMethods().isEmpty());
            assertEquals(2, adapter.probes().size());
            for (var probe : adapter.probes()) assertEquals(probe.kind() == org.j7m.middleware.application.api.ManagedProbeKind.HEALTH, adapter.probe(value, probe));
            var unknown = new org.j7m.middleware.application.api.ManagedProbe(org.j7m.middleware.application.api.ManagedProbeKind.HEALTH,
                    new org.j7m.middleware.application.api.ManagedMethod("fixture.Probed", "unknown", List.of(), "boolean"));
            assertThrows(IllegalArgumentException.class, () -> adapter.probe(value, unknown));
        }
        compile(List.of(source("Bad", "@J7mComponent public class Bad {public Bad(){} @HealthCheck public String health(){return \"healthy\";}}")), false);
        compile(List.of(source("Bad", "@Scope(Scope.Kind.CALL) @J7mComponent public class Bad {public Bad(){} @ReadinessCheck public boolean ready(){return true;}}")), false);
        compile(List.of(source("Bad", "@J7mComponent public class Bad {public Bad(){} @ReadinessCheck private boolean ready(){return true;}}")), false);
    }

    private Path compile(List<String> sources, boolean success) throws Exception {
        var compiler = new SchemaProcessorTest(); compiler.temporary = directory;
        return compiler.compile(sources, success);
    }
    private static String source(String name, String body) { return SchemaProcessorTest.source(name, body); }
}
