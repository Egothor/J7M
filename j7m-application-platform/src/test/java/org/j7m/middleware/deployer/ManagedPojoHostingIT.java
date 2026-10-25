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

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ProxyOptions;
import org.j7m.middleware.application.repository.DefaultBundleSigner;
import org.j7m.middleware.application.repository.J7mBundleWriter;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.grpc.GrpcSecurityOptions;
import org.j7m.middleware.transport.grpc.GrpcTlsConfiguration;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Signed ordinary POJOs are activated in a separate real hosting JVM and called through mTLS.
 */
class ManagedPojoHostingIT {
    @TempDir Path directory;

    @Test void signedGeneratedGraphFailuresPreserveStructuredDiagnosticsAcrossActualActivation() throws Exception {
        var cases = Map.of("di-missing", "MISSING_DEPENDENCY", "di-ambiguous", "AMBIGUOUS_DEPENDENCY", "di-cycle", "DEPENDENCY_CYCLE", "di-scope", "SCOPE_CAPTURE", "jpms-noexport", "JPMS_ACCESS", "di-construction", "CONSTRUCTION_FAILED");
        for (var entry : cases.entrySet()) {
            try (var fixture = fixture(entry.getKey(), false, entry.getKey().equals("jpms-noexport"))) {
                waitFor(fixture.root.resolve("failed"), fixture.process);
                assertTrue(fixture.process.waitFor(5, TimeUnit.SECONDS));
                assertEquals(entry.getValue(), Files.readString(fixture.root.resolve("injection-code")));
                assertEquals(entry.getValue(), Files.readString(fixture.root.resolve("injection-status")));
                String path = Files.readString(fixture.root.resolve("injection-path"));
                assertTrue(path.contains(entry.getKey().equals("jpms-noexport") ? "fixture.api.Api" : "fixture.hidden.Service"), path);
                assertTrue(path.length() <= 4096);
                assertFalse(Files.exists(fixture.root.resolve("events")));
                if (Files.exists(fixture.root.resolve("order"))) assertEquals(entry.getKey().equals("di-construction"), Files.readAllLines(fixture.root.resolve("order")).contains("CTOR"));
                assertFalse(Files.readString(fixture.log).contains("password=private-constructor-detail"));
            }
        }
    }

