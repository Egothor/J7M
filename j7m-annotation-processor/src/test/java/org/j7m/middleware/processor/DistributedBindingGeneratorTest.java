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
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DistributedBindingGeneratorTest {
    @TempDir Path directory;
    private List<Diagnostic<? extends JavaFileObject>> compile(String constructor, String fields, boolean expected) throws Exception {
        Path source = directory.resolve("Consumer.java"), classes = directory.resolve("classes"); Files.createDirectories(classes);
        Files.writeString(source, "package fixture; import org.j7m.middleware.annotations.*; import org.j7m.middleware.application.api.*; @J7mComponent public class Consumer { " + constructor + fields + " }");
        var compiler = ToolProvider.getSystemJavaCompiler(); var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, manager, diagnostics, List.of("-classpath", System.getProperty("j7m.processor.test.classpath"), "-d", classes.toString()), null, manager.getJavaFileObjects(source));
            task.setProcessors(List.of(new J7mProcessor())); assertEquals(expected, task.call(), diagnostics.getDiagnostics().toString());
        }
        return diagnostics.getDiagnostics();
    }
    @Test void serviceResourceAndInheritedConstructorRefreshEmitActualFiniteMetadata() throws Exception {
        compile("@Inject(refresh=BindingRefresh.ON_FAILURE) public Consumer(@Named(\"workers\") Runnable service) {}",
                "@ServiceRef(name=\"other\",contractId=\"jobs\",contractVersion=2,refresh=BindingRefresh.ON_TOPOLOGY_CHANGE,policy=\"fast\") public Runnable field;"
                + "@J7mResource(value=\"queue\") public ManagedProvider<Runnable> resource;", true);
        String generated = Files.readString(directory.resolve("classes/fixture/Consumer__J7mComponentIndex.java"));
        assertTrue(generated.contains("service.name")); assertTrue(generated.contains("java.lang.Runnable")); assertTrue(generated.contains("service.contract.version"));
        assertTrue(generated.contains("resource.name")); assertTrue(generated.contains("ON_TOPOLOGY_CHANGE")); assertTrue(generated.contains("ON_FAILURE")); assertTrue(generated.contains("resolver.provider"));
    }
    @Test void defaultInjectRemainsLocalAndDoesNotAcquireRemoteBehavior() throws Exception {
        compile("@Inject public Consumer(@Named(\"local\") Runnable dependency) {}", "", true);
        String generated = Files.readString(directory.resolve("classes/fixture/Consumer__J7mComponentIndex.java")); assertFalse(generated.contains("service.name")); assertFalse(generated.contains("binding.refresh"));
    }
    @Test void incompatibleBindingCombinationsFailWithStableCodesWithoutEchoingPolicyValues() throws Exception {
        var diagnostics = compile("public Consumer(@ServiceRef(name=\"workers\",policy=\"bad policy value\") Runnable value) {}", "", false);
        assertTrue(diagnostics.stream().anyMatch(d -> d.getMessage(Locale.ROOT).contains("J7M_INVALID_BINDING_POLICY")));
        assertFalse(diagnostics.stream().anyMatch(d -> d.getMessage(Locale.ROOT).contains("bad policy value")));
    }
    @Test void nonInterfaceDynamicInjectIsRejectedInsteadOfSilentlyRemainingLocal() throws Exception {
        var diagnostics = compile("public Consumer(@Inject(refresh=BindingRefresh.ON_FAILURE) @Named(\"workers\") String value) {}", "", false);
        assertTrue(diagnostics.stream().anyMatch(d -> d.getMessage(Locale.ROOT).contains("J7M_SERVICE_BINDING_REQUIRES_INTERFACE")));
    }
    @Test void mixingConfigurationAndServiceReferenceIsRejectedBeforeGeneration() throws Exception {
        var diagnostics = compile("public Consumer(@ServiceRef(name=\"workers\") @ConfigValue(\"key\") Runnable value) {}", "", false);
        assertTrue(diagnostics.stream().anyMatch(d -> d.getMessage(Locale.ROOT).contains("J7M_CONFLICTING_CONFIG_BINDING")));
    }
}
