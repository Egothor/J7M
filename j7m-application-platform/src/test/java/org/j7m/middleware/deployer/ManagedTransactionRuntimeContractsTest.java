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

package org.j7m.middleware.deployer;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.ManagedHostingOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Handwritten finite indexes bypass javac processing and exercise actual activation preflight.
 */
class ManagedTransactionRuntimeContractsTest {
    @TempDir Path directory;
    static final AtomicInteger constructors = new AtomicInteger(), loadedIndexes = new AtomicInteger();
    public interface JdbcContract { @RemoteMethod @JdbcTransaction String value(); }
    public interface ResourceContract { @RemoteMethod @ResourceTransaction String value(); }
    @J7mComponent @org.j7m.middleware.annotations.J7mApplication(group="test",name="tx-preflight",version="1")
    @J7mService(name="//test/tx",contractId="tx")
    public static class JdbcWorker implements JdbcContract {
        public JdbcWorker() { constructors.incrementAndGet(); }
        public String value() { throw new AssertionError("No transaction adapter installed"); }
    }
    @J7mComponent @org.j7m.middleware.annotations.J7mApplication(group="test",name="tx-preflight",version="1")
    @J7mService(name="//test/tx",contractId="tx")
    public static class ResourceWorker implements ResourceContract {
        public ResourceWorker() { constructors.incrementAndGet(); }
        public String value() { throw new AssertionError("No transaction adapter installed"); }
    }
    public static class Jdbc__J7mComponentIndex extends ManualIndex<JdbcWorker> {
        public Jdbc__J7mComponentIndex() { super(JdbcWorker.class, JdbcContract.class, JdbcWorker::new); }
    }
    public static class Resource__J7mComponentIndex extends ManualIndex<ResourceWorker> {
        public Resource__J7mComponentIndex() { super(ResourceWorker.class, ResourceContract.class, ResourceWorker::new); }
    }
    public abstract static class ManualIndex<T> implements ManagedComponentIndex {
        private final Class<T> type; private final Class<?> contract; private final Supplier<T> construct;
        ManualIndex(Class<T> type, Class<?> contract, Supplier<T> construct) {
            this.type = type; this.contract = contract; this.construct = construct; loadedIndexes.incrementAndGet();
        }
        private ManagedMethod method() { return new ManagedMethod(contract.getName(), "value", List.of(), String.class.getName()); }
        public List<ManagedMethod> methods() { return List.of(method()); }
        public List<ManagedComponentDefinition<?>> components() {
            return List.of(new ManagedHostAdapter<T>() {
                public Class<T> type() { return type; }
                public String name() { return ""; }
                public List<ManagedQualifier> qualifiers() { return List.of(); }
                public Scope.Kind scope() { return Scope.Kind.ACTIVATION; }
                public List<ManagedDependency> dependencies() { return List.of(); }
                public T create(ManagedResolver resolver) { return construct.get(); }
                public List<ManagedMethod> remoteMethods() { return List.of(method()); }
            });
        }
    }
    @Test void jdbcAndResourceMarkersWithoutActualHostCapabilitiesRejectBeforeAnyConstructor() throws Exception {
        constructors.set(0); loadedIndexes.set(0);
        for (Class<?> worker : List.of(JdbcWorker.class, ResourceWorker.class)) {
            Class<?> contract = worker == JdbcWorker.class ? JdbcContract.class : ResourceContract.class;
            Class<?> index = worker == JdbcWorker.class ? Jdbc__J7mComponentIndex.class : Resource__J7mComponentIndex.class;
            Path root = directory.resolve(worker.getSimpleName()); Path service = root.resolve("META-INF/services/" + ManagedComponentIndex.class.getName());
            Files.createDirectories(service.getParent()); Files.writeString(service, index.getName());
            var identity = new ApplicationIdentity(ApplicationCoordinate.parse("test:tx-preflight:1"), "Tx preflight", "test");
            var manifest = new ApplicationManifest(org.j7m.middleware.application.repository.ApplicationManifestValidator.SCHEMA_VERSION, identity,
                    "0.1", "25", "CLASSPATH", worker.getName(), List.of(new ApplicationManifest.ServiceDeclaration("//test/tx", contract.getName(), worker.getName(), "grpc", false, Map.of())), null, null);
            ApplicationContext context = (ApplicationContext) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ApplicationContext.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "configuration" -> ConfigurationView.of(Map.of("j7m.annotations.profile", "managed-v1"));
                        case "applicationIdentity" -> identity;
                        case "activationPrincipal" -> org.j7m.middleware.security.api.Principal.runtime("test");
                        case "runtimeIdentity" -> new RuntimeIdentity("test", "test", Map.of());
                        default -> throw new AssertionError("Unexpected pre-constructor context access");
                    });
            try (var loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, getClass().getClassLoader()) {
                @Override public Enumeration<URL> getResources(String name) throws java.io.IOException {
                    return name.equals("META-INF/services/" + ManagedComponentIndex.class.getName()) ? findResources(name) : super.getResources(name);
                }
            }) {
                var denied = assertThrows(org.j7m.middleware.api.MiddlewareException.class, () -> new ManagedPojoActivation(manifest, context, loader,
                        null, type -> { }, ManagedHostingOptions.defaults(), "0".repeat(64), boundary -> { throw new AssertionError("No callback before preflight"); }));
                assertEquals("managed activation preparation failed", denied.getMessage());
                assertThrows(IllegalArgumentException.class, () -> ManagedRuntimeContracts.validateType(worker, Set.of()));
                assertEquals(0, constructors.get());
            }
        }
        assertEquals(2, loadedIndexes.get(), "Both actual handwritten indexes must have reached runtime validation");
    }
}