    @Test
    void signedPojoRemoteOverloadsAuthenticatedContextsAndRealDrainPreserveOwnership() throws Exception {
        Fixture fixture = fixture("normal", false);
        try (fixture; var loader = new URLClassLoader(new java.net.URL[]{fixture.jar.toUri().toURL()}, getClass().getClassLoader());
                var alice = client("alice", fixture); var bob = client("bob", fixture)) {
            waitFor(fixture.root.resolve("ready"), fixture.process);
            ObjectRef reference = ManagedPojoProcess.readRef(fixture.root.resolve("reference"));
            var declared = loader.loadClass("fixture.hidden.Service").getAnnotation(org.j7m.middleware.annotations.J7mService.class);
            assertTrue(declared.exported());
            assertEquals(declared.contractId(), reference.metadata().get("j7m.contract.id"));
            assertEquals(Integer.toString(declared.contractVersion()), reference.metadata().get("j7m.contract.version"));
            List<String> order = Files.readAllLines(fixture.root.resolve("order"));
            assertEquals(5, order.stream().filter("V"::equals).count());
            assertEquals(5, order.stream().filter("B"::equals).count());
            assertTrue(order.subList(0, 5).stream().allMatch("V"::equals));
            assertTrue(order.subList(5, 10).stream().allMatch("B"::equals));
            assertEquals(10, order.indexOf("CTOR"), "all method policies must validate and bind before constructors");
            Class<?> api = loader.loadClass("fixture.api.Api");
            Object first = alice.reference(reference, api, new ProxyOptions(Duration.ofSeconds(5), Map.of()));
            Object second = bob.reference(reference, api, ProxyOptions.defaults());
            var echo = api.getMethod("echo", String.class);
            try (var untrusted = client("alice", fixture, false)) {
                Exception refused = assertThrows(Exception.class, () -> {
                    Object deniedProxy = untrusted.reference(reference, api, new ProxyOptions(Duration.ofSeconds(1), Map.of()));
                    echo.invoke(deniedProxy, "unauthenticated");
                });
                Throwable transport = refused instanceof InvocationTargetException invocation ? invocation.getCause() : refused;
                assertInstanceOf(MiddlewareException.class, transport);
                assertEquals(MiddlewareErrorCode.UNAUTHENTICATED, ((MiddlewareException) transport).code());
                assertFalse(Files.exists(fixture.root.resolve("policy-invoked")), "unauthenticated transport reached the handler pipeline");
            }
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var one = executor.submit(() -> { for (int index = 0; index < 20; index++) assertTrue(((String) echo.invoke(first, "work")).startsWith("alice:work:")); return true; });
                var two = executor.submit(() -> { for (int index = 0; index < 20; index++) assertTrue(((String) echo.invoke(second, "work")).startsWith("bob:work:")); return true; });
                assertTrue(one.get(10, TimeUnit.SECONDS)); assertTrue(two.get(10, TimeUnit.SECONDS));
                var denied = assertThrows(InvocationTargetException.class, () -> api.getMethod("echo", int.class).invoke(first, 1));
                assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, ((MiddlewareException) denied.getCause()).code());
                assertEquals("value", api.getMethod("value", String.class).invoke(first, "value"));
                assertEquals(reference, api.getMethod("handle", ObjectRef.class).invoke(first, reference));
                var failed = assertThrows(InvocationTargetException.class, () -> api.getMethod("fail").invoke(first));
                assertEquals(MiddlewareErrorCode.REMOTE_EXCEPTION, ((MiddlewareException) failed.getCause()).code());
                assertFalse(failed.getCause().getMessage().contains("synthetic-handler-detail"));
                assertTrue(((String) echo.invoke(second, "after-failure")).startsWith("bob:after-failure:"));
                assertTrue(command(fixture, "STATUS").endsWith("RUNNING:1"));
                var held = executor.submit(() -> api.getMethod("hold").invoke(first));
                waitFor(fixture.root.resolve("entered"), fixture.process);
                try {
                    long constructed = Files.readAllLines(fixture.root.resolve("order")).stream().filter("CTOR"::equals).count();
                    assertTrue(command(fixture, "UPGRADE").endsWith("false:RUNNING"), "upgrade must not replace a still-running handler");
                    assertEquals(constructed, Files.readAllLines(fixture.root.resolve("order")).stream().filter("CTOR"::equals).count());
                    assertTrue(command(fixture, "STOP").endsWith("false:DRAINING"));
                    assertEquals(List.of("I", "S", "D"), Files.readAllLines(fixture.root.resolve("events")));
                    var unavailable = assertThrows(InvocationTargetException.class, () -> echo.invoke(second, "after-drain"));
                    assertEquals(MiddlewareErrorCode.UNAVAILABLE, ((MiddlewareException) unavailable.getCause()).code());
                } finally { Files.writeString(fixture.root.resolve("release"), "release"); }
                assertEquals("alice", held.get(5, TimeUnit.SECONDS));
            }
            assertTrue(command(fixture, "STOP").endsWith("true:STOPPED"));
            assertTrue(command(fixture, "STOP").endsWith("true:STOPPED"));
            assertEquals(List.of("I", "S", "D", "C"), Files.readAllLines(fixture.root.resolve("events")));
            assertFalse(Files.exists(fixture.root.resolve("forbidden")));
            assertEquals(5, Files.readAllLines(fixture.root.resolve("order")).stream().filter("X"::equals).count());
        }
    }

    @Test
    void failedStartupAndFailedPartialExportDisposeExactlyOnceWithoutPublishingObjects() throws Exception {
        for (String mode : List.of("startup", "partial")) {
            try (Fixture fixture = fixture(mode, "partial".equals(mode))) {
                waitFor(fixture.root.resolve("failed"), fixture.process);
                assertTrue(fixture.process.waitFor(10, TimeUnit.SECONDS));
                assertEquals(0, fixture.process.exitValue(), Files.readString(fixture.log));
                assertEquals("FAILED:0", Files.readString(fixture.root.resolve("failed")));
                assertEquals(List.of("I", "S", "D", "C"), Files.readAllLines(fixture.root.resolve("events")));
            }
        }
    }

    @Test
    void unsignedUntrustedAndNonExportedDeploymentsNeverConstructApplicationCode() throws Exception {
        for (String mode : List.of("unsigned", "untrusted", "disabled", "noexport")) try (Fixture fixture = fixture(mode, false)) {
            waitFor(fixture.root.resolve("failed"), fixture.process);
            assertTrue(fixture.process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, fixture.process.exitValue(), Files.readString(fixture.log));
            assertEquals("FAILED:0", Files.readString(fixture.root.resolve("failed")));
            assertFalse(Files.exists(fixture.root.resolve("ready")));
            assertFalse(Files.exists(fixture.root.resolve("events")));
            assertFalse(Files.exists(fixture.root.resolve("order")));
        }
    }

    @Test
    void quiescentUpgradeAndRollbackRetainManagedProfileAndConfigurationWithDefaultRequests() throws Exception {
        try (Fixture fixture = fixture("rollback", false);
                var loader = new URLClassLoader(new java.net.URL[]{fixture.jar.toUri().toURL()}, getClass().getClassLoader());
                var runtime = client("alice", fixture)) {
            waitFor(fixture.root.resolve("ready"), fixture.process);
            Class<?> api = loader.loadClass("fixture.api.Api");
            ObjectRef original = ManagedPojoProcess.readRef(fixture.root.resolve("reference"));
            assertTrue(command(fixture, "UPGRADE").endsWith("true:RUNNING"));
            ObjectRef upgraded = ManagedPojoProcess.readRef(fixture.root.resolve("reference"));
            assertNotEquals(original.objectId(), upgraded.objectId());
            assertTrue(upgraded.generation() > original.generation());
            assertTrue(((String) api.getMethod("echo", String.class).invoke(runtime.reference(upgraded, api, ProxyOptions.defaults()), "upgraded")).startsWith("alice:upgraded:"));
            assertTrue(command(fixture, "ROLLBACK").endsWith("true:RUNNING"));
            ObjectRef restored = ManagedPojoProcess.readRef(fixture.root.resolve("reference"));
            assertNotEquals(upgraded.objectId(), restored.objectId());
            assertTrue(restored.generation() > upgraded.generation());
            assertTrue(((String) api.getMethod("echo", String.class).invoke(runtime.reference(restored, api, ProxyOptions.defaults()), "restored")).startsWith("alice:restored:"));
            assertTrue(command(fixture, "STOP").endsWith("true:STOPPED"));
            assertEquals(List.of("I", "S", "D", "C", "I", "S", "D", "C", "I", "S", "D", "C"), Files.readAllLines(fixture.root.resolve("events")));
        }
    }

    @Test
    void signedAnnotatedSecurityUsesActualTlsPrincipalTargetAndMandatoryAuditWithoutEffectReplay() throws Exception {
        try (Fixture fixture = fixture("security", false);
                var loader = new URLClassLoader(new java.net.URL[]{fixture.jar.toUri().toURL()}, getClass().getClassLoader());
                var alice = client("alice", fixture); var bob = client("bob", fixture)) {
            waitFor(fixture.root.resolve("ready"), fixture.process);
            ObjectRef reference = ManagedPojoProcess.readRef(fixture.root.resolve("reference"));
            Class<?> api = loader.loadClass("fixture.api.Api");
            Object allowed = alice.reference(reference, api, ProxyOptions.defaults());
            Object denied = bob.reference(reference, api, ProxyOptions.defaults());
            var echo = api.getMethod("echo", String.class);
            assertTrue(((String) echo.invoke(allowed, "private-input-sentinel")).startsWith("alice:private-input-sentinel:"));
            assertEquals(1, Files.readAllLines(fixture.root.resolve("effects")).size());
            var refused = assertThrows(InvocationTargetException.class, () -> echo.invoke(denied, "denied"));
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, ((MiddlewareException) refused.getCause()).code());
            assertEquals(1, Files.readAllLines(fixture.root.resolve("effects")).size());
            var roleDenied = assertThrows(InvocationTargetException.class, () -> api.getMethod("value", String.class).invoke(allowed, "no-admin-role"));
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, ((MiddlewareException) roleDenied.getCause()).code());
            var trustDenied = assertThrows(InvocationTargetException.class, () -> api.getMethod("handle", ObjectRef.class).invoke(allowed, reference));
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, ((MiddlewareException) trustDenied.getCause()).code());
            var handlerFailed = assertThrows(InvocationTargetException.class, () -> api.getMethod("fail").invoke(allowed));
            assertEquals(MiddlewareErrorCode.REMOTE_EXCEPTION, ((MiddlewareException) handlerFailed.getCause()).code());
            assertFalse(handlerFailed.getCause().toString().contains("synthetic-handler-detail"));
            Files.writeString(fixture.root.resolve("audit-fail"), "START");
            var before = assertThrows(InvocationTargetException.class, () -> echo.invoke(allowed, "before-audit"));
            assertEquals(MiddlewareErrorCode.UNAVAILABLE, ((MiddlewareException) before.getCause()).code());
            assertEquals(1, Files.readAllLines(fixture.root.resolve("effects")).size());
            Files.writeString(fixture.root.resolve("audit-fail"), "SUCCESS");
            var after = assertThrows(InvocationTargetException.class, () -> echo.invoke(allowed, "after-audit"));
            assertEquals(MiddlewareErrorCode.UNAVAILABLE, ((MiddlewareException) after.getCause()).code());
            assertEquals("UNKNOWN", ((MiddlewareException) after.getCause()).remoteError().details().get("outcome"));
            assertEquals(2, Files.readAllLines(fixture.root.resolve("effects")).size(), "audit failure must not replay a dispatched effect");
            Files.delete(fixture.root.resolve("audit-fail"));
            String audit = Files.readString(fixture.root.resolve("audit"));
            assertTrue(audit.contains("DENIED")); assertTrue(audit.contains("SUCCESS")); assertTrue(audit.contains("FAILED"));
            assertFalse(audit.contains("private-input-sentinel")); assertFalse(audit.contains("synthetic-handler-detail"));
            assertTrue(Files.readAllLines(fixture.root.resolve("permissions")).stream().allMatch(line -> line.endsWith(":" + reference.objectId())));
            try (var tasks = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                var held = tasks.submit(() -> api.getMethod("hold").invoke(allowed));
                waitFor(fixture.root.resolve("entered"), fixture.process);
                try {
                    var overloaded = assertThrows(InvocationTargetException.class, () -> echo.invoke(allowed, "overloaded"));
                    assertEquals(MiddlewareErrorCode.OVERLOADED, ((MiddlewareException) overloaded.getCause()).code());
                    assertEquals(2, Files.readAllLines(fixture.root.resolve("effects")).size());
                } finally { Files.writeString(fixture.root.resolve("release"), "release"); }
                assertEquals("alice", held.get(5, TimeUnit.SECONDS));
            }
            assertTrue(command(fixture, "STOP").endsWith("true:STOPPED"));
        }
    }

    @Test
    void signedUnsatisfiableResourceAndHeapProfilesRejectBeforeAnyApplicationConstructor() throws Exception {
        for (String mode : List.of("resource", "isolation", "nonentry", "factorypolicy", "probeauth", "readiness-metadata")) try (Fixture fixture = fixture(mode, false)) {
            waitFor(fixture.root.resolve("failed"), fixture.process);
            assertTrue(fixture.process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, fixture.process.exitValue(), Files.readString(fixture.log));
            assertEquals("FAILED:0", Files.readString(fixture.root.resolve("failed")));
            Path order = fixture.root.resolve("order");
            assertTrue(!Files.exists(order) || Files.readAllLines(order).stream().noneMatch("CTOR"::equals));
            assertFalse(Files.exists(fixture.root.resolve("events")));
        }
    }

    @Test
    void actualReadinessWithdrawsDiscoveryAndHeldProbeRetainsLifetimeAfterMonitorTimeout() throws Exception {
        try (Fixture fixture = fixture("probes", false);
                var loader = new URLClassLoader(new java.net.URL[]{fixture.jar.toUri().toURL()}, getClass().getClassLoader());
                var alice = client("alice", fixture)) {
            waitFor(fixture.root.resolve("ready"), fixture.process);
            Class<?> api = loader.loadClass("fixture.api.Api");
            Object proxy = alice.reference(ManagedPojoProcess.readRef(fixture.root.resolve("reference")), api, ProxyOptions.defaults());
            var echo = api.getMethod("echo", String.class);
            assertTrue(command(fixture, "VISIBLE").endsWith(":0"));
            var initial = assertThrows(InvocationTargetException.class, () -> echo.invoke(proxy, "initial"));
            assertEquals(MiddlewareErrorCode.UNAVAILABLE, ((MiddlewareException) initial.getCause()).code());
            Files.delete(fixture.root.resolve("notready"));
            awaitCommand(fixture, "VISIBLE", ":1");
            assertTrue(((String) echo.invoke(proxy, "healthy")).startsWith("alice:healthy:"));
            waitFor(fixture.root.resolve("probe-safe-context"), fixture.process);
            Files.writeString(fixture.root.resolve("notready"), "not ready");
            awaitCommand(fixture, "VISIBLE", ":0");
            var withdrawn = assertThrows(InvocationTargetException.class, () -> echo.invoke(proxy, "withdrawn"));
            assertEquals(MiddlewareErrorCode.UNAVAILABLE, ((MiddlewareException) withdrawn.getCause()).code());
            Files.writeString(fixture.root.resolve("probe-block"), "block"); Files.delete(fixture.root.resolve("notready"));
            waitFor(fixture.root.resolve("probe-entered"), fixture.process);
            try {
                awaitCommand(fixture, "PROBE_STATUS", ":UNKNOWN");
                assertTrue(command(fixture, "STOP").endsWith("false:DRAINING"));
                assertEquals(List.of("I", "S", "D"), Files.readAllLines(fixture.root.resolve("events")));
            } finally { Files.writeString(fixture.root.resolve("probe-release"), "release"); }
            awaitCommand(fixture, "STOP", "true:STOPPED");
            assertEquals(List.of("I", "S", "D", "C"), Files.readAllLines(fixture.root.resolve("events")));
        }
    }

    @Test
    void healthOnlyProbeActuallyRunsWithoutClaimingReadinessOrWithdrawingOrdinaryHosting() throws Exception {
        try (Fixture fixture = fixture("healthonly", false);
                var loader = new URLClassLoader(new java.net.URL[]{fixture.jar.toUri().toURL()}, getClass().getClassLoader());
                var alice = client("alice", fixture)) {
            waitFor(fixture.root.resolve("ready"), fixture.process);
            waitFor(fixture.root.resolve("health-ran"), fixture.process);
            assertTrue(command(fixture, "PROBE_STATUS").endsWith(":UNKNOWN"));
            assertTrue(command(fixture, "VISIBLE").endsWith(":1"));
            Class<?> api = loader.loadClass("fixture.api.Api");
            Object proxy = alice.reference(ManagedPojoProcess.readRef(fixture.root.resolve("reference")), api, ProxyOptions.defaults());
            assertTrue(((String) api.getMethod("echo", String.class).invoke(proxy, "ordinary")).startsWith("alice:ordinary:"));
            assertEquals("anonymous", Files.readString(fixture.root.resolve("probe-safe-context")));
            awaitCommand(fixture, "STOP", "true:STOPPED");
        }
    }

    @Test
    void monitorCapacityFailureNeverPublishesSecondActivationOrDisruptsFirstGeneration() throws Exception {
        try (Fixture fixture = fixture("probe-capacity", false);
                var loader = new URLClassLoader(new java.net.URL[]{fixture.jar.toUri().toURL()}, getClass().getClassLoader());
                var alice = client("alice", fixture)) {
            waitFor(fixture.root.resolve("ready"), fixture.process); awaitCommand(fixture, "VISIBLE", ":1");
            ObjectRef original = ManagedPojoProcess.readRef(fixture.root.resolve("reference"));
            Class<?> api = loader.loadClass("fixture.api.Api");
            Object proxy = alice.reference(original, api, ProxyOptions.defaults());
            assertTrue(command(fixture, "SECOND").endsWith("CAPACITY:1:RUNNING"));
            awaitCommand(fixture, "VISIBLE", ":1");
            assertEquals(original.objectId(), ManagedPojoProcess.readRef(fixture.root.resolve("reference")).objectId());
            assertTrue(((String) api.getMethod("echo", String.class).invoke(proxy, "preserved")).startsWith("alice:preserved:"));
            awaitCommand(fixture, "STOP", "true:STOPPED");
        }
    }

    @Test
    void actualPlaintextTransportCannotInvokeConfidentialAnnotatedEntry() throws Exception {
        try (Fixture fixture = fixture("plaintextsecurity", false);
                var loader = new URLClassLoader(new java.net.URL[]{fixture.jar.toUri().toURL()}, getClass().getClassLoader());
                var runtime = new MiddlewareRuntimeBuilder().runtimeId("plaintext-client").addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build()) {
            waitFor(fixture.root.resolve("ready"), fixture.process);
            Class<?> api = loader.loadClass("fixture.api.Api");
            Exception failed = assertThrows(Exception.class, () -> {
                Object proxy = runtime.reference(ManagedPojoProcess.readRef(fixture.root.resolve("reference")), api, ProxyOptions.defaults());
                api.getMethod("echo", String.class).invoke(proxy, "plaintext-secret");
            });
            Throwable denied = failed instanceof InvocationTargetException invocation ? invocation.getCause() : failed;
            assertInstanceOf(MiddlewareException.class, denied);
            assertEquals(MiddlewareErrorCode.UNAUTHENTICATED, ((MiddlewareException) denied).code());
            assertFalse(Files.exists(fixture.root.resolve("effects")));
            awaitCommand(fixture, "STOP", "true:STOPPED");
        }
    }

    @Test
    void signedNamedModuleAsyncWorkersPreserveTwoPrincipalsCallDisposalAndLogicalPayloads() throws Exception {
        try (Fixture fixture = fixture("async", false, true);
                var loader = new URLClassLoader(new java.net.URL[]{fixture.jar.toUri().toURL()}, getClass().getClassLoader());
                var alice = client("alice", fixture); var bob = client("bob", fixture);
                var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            waitFor(fixture.root.resolve("ready"), fixture.process);
            Class<?> api = loader.loadClass("fixture.api.Api");
            ObjectRef reference = ManagedPojoProcess.readRef(fixture.root.resolve("reference"));
            Object first = alice.reference(reference, api, ProxyOptions.defaults());
            Object second = bob.reference(reference, api, ProxyOptions.defaults());
            var method = api.getMethod("asyncEcho", String.class);
            var one = workers.submit(() -> {
                for (int index = 0; index < 20; index++) {
                    String value = "a" + index;
                    var result = (java.util.concurrent.CompletionStage<?>) method.invoke(first, value);
                    assertTrue(((String) result.toCompletableFuture().get(5, TimeUnit.SECONDS)).startsWith("alice:" + value + ":"));
                    assertTrue(Files.exists(fixture.root.resolve("call-closed-alice-" + value)));
                }
                return true;
            });
            var two = workers.submit(() -> {
                for (int index = 0; index < 20; index++) {
                    String value = "b" + index;
                    var result = (java.util.concurrent.CompletionStage<?>) method.invoke(second, value);
                    assertTrue(((String) result.toCompletableFuture().get(5, TimeUnit.SECONDS)).startsWith("bob:" + value + ":"));
                    assertTrue(Files.exists(fixture.root.resolve("call-closed-bob-" + value)));
                }
                return true;
            });
            assertTrue(one.get(15, TimeUnit.SECONDS)); assertTrue(two.get(15, TimeUnit.SECONDS));
            var failed = (java.util.concurrent.CompletionStage<?>) api.getMethod("asyncFail").invoke(first);
            var error = assertThrows(java.util.concurrent.ExecutionException.class, () -> failed.toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertInstanceOf(MiddlewareException.class, error.getCause());
            assertEquals(MiddlewareErrorCode.REMOTE_EXCEPTION, ((MiddlewareException) error.getCause()).code());
            assertFalse(error.getCause().getMessage().contains("synthetic-async-handler-secret"));
            api.getMethod("fire", String.class).invoke(second, "fire");
            waitFor(fixture.root.resolve("oneway-effect"), fixture.process);
            assertEquals("bob:fire", Files.readString(fixture.root.resolve("oneway-effect")));
            awaitCommand(fixture, "INFLIGHT", ":0");
            assertTrue(command(fixture, "STOP").endsWith("true:STOPPED"));
        }
    }

    @Test
    void signedAsyncTimeoutAndCancelRetainCallResourcePermitAndInflightUntilActualWorkerExit() throws Exception {
        try (Fixture fixture = fixture("async", false);
                var loader = new URLClassLoader(new java.net.URL[]{fixture.jar.toUri().toURL()}, getClass().getClassLoader());
                var runtime = client("alice", fixture)) {
            waitFor(fixture.root.resolve("ready"), fixture.process);
            Class<?> api = loader.loadClass("fixture.api.Api");
            Object proxy = runtime.reference(ManagedPojoProcess.readRef(fixture.root.resolve("reference")), api, ProxyOptions.defaults());
            var warm = (java.util.concurrent.CompletionStage<?>) api.getMethod("asyncEcho", String.class).invoke(proxy, "warm");
            warm.toCompletableFuture().get(5, TimeUnit.SECONDS);
            var hold = api.getMethod("asyncHold", String.class);
            var timed = (java.util.concurrent.CompletionStage<?>) hold.invoke(proxy, "timeout");
            waitFor(fixture.root.resolve("async-entered-timeout"), fixture.process);
            var overloaded = (java.util.concurrent.CompletionStage<?>) hold.invoke(proxy, "not-admitted");
            var denied = assertThrows(java.util.concurrent.ExecutionException.class, () -> overloaded.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(MiddlewareErrorCode.OVERLOADED, ((MiddlewareException) denied.getCause()).code());
            assertFalse(Files.exists(fixture.root.resolve("async-entered-not-admitted")));
            var expired = assertThrows(java.util.concurrent.ExecutionException.class, () -> timed.toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertEquals(MiddlewareErrorCode.DEADLINE_EXCEEDED, ((MiddlewareException) expired.getCause()).code());
            assertTrue(command(fixture, "INFLIGHT").endsWith(":1"));
            assertTrue(command(fixture, "RESOURCE_ACTIVE").endsWith(":1"));
            assertFalse(Files.exists(fixture.root.resolve("call-closed-timeout")));
            assertTrue(command(fixture, "UPGRADE").endsWith("false:RUNNING"));
            Files.writeString(fixture.root.resolve("async-release-timeout"), "release");
            waitFor(fixture.root.resolve("call-closed-timeout"), fixture.process);
            awaitCommand(fixture, "INFLIGHT", ":0");
            assertTrue(command(fixture, "RESOURCE_ACTIVE").endsWith(":0"));
            var cancelled = (java.util.concurrent.CompletionStage<?>) api.getMethod("asyncCancelHold", String.class).invoke(proxy, "cancel");
            waitFor(fixture.root.resolve("async-entered-cancel"), fixture.process);
            assertSame(cancelled, cancelled.toCompletableFuture(), "client proxy returned a non-cancellable future copy");
            assertTrue(cancelled.toCompletableFuture().cancel(false));
            long signalDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!Files.exists(fixture.root.resolve("async-cancelled-cancel")) && System.nanoTime() < signalDeadline) {
                java.util.concurrent.locks.LockSupport.parkNanos(1000000);
            }
            assertTrue(Files.exists(fixture.root.resolve("async-cancelled-cancel")), "transport cancellation was not observed before annotation expiry");
            assertTrue(command(fixture, "INFLIGHT").endsWith(":1"));
            assertTrue(command(fixture, "RESOURCE_ACTIVE").endsWith(":1"));
            assertTrue(command(fixture, "STOP").endsWith("false:DRAINING"));
            assertFalse(Files.exists(fixture.root.resolve("call-closed-cancel")));
            Files.writeString(fixture.root.resolve("async-release-cancel"), "release");
            waitFor(fixture.root.resolve("call-closed-cancel"), fixture.process);
            awaitCommand(fixture, "INFLIGHT", ":0");
            assertTrue(command(fixture, "STOP").endsWith("true:STOPPED"));
        }
    }

    private static void awaitCommand(Fixture fixture, String request, String suffix) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!command(fixture, request).endsWith(suffix)) {
            if (System.nanoTime() >= end) fail("host observation did not converge: " + request);
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    @Test
    void signedNamedModuleUsesVerifiedExportSchemaWithoutOpeningApplicationPackages() throws Exception {
        try (Fixture fixture = fixture("schema", false, true);
                var loader = new URLClassLoader(new java.net.URL[]{fixture.jar.toUri().toURL()}, getClass().getClassLoader())) {
            waitFor(fixture.root.resolve("ready"), fixture.process);
            var indexes = java.util.ServiceLoader.load(org.j7m.middleware.codec.api.SchemaIndex.class, loader).stream().map(java.util.ServiceLoader.Provider::get).toList();
            assertEquals(1, indexes.size());
            var codec = new org.j7m.middleware.codec.defaultcodec.DefaultValueCodec(org.j7m.middleware.codec.api.SchemaIndexes.registry(indexes));
            var tls = new GrpcTlsConfiguration(fixture.root.resolve("alice.crt"), fixture.root.resolve("alice.key"), fixture.root.resolve("trust.crt"), true, "default", "localhost");
            try (var client = new MiddlewareRuntimeBuilder().runtimeId("schema-client").codec(codec)
                    .addTransport(new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(true,tls))).disableNuggetAdmin().build()) {
                var api = loader.loadClass("fixture.api.Api"); var dto = loader.loadClass("fixture.api.Payload");
                var proxy = client.reference(ManagedPojoProcess.readRef(fixture.root.resolve("reference")),api,ProxyOptions.defaults());
                var value = dto.getConstructor(String.class).newInstance("payload");
                Object result;
                try { result = api.getMethod("payload",dto).invoke(proxy,value); }
                catch (InvocationTargetException failure) {
                    var error = (MiddlewareException) failure.getCause();
                    throw new AssertionError("Schema call failed: "+error.code()+" policyEntered="+Files.exists(fixture.root.resolve("policy-invoked"))+" host="+Files.readString(fixture.log),error);
                }
                assertEquals("alice:payload",dto.getMethod("value").invoke(result));
                var async = (java.util.concurrent.CompletionStage<?>) api.getMethod("asyncPayload",dto).invoke(proxy,value);
                assertEquals("alice:payload",dto.getMethod("value").invoke(async.toCompletableFuture().get(5,TimeUnit.SECONDS)));
                var oversized = dto.getConstructor(String.class).newInstance("x".repeat(5000));
                var forged = client.reference(ManagedPojoProcess.readRef(fixture.root.resolve("reference")),api,new ProxyOptions(Duration.ofSeconds(5),Map.of("inboundBytes","0")));
                var rejected = assertThrows(InvocationTargetException.class,() -> api.getMethod("payload",dto).invoke(forged,oversized));
                assertEquals(MiddlewareErrorCode.OVERLOADED,((MiddlewareException)rejected.getCause()).code());
                assertFalse(Files.readString(fixture.root.resolve("sources/module-info.java")).contains("opens"));
                assertTrue(command(fixture,"STOP").endsWith("true:STOPPED"));
            }
        }
    }

    private Fixture fixture(String mode, boolean partial) throws Exception { return fixture(mode, partial, false); }

    private Fixture fixture(String mode, boolean partial, boolean modular) throws Exception {
        Path root = Files.createDirectory(directory.resolve(mode));
        Path classes = compile(root, partial, modular, mode);
        Path jar = root.resolve("application.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar)); var paths = Files.walk(classes)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\', '/')));
                output.write(Files.readAllBytes(path)); output.closeEntry();
            }
        }
        Path manifest = root.resolve("application.json");
        String second = partial ? ",{\"serviceName\":\"//managed/other\",\"interfaceName\":\"fixture.api.Api\",\"implementationClass\":\"fixture.hidden.Other\",\"transport\":\"rmi\",\"globallyExported\":true,\"metadata\":{}}" : "";
        String bootstrapModule = modular ? ",\"bootstrapModule\":\"fixture.application\"" : "";
        String modules = modular ? "\"modules\":[{\"name\":\"fixture.application\",\"path\":\"modules/application.jar\"}]," : "";
        Files.writeString(manifest, """
            {"schemaVersion":"j7m.application/v1","application":{"group":"fixture","name":"pojo","version":"1","displayName":"POJO","description":"signed managed hosting integration"},
            "runtime":{"minJ7mVersion":"0.6.6","javaVersion":"25","moduleMode":"%s","bootstrapClass":"fixture.hidden.App"%s},
            %s"services":[{"serviceName":"//managed/pojo","interfaceName":"fixture.api.Api","implementationClass":"fixture.hidden.Service","transport":"grpc","globallyExported":true,"metadata":{}}%s],
            "security":{"trustDomain":"default","confidential":true},"resources":{"cpu":"1","memory":"128MiB","maxConcurrentInvocations":8,"maxInboundPayload":"1MiB"}}
            """.formatted(modular ? "JPMS" : "CLASSPATH", bootstrapModule, modules, second));
        if ("schema".equals(mode)) Files.writeString(manifest,Files.readString(manifest).replace("1MiB","4KiB"));
        if ("plaintextsecurity".equals(mode)) Files.writeString(manifest, Files.readString(manifest).replace("\"confidential\":true", "\"confidential\":false"));
        if ("readiness-metadata".equals(mode)) Files.writeString(manifest, Files.readString(manifest)
                .replace("\"metadata\":{}", "\"metadata\":{\"j7m.readiness.ready\":\"true\"}"));
        var bundle = new J7mBundleWriter().write(manifest, root.resolve("signed.j7m"), modular ? List.of() : List.of(jar), modular ? List.of(jar) : List.of(), List.of());
        var keyPair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var signing = new org.j7m.middleware.application.repository.SigningKeyPair(new org.j7m.middleware.application.repository.SigningKeyId("operator"), "test-operator", keyPair.getPrivate(), keyPair.getPublic());
        if (!"unsigned".equals(mode)) bundle = new DefaultBundleSigner(signing, java.time.Clock.systemUTC()).sign(bundle);
        Path signerPublic = Files.write(root.resolve("signer-public.der"), keyPair.getPublic().getEncoded());
        Path managerPublic = "untrusted".equals(mode)
                ? Files.write(root.resolve("manager-public.der"), java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded()) : signerPublic;
        for (String peer : List.of("host", "alice", "bob")) certificate(root, peer);
        Path trust = root.resolve("trust.crt");
        Files.writeString(trust, Files.readString(root.resolve("host.crt")) + Files.readString(root.resolve("alice.crt")) + Files.readString(root.resolve("bob.crt")));
        Path log = root.resolve("host.log");
        List<String> command = new ArrayList<>(List.of(javaTool("java")));
        if (modular) {
            Path host = compileModularHost(root);
            command.addAll(List.of("--module-path", modulePath() + java.io.File.pathSeparator + host, "--add-modules", "ALL-MODULE-PATH",
                    "-m", "fixture.host/fixture.host.ManagedPojoProcess"));
        } else command.addAll(List.of("-cp", System.getProperty("j7m.artifact.test.classpath"), ManagedPojoProcess.class.getName()));
        command.addAll(List.of(bundle.path().toString(), root.toString(), root.resolve("host.crt").toString(), root.resolve("host.key").toString(), trust.toString(), Boolean.toString("startup".equals(mode)), mode, signerPublic.toString(), managerPublic.toString()));
        if ("probes".equals(mode)) Files.writeString(root.resolve("notready"), "not ready");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        return new Fixture(root, jar, log, process);
    }

    private Path compile(Path root, boolean partial, boolean modular, String mode) throws Exception {
        Path sources = root.resolve("sources"), classes = root.resolve("classes");
        Files.createDirectories(sources); Files.createDirectories(classes);
        List<Path> files = new ArrayList<>();
        files.add(write(sources, "Api", """
            package fixture.api;
            import org.j7m.middleware.annotations.*;
            @RemoteService public interface Api {
                @RemoteMethod String echo(String value); String echo(int value);
                @RemoteMethod @NoRemoteExport String value(@NoRemoteExport String value);
                @RemoteMethod org.j7m.middleware.api.ObjectRef handle(@NoRemoteExport org.j7m.middleware.api.ObjectRef value);
                @RemoteMethod String hold() throws Exception;
                @RemoteMethod String fail();
            }
            """));
        files.add(write(sources, "App", """
            package fixture.hidden;
            import org.j7m.middleware.annotations.*;
            @J7mApplication(group="fixture",name="pojo",version="1") public class App implements AutoCloseable {
                @Inject public App(@Named("primary") Service service,@CurrentApplication org.j7m.middleware.application.api.ApplicationContext context)throws Exception{if(!context.configuration().get("managed.marker").orElse("").equals("kept"))throw new IllegalStateException("activation configuration lost");java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("managed.events")).resolveSibling("order"),"CTOR\\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);}
                private void event(String value)throws Exception{java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("managed.events")),value+"\\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);}
                @Initialize public void initialize()throws Exception{event("I");}
                @Startup public void start()throws Exception{event("S");if(Boolean.getBoolean("managed.fail"))throw new IllegalStateException("expected startup failure");}
                @Drain public void drain()throws Exception{event("D");}
                @Shutdown public void close()throws Exception{event("C");}
            }
            """));
        files.add(write(sources, "Service", """
            package fixture.hidden;
            import org.j7m.middleware.annotations.*;
            import org.j7m.middleware.application.api.ManagedProvider;
            import org.j7m.middleware.security.api.Principal;
            @J7mComponent(name="primary") @J7mService(name="//managed/pojo",contractId="fixture.contract") public class Service implements fixture.api.Api {
                protected final ManagedProvider<Principal> principal;
                private final ManagedProvider<org.j7m.middleware.application.api.InvocationContext> invocation;
                @Inject public Service(@CurrentPrincipal ManagedProvider<Principal> principal,@CurrentInvocation ManagedProvider<org.j7m.middleware.application.api.InvocationContext> invocation)throws Exception{this.principal=principal;this.invocation=invocation;java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("managed.events")).resolveSibling("order"),"CTOR\\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);}
                public String echo(String value){return handoff(value);}
                private String handoff(String value){
                    var captured=invocation.get();
                    try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
                        return executor.submit(()->{
                            String result;
                            try(var scope=org.j7m.middleware.application.api.J7mContext.open(captured)){
                                result=principal.get().id()+":"+value+":"+invocation.get().correlationId();
                            }
                            if(!org.j7m.middleware.application.api.J7mContext.currentInvocation().correlationId().isEmpty())throw new IllegalStateException("worker context leaked");
                            return result;
                        }).get(2,java.util.concurrent.TimeUnit.SECONDS);
                    }catch(Exception failure){throw new IllegalStateException("explicit context handoff failed",failure);}
                }
                public String echo(int value){throw new AssertionError("unlisted overload executed");}
                public String value(String value){return value;}
                public String fail(){throw new IllegalStateException("synthetic-handler-detail");}
                public org.j7m.middleware.api.ObjectRef handle(org.j7m.middleware.api.ObjectRef value){return value;}
                public String hold()throws Exception{java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("managed.entered")),"entered");while(!java.nio.file.Files.exists(java.nio.file.Path.of(System.getProperty("managed.release"))))Thread.sleep(10);return principal.get().id();}
            }
            """));
        Path serviceSource = sources.resolve("Service.java");
        if ("async".equals(mode)) {
            Path apiSource = sources.resolve("Api.java");
            String apiBody = Files.readString(apiSource);
            int apiEnd = apiBody.lastIndexOf('}');
            Files.writeString(apiSource, apiBody.substring(0, apiEnd) + """
                @RemoteMethod @Async java.util.concurrent.CompletionStage<String> asyncEcho(String value);
                @RemoteMethod @Async @Cancellable @LongRunning @Deadline(millis=500) @ResourceRequirement("small") java.util.concurrent.CompletionStage<String> asyncHold(String token);
                @RemoteMethod @Async @Cancellable @Deadline(millis=5000) @ResourceRequirement("small") java.util.concurrent.CompletionStage<String> asyncCancelHold(String token);
                @RemoteMethod @Async java.util.concurrent.CompletionStage<String> asyncFail();
                @RemoteMethod @OneWay void fire(String value);
                """ + apiBody.substring(apiEnd));
            String body = Files.readString(serviceSource)
                    .replace("InvocationContext> invocation)throws Exception", "InvocationContext> invocation,ManagedProvider<CallValue> callValues)throws Exception")
                    .replace("this.invocation=invocation;", "this.invocation=invocation;this.callValues=callValues;");
            String methods = """
                private final ManagedProvider<CallValue> callValues;
                public static class CallValue implements AutoCloseable {
                    public String token="unused";
                    public boolean closed;
                    public void close()throws Exception{
                        if(closed)throw new IllegalStateException("duplicate CALL close"); closed=true;
                        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("managed.events")).resolveSibling("call-closed-"+token),"closed");
                    }
                }
                @Produces @Scope(Scope.Kind.CALL) public static CallValue callValue(){return new CallValue();}
                private java.nio.file.Path asyncFile(String name){return java.nio.file.Path.of(System.getProperty("managed.events")).resolveSibling(name);}
                public java.util.concurrent.CompletionStage<String> asyncEcho(String value){
                    var control=invocation.get().control();
                    return control.submit(()->{
                        var owned=callValues.get();owned.token=principal.get().id()+"-"+value;
                        if(owned.closed||control instanceof org.j7m.middleware.transport.api.InvocationCompletionScope)throw new IllegalStateException("invalid ownership");
                        var actual=org.j7m.middleware.security.api.SecurityContexts.current();
                        if(actual==null||!actual.principal().id().equals(principal.get().id()))throw new IllegalStateException("async identity crossed");
                        return principal.get().id()+":"+value+":"+invocation.get().correlationId();
                    });
                }
                public java.util.concurrent.CompletionStage<String> asyncHold(String token){return heldAsync(token);}
                public java.util.concurrent.CompletionStage<String> asyncCancelHold(String token){return heldAsync(token);}
                private java.util.concurrent.CompletionStage<String> heldAsync(String token){
                    var control=invocation.get().control();
                    return control.submit(()->{
                        var owned=callValues.get();owned.token=token;
                        java.nio.file.Files.writeString(asyncFile("async-entered-"+token),principal.get().id());
                        try{
                            while(!java.nio.file.Files.exists(asyncFile("async-release-"+token))){
                                if(control.cancellationRequested())java.nio.file.Files.writeString(asyncFile("async-cancelled-"+token),"observed");
                                if(owned.closed)throw new IllegalStateException("CALL closed before worker exit");
                                java.util.concurrent.locks.LockSupport.parkNanos(1000000);
                            }
                            return principal.get().id();
                        }finally{
                            if(owned.closed)throw new IllegalStateException("CALL closed inside worker finally");
                            java.nio.file.Files.writeString(asyncFile("async-exited-"+token),"exited");
                        }
                    });
                }
                public java.util.concurrent.CompletionStage<String> asyncFail(){return invocation.get().control().submit(()->{throw new IllegalStateException("synthetic-async-handler-secret");});}
                public void fire(String value){
                    invocation.get().control().contextualExecutor().execute(()->{
                        try{java.nio.file.Files.writeString(asyncFile("oneway-effect"),principal.get().id()+":"+value);}
                        catch(java.io.IOException failure){throw new IllegalStateException(failure);}
                    });
                }
                """;
            int end = body.lastIndexOf('}');
            Files.writeString(serviceSource, body.substring(0, end) + methods + body.substring(end));
        }
        if (List.of("security", "plaintextsecurity").contains(mode)) {
            Path apiSource = sources.resolve("Api.java");
            Files.writeString(apiSource, Files.readString(apiSource)
                    .replace("@RemoteService public interface", "@Confidential @Audit @RemoteService public interface")
                    .replace("@RemoteMethod String echo(String value)", "@RemoteMethod @RequiresTrustDomain(\"default\") @RequiresPermission(\"orders:invoke\") String echo(@Sensitive String value)")
                    .replace("@RemoteMethod org.j7m.middleware.api.ObjectRef handle", "@RemoteMethod @RequiresTrustDomain(\"other\") org.j7m.middleware.api.ObjectRef handle"));
            Files.writeString(serviceSource, Files.readString(serviceSource)
                    .replace("@J7mComponent(name=\"primary\")", "@ResourceRequirement(\"small\") @RequiresRole(\"runtime\") @J7mComponent(name=\"primary\")")
                    .replace("public String value(String value)", "@RequiresRole(\"admin\") public String value(String value)")
                    .replace("public String echo(String value){return", "public String echo(String value){try{java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty(\"managed.events\")).resolveSibling(\"effects\"),\"effect\\n\",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);}catch(java.io.IOException e){throw new IllegalStateException(e);}return"));
        }
        if ("resource".equals(mode)) Files.writeString(serviceSource, Files.readString(serviceSource)
                .replace("@J7mComponent(name=\"primary\")", "@ResourceRequirement(\"small\") @Priority(11) @J7mComponent(name=\"primary\")"));
        if ("isolation".equals(mode)) Files.writeString(serviceSource, Files.readString(serviceSource)
                .replace("@J7mComponent(name=\"primary\")", "@IsolationProfile(\"small-heap\") @J7mComponent(name=\"primary\")"));
        if (List.of("probes", "healthonly", "probe-capacity").contains(mode)) {
            String methods="""
                private java.nio.file.Path probeFile(String name){return java.nio.file.Path.of(System.getProperty("managed.events")).resolveSibling(name);}
                private void probeContext()throws Exception{
                    var actual=org.j7m.middleware.security.api.SecurityContexts.current();
                    var safe=org.j7m.middleware.application.api.J7mContext.currentInvocation().security().principal();
                    if(actual==null||actual.authenticated()||!actual.roles().isEmpty()||!safe.id().equals("anonymous")||!safe.roles().isEmpty()
                       ||!actual.principal().id().equals("anonymous"))throw new IllegalStateException("probe inherited authority");
                    if(!java.nio.file.Files.exists(probeFile("probe-safe-context")))java.nio.file.Files.writeString(probeFile("probe-safe-context"),safe.id());
                }
                @HealthCheck public boolean health()throws Exception{probeContext();java.nio.file.Files.writeString(probeFile("health-ran"),"ran");return true;}
                """;
            if(!"healthonly".equals(mode)) methods+="""
                @ReadinessCheck public boolean ready()throws Exception{
                    probeContext();
                    if(java.nio.file.Files.exists(probeFile("probe-block"))){java.nio.file.Files.writeString(probeFile("probe-entered"),"entered");
                        while(!java.nio.file.Files.exists(probeFile("probe-release"))){java.util.concurrent.locks.LockSupport.parkNanos(1000000);Thread.interrupted();}}
                    return !java.nio.file.Files.exists(probeFile("notready"));
                }
                """;
            String body=Files.readString(serviceSource); int end=body.lastIndexOf('}');
            Files.writeString(serviceSource,body.substring(0,end)+methods+body.substring(end));
        }
        if (List.of("nonentry", "factorypolicy", "probeauth").contains(mode)) {
            String method=switch(mode){
                case "nonentry" -> "@RequiresRole(\"admin\") private void local(){}";
                case "factorypolicy" -> "@Produces @RequiresPermission(\"admin\") public String product(){return \"value\";}";
                default -> "@HealthCheck @RequiresRole(\"admin\") public boolean probe(){return true;}";
            };
            String body=Files.readString(serviceSource); int end=body.lastIndexOf('}');
            Files.writeString(serviceSource,body.substring(0,end)+method+body.substring(end));
        }
        if ("disabled".equals(mode)) Files.writeString(serviceSource, Files.readString(serviceSource).replace("contractId=\"fixture.contract\"", "contractId=\"fixture.contract\",exported=false"));
        if ("noexport".equals(mode)) Files.writeString(serviceSource, Files.readString(serviceSource).replace("@J7mComponent(name=\"primary\")", "@NoRemoteExport @J7mComponent(name=\"primary\")"));
        if (partial) files.add(write(sources, "Other", """
            package fixture.hidden; import org.j7m.middleware.annotations.*;
            @J7mService(name="//managed/other") public class Other extends Service {
                public Other(@CurrentPrincipal org.j7m.middleware.application.api.ManagedProvider<org.j7m.middleware.security.api.Principal> principal,@CurrentInvocation org.j7m.middleware.application.api.ManagedProvider<org.j7m.middleware.application.api.InvocationContext> invocation)throws Exception{super(principal,invocation);}
            }
            """));
        if (mode.startsWith("di-") && !mode.equals("di-construction")) {
            String missing = "package fixture.hidden; public class Missing {}";
            if (mode.equals("di-cycle")) missing = "package fixture.hidden; import org.j7m.middleware.annotations.*; @J7mComponent public class Missing {@Inject public Missing(@Named(\"primary\") Service service){}}";
            if (mode.equals("di-scope")) missing = "package fixture.hidden; import org.j7m.middleware.annotations.*; @J7mComponent @Scope(Scope.Kind.CALL) public class Missing {}";
            files.add(write(sources,"Missing",missing));
            if (mode.equals("di-ambiguous")) {
                files.add(write(sources,"FirstMissing","package fixture.hidden; @org.j7m.middleware.annotations.J7mComponent public class FirstMissing extends Missing {}"));
                files.add(write(sources,"SecondMissing","package fixture.hidden; @org.j7m.middleware.annotations.J7mComponent public class SecondMissing extends Missing {}"));
            }
            Files.writeString(serviceSource,Files.readString(serviceSource).replace("invocation)throws Exception", "invocation,Missing missing)throws Exception"));
        }
        if (mode.equals("di-construction")) {
            String body = Files.readString(serviceSource); int start = body.indexOf("@Inject public Service("); int end = body.indexOf('\n',start); int brace = body.lastIndexOf('}',end);
            Files.writeString(serviceSource,body.substring(0,brace)+"throw new Exception(\"password=private-constructor-detail\");"+body.substring(brace));
        }
        if (mode.equals("schema")) {
            files.add(write(sources, "Payload", "package fixture.api; import org.j7m.middleware.annotations.*; @SchemaType(id=\"fixture.payload\",version=1) public record Payload(@WireField(id=1) String value) {}"));
            Path apiSource = sources.resolve("Api.java"); String api = Files.readString(apiSource);
            int end = api.lastIndexOf('}'); Files.writeString(apiSource, api.substring(0,end)+"@RemoteMethod Payload payload(Payload value); @RemoteMethod @Async java.util.concurrent.CompletionStage<Payload> asyncPayload(Payload value);"+api.substring(end));
            String body = Files.readString(serviceSource); end = body.lastIndexOf('}');
            Files.writeString(serviceSource, body.substring(0,end)+"public fixture.api.Payload payload(fixture.api.Payload value){return new fixture.api.Payload(principal.get().id()+\":\"+value.value());}"+"public java.util.concurrent.CompletionStage<fixture.api.Payload> asyncPayload(fixture.api.Payload value){return invocation.get().control().submit(()->new fixture.api.Payload(principal.get().id()+\":\"+value.value()));}"+body.substring(end));
        }
        if (modular) files.add(write(sources, "module-info", "module fixture.application {requires org.j7m.api; "+(mode.equals("jpms-noexport") ? "" : "exports fixture.api; ")+"provides org.j7m.middleware.application.api.ManagedComponentIndex with fixture.hidden.App__J7mComponentIndex,fixture.hidden.Service__J7mComponentIndex;"
                +(mode.equals("schema") ? "provides org.j7m.middleware.codec.api.SchemaIndex with fixture.api.Payload__J7mSchema;" : "")+"}"));
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(diagnostics, null, null)) {
            var task = ToolProvider.getSystemJavaCompiler().getTask(null, manager, diagnostics,
                    modular ? List.of("--module-path", modulePath(), "-d", classes.toString())
                            : List.of("-classpath", System.getProperty("j7m.artifact.test.classpath"), "-d", classes.toString()), null, manager.getJavaFileObjectsFromPaths(files));
            task.setProcessors(List.of(new org.j7m.middleware.processor.J7mProcessor()));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        return classes;
    }

    private Path compileModularHost(Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("host-sources"));
        Path output = Files.createDirectories(root.resolve("host-classes"));
        String host = Files.readString(Path.of("src/test/java/org/j7m/middleware/deployer/ManagedPojoProcess.java"))
                .replace("package org.j7m.middleware.deployer;", "package fixture.host; import org.j7m.middleware.deployer.ApplicationManager;");
        Path main = Files.writeString(source.resolve("ManagedPojoProcess.java"), host);
        Path descriptor = Files.writeString(source.resolve("module-info.java"), "module fixture.host {requires org.j7m.application.platform; requires org.j7m.transport.grpc;}");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(diagnostics, null, null)) {
            assertTrue(ToolProvider.getSystemJavaCompiler().getTask(null, manager, diagnostics, List.of("--module-path", modulePath(), "-proc:none", "-d", output.toString()), null,
                    manager.getJavaFileObjectsFromPaths(List.of(main, descriptor))).call(), diagnostics.getDiagnostics().toString());
        }
        return output;
    }

    private static String modulePath() {
        Map<String, Path> modules = new java.util.LinkedHashMap<>();
        for (String entry : System.getProperty("j7m.artifact.test.classpath").split(java.io.File.pathSeparator)) {
            Path path = Path.of(entry);
            if (!Files.isRegularFile(path) && !Files.exists(path.resolve("module-info.class"))) continue;
            for (var module : java.lang.module.ModuleFinder.of(path).findAll()) modules.putIfAbsent(module.descriptor().name(), path);
        }
        return String.join(java.io.File.pathSeparator, modules.values().stream().map(Path::toString).toList());
    }

    @Test
    void signedNamedModuleUsesUnexportedImplementationPackageWithoutAnyOpens() throws Exception {
        try (Fixture fixture = fixture("jpms", false, true);
                var loader = new URLClassLoader(new java.net.URL[]{fixture.jar.toUri().toURL()}, getClass().getClassLoader());
                var runtime = client("alice", fixture)) {
            waitFor(fixture.root.resolve("ready"), fixture.process);
            Class<?> api = loader.loadClass("fixture.api.Api");
            Object proxy = runtime.reference(ManagedPojoProcess.readRef(fixture.root.resolve("reference")), api, ProxyOptions.defaults());
            assertTrue(((String) api.getMethod("echo", String.class).invoke(proxy, "JPMS")).startsWith("alice:JPMS:"));
            assertTrue(command(fixture, "STOP").endsWith("true:STOPPED"));
            assertEquals(List.of("I", "S", "D", "C"), Files.readAllLines(fixture.root.resolve("events")));
        }
    }

    private static Path write(Path sources, String name, String source) throws Exception { return Files.writeString(sources.resolve(name + ".java"), source); }

    private static void certificate(Path root, String name) throws Exception {
        Process process = new ProcessBuilder("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-keyout", root.resolve(name + ".key").toString(),
                "-out", root.resolve(name + ".crt").toString(), "-subj", "/CN=" + name, "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
    }

    private static org.j7m.middleware.core.DefaultMiddlewareRuntime client(String name, Fixture fixture) { return client(name, fixture, true); }

    private static org.j7m.middleware.core.DefaultMiddlewareRuntime client(String name, Fixture fixture, boolean certificate) {
        var tls = new GrpcTlsConfiguration(fixture.root.resolve(name + ".crt"), fixture.root.resolve(name + ".key"), fixture.root.resolve("trust.crt"), certificate, "default", "localhost");
        return new MiddlewareRuntimeBuilder().runtimeId("client-" + name).addTransport(new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(true, tls))).disableNuggetAdmin().build();
    }

    private static String command(Fixture fixture, String command) throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        Path temporary = fixture.root.resolve("command.tmp"); Files.writeString(temporary, id + ":" + command);
        Files.move(temporary, fixture.root.resolve("command"), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && fixture.process.isAlive()) {
            Path reply = fixture.root.resolve("reply");
            if (Files.exists(reply)) { String value = Files.readString(reply); if (value.startsWith(id + ":")) return value; }
            Thread.sleep(10);
        }
        throw new AssertionError("fixture command failed: " + Files.readString(fixture.log));
    }

    private static void waitFor(Path path, Process process) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(path) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.exists(path), () -> "host did not publish " + path.getFileName() + ": " + readLog(path.getParent().resolve("host.log")));
    }
    private static String readLog(Path path) { try { return Files.readString(path); } catch (Exception failure) { return "log unavailable"; } }
    private static String javaTool(String name) { return Path.of(System.getProperty("java.home"), "bin", name).toString(); }

    private record Fixture(Path root, Path jar, Path log, Process process) implements AutoCloseable {
        public void close() throws Exception {
            if (process.isAlive()) { Files.writeString(root.resolve("command"), "exit:EXIT"); if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly(); }
        }
    }
}
