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

package org.j7m.middleware.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.j7m.middleware.api.InstanceStatus;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ObjectRegistration;
import org.j7m.middleware.api.ObjectState;
import org.j7m.middleware.api.RegistrationState;
import org.j7m.middleware.codec.api.EncodedValue;
import org.j7m.middleware.application.api.ActivationIdentity;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationActivationRequest;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationContext;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationDeploymentPlan;
import org.j7m.middleware.application.api.ApplicationDeploymentResult;
import org.j7m.middleware.application.api.ApplicationId;
import org.j7m.middleware.application.api.ApplicationIdentity;
import org.j7m.middleware.application.api.ApplicationInstanceId;
import org.j7m.middleware.application.api.ApplicationInstanceIdentity;
import org.j7m.middleware.application.api.ApplicationQuery;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.j7m.middleware.application.api.ApplicationVersion;
import org.j7m.middleware.application.api.RepositoryPublishOptions;
import org.j7m.middleware.application.repository.J7mBundleWriter;
import org.j7m.middleware.application.repository.ApplicationPlatformLimits;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.deployer.ApplicationManager;
import org.j7m.middleware.deployer.DefaultApplicationDeployer;
import org.j7m.middleware.deployer.api.RollbackOptions;
import org.j7m.middleware.deployer.api.UpgradeOptions;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.registry.api.PeerQuery;
import org.j7m.middleware.registry.api.RegistryClient;
import org.j7m.middleware.registry.api.RegistryPeerStatus;
import org.j7m.middleware.registry.api.RegistryQuery;
import org.j7m.middleware.registry.api.RegistryRecord;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.tests.fixtures.BlockingFailingExportServiceImpl;
import org.j7m.middleware.tests.fixtures.FailingExportApplication;
import org.j7m.middleware.tests.fixtures.FailingExportService;
import org.j7m.middleware.tests.fixtures.FailingExportServiceImpl;
import org.j7m.middleware.tests.fixtures.LifecycleFixtures;
import org.j7m.middleware.tests.fixtures.PassingExportServiceImpl;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Corrective Gate 4 tests for local application lifecycle, upgrade strategies,
 * rollback, diagnostics, and admin/status completeness.
 */
final class Phase54LifecycleUpgradeAdminClosureTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void activationPlanningStopUnloadAndFailureDiagnosticsAreComplete() throws ReflectiveOperationException, IOException {
        LocalApplicationRepository repository = repository("lifecycle-repo");
        repository.publish(modularBundle("1.0.0"), new RepositoryPublishOptions("test", false));
        repository.publish(badModularBundle(), new RepositoryPublishOptions("test", false));
        try (DefaultMiddlewareRuntime runtime = runtime("phase54-lifecycle")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            ApplicationCoordinate coordinate = ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.0.0");
            ApplicationDeploymentPlan plan = manager.planActivation(coordinate);
            assertEquals(ApplicationRunState.PLANNED, plan.state());
            assertTrue(plan.steps().stream().anyMatch(step -> step.contains("verify bundle")));
            assertEquals(ApplicationRunState.REJECTED,
                    manager.planActivation(ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:404.0.0")).state());

            ApplicationActivation activation = manager.activate(coordinate,
                    new ApplicationActivationRequest("operator", "dev", false, Map.of()));
            Class<?> api = manager.serviceInterface(activation.instanceIdentity().instanceId(),
                    "com.example.j7m.modular.hello.api.ModularHelloService");
            Object proxy = runtime.lookup("//apps/example/modular/hello/ModularHelloService", api, LookupOptions.defaults());
            assertEquals("modular hello gate4", api.getMethod("hello", String.class).invoke(proxy, "gate4"));
            assertTrue(manager.list(ApplicationQuery.all()).getFirst().metadata().get("transitionTimestamps").contains("RUNNING"));

            assertTrue(manager.deactivate(activation.instanceIdentity().instanceId()).success());
            ApplicationActivation stopped = manager.list(new ApplicationQuery(null, ApplicationRunState.STOPPED)).getFirst();
            assertEquals("true", stopped.metadata().get("heartbeatStopped"));
            assertEquals(0, runtime.list("//apps/example/modular/hello/ModularHelloService",
                    List.of("com.example.j7m.modular.hello.api.ModularHelloService"), Map.of(), LookupOptions.defaults()).size());
            assertTrue(manager.unload(activation.instanceIdentity().instanceId()).success());
            assertEquals(0, manager.list(ApplicationQuery.all()).size());
            assertEquals(1, manager.historicalApplications().size());
            assertEquals("true", manager.historicalApplications().getFirst().metadata().get("managerReferencesReleased"));

            MiddlewareException failure = assertThrows(MiddlewareException.class, () -> manager.activate(
                    ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:9.9.9"),
                    new ApplicationActivationRequest("operator", "dev", false, Map.of())));
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, failure.code());
            ApplicationActivation failed = manager.list(new ApplicationQuery(null, ApplicationRunState.FAILED)).getFirst();
            assertEquals("FAILED", failed.metadata().get("lastActivationResult"));
            assertFalse(failed.metadata().get("safeMessage").contains("PRIVATE"));
            assertEquals("0", failed.metadata().get("partialServicesCleanedCount"));
        }
    }

    @Test
    void exportFailureAfterStartStopsApplicationBeforeCleanup() throws IOException {
        LocalApplicationRepository repository = repository("export-failure-repo");
        ApplicationBundle bundle = failingExportBundle();
        repository.publish(bundle, new RepositoryPublishOptions("test", false));
        Path marker = this.temporaryDirectory.resolve("export-failure-marker.txt");
        String previousMarker = System.getProperty("j7m.test.exportFailureMarker");
        System.setProperty("j7m.test.exportFailureMarker", marker.toString());
        try (DefaultMiddlewareRuntime runtime = runtime("phase54-export-failure")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            MiddlewareException failure = assertThrows(MiddlewareException.class, () -> manager.activate(bundle.coordinate(),
                    new ApplicationActivationRequest("operator", "dev", false, Map.of())));
            assertEquals(MiddlewareErrorCode.INTERNAL, failure.code());

            String lifecycle = Files.readString(marker, StandardCharsets.UTF_8);
            assertEquals("start\nstop\n", lifecycle);
            ApplicationActivation failed = manager.list(new ApplicationQuery(null, ApplicationRunState.FAILED)).getFirst();
            assertEquals("true", failed.metadata().get("lifecycleStoppedDuringRollback"));
            assertFalse(failed.metadata().get("transitionTimestamps").contains("RUNNING"));
            assertEquals(0, runtime.list("//apps/test/failing-export/Service",
                    List.of(FailingExportService.class.getName()), Map.of(), LookupOptions.defaults()).size());
        } finally {
            if (previousMarker == null) {
                System.clearProperty("j7m.test.exportFailureMarker");
            } else {
                System.setProperty("j7m.test.exportFailureMarker", previousMarker);
            }
        }
    }

    @Test
    void serviceIsNotDiscoverableWhileLifecycleStartIsBlocked() throws Exception {
        LocalApplicationRepository repository = repository("deferred-publication-repo");
        ApplicationBundle bundle = failingExportBundle();
        repository.publish(bundle, new RepositoryPublishOptions("test", false));
        Path marker = this.temporaryDirectory.resolve("deferred-publication-marker.txt");
        Path release = this.temporaryDirectory.resolve("deferred-publication-release.txt");
        String previousMarker = System.getProperty("j7m.test.exportFailureMarker");
        String previousRelease = System.getProperty("j7m.test.exportFailureRelease");
        System.setProperty("j7m.test.exportFailureMarker", marker.toString());
        System.setProperty("j7m.test.exportFailureRelease", release.toString());
        try (DefaultMiddlewareRuntime runtime = runtime("phase54-deferred-publication")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<MiddlewareException> activation = executor.submit(() -> assertThrows(MiddlewareException.class,
                        () -> manager.activate(bundle.coordinate(), new ApplicationActivationRequest("operator", "dev", false, Map.of()))));
                waitForMarker(marker);
                assertEquals(0, runtime.list("//apps/test/failing-export/Service",
                        List.of(FailingExportService.class.getName()), Map.of(), LookupOptions.defaults()).size());
                Files.writeString(release, "release\n", StandardCharsets.UTF_8);
                assertEquals(MiddlewareErrorCode.INTERNAL, activation.get(5L, TimeUnit.SECONDS).code());
                ApplicationActivation failed = manager.list(new ApplicationQuery(null, ApplicationRunState.FAILED)).getFirst();
                assertEquals("0", failed.metadata().get("partialServicesCleanedCount"));
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS));
            }
        } finally {
            restoreProperty("j7m.test.exportFailureMarker", previousMarker);
            restoreProperty("j7m.test.exportFailureRelease", previousRelease);
        }
    }

    @Test
    void partialPublicationIsRolledBackWhenLaterServiceExportFails() throws IOException {
        LocalApplicationRepository repository = repository("partial-publication-repo");
        ApplicationBundle bundle = partialExportBundle();
        repository.publish(bundle, new RepositoryPublishOptions("test", false));
        Path marker = this.temporaryDirectory.resolve("partial-publication-marker.txt");
        String previousMarker = System.getProperty("j7m.test.exportFailureMarker");
        System.setProperty("j7m.test.exportFailureMarker", marker.toString());
        try (DefaultMiddlewareRuntime runtime = runtime("phase54-partial-publication")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            MiddlewareException failure = assertThrows(MiddlewareException.class, () -> manager.activate(bundle.coordinate(),
                    new ApplicationActivationRequest("operator", "dev", false, Map.of())));
            assertEquals(MiddlewareErrorCode.INTERNAL, failure.code());

            ApplicationActivation failed = manager.list(new ApplicationQuery(null, ApplicationRunState.FAILED)).getFirst();
            assertEquals("1", failed.metadata().get("partialServicesCleanedCount"));
            assertEquals(0, runtime.list("//apps/test/failing-export/Good",
                    List.of(FailingExportService.class.getName()), Map.of(), LookupOptions.defaults()).size());
            assertEquals(0, runtime.list("//apps/test/failing-export/Service",
                    List.of(FailingExportService.class.getName()), Map.of(), LookupOptions.defaults()).size());
        } finally {
            restoreProperty("j7m.test.exportFailureMarker", previousMarker);
        }
    }

    @Test
    void preparedServiceIsNotDiscoverableWhileLaterServiceExportIsBlocked() throws Exception {
        LocalApplicationRepository repository = repository("prepared-publication-repo");
        ApplicationBundle bundle = blockedPartialExportBundle();
        repository.publish(bundle, new RepositoryPublishOptions("test", false));
        Path marker = this.temporaryDirectory.resolve("blocked-export-marker.txt");
        Path release = this.temporaryDirectory.resolve("blocked-export-release.txt");
        String previousMarker = System.getProperty("j7m.test.blockedExportMarker");
        String previousRelease = System.getProperty("j7m.test.blockedExportRelease");
        System.setProperty("j7m.test.blockedExportMarker", marker.toString());
        System.setProperty("j7m.test.blockedExportRelease", release.toString());
        try (DefaultMiddlewareRuntime runtime = runtime("phase54-prepared-publication")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<MiddlewareException> activation = executor.submit(() -> assertThrows(MiddlewareException.class,
                        () -> manager.activate(bundle.coordinate(), new ApplicationActivationRequest("operator", "dev", false, Map.of()))));
                waitForMarker(marker);

                assertEquals(0, runtime.list("//apps/test/failing-export/Good",
                        List.of(FailingExportService.class.getName()), Map.of(), LookupOptions.defaults()).size());
                MiddlewareException lookup = assertThrows(MiddlewareException.class, () -> runtime.lookup(
                        "//apps/test/failing-export/Good", FailingExportService.class, LookupOptions.defaults()));
                assertEquals(MiddlewareErrorCode.NOT_FOUND, lookup.code());

                Files.writeString(release, "release\n", StandardCharsets.UTF_8);
                assertEquals(MiddlewareErrorCode.INTERNAL, activation.get(5L, TimeUnit.SECONDS).code());
                ApplicationActivation failed = manager.list(new ApplicationQuery(null, ApplicationRunState.FAILED)).getFirst();
                assertEquals("1", failed.metadata().get("partialServicesCleanedCount"));
                assertFalse(failed.metadata().get("transitionTimestamps").contains("RUNNING"));
                assertEquals(0, runtime.list("//apps/test/failing-export/Good",
                        List.of(FailingExportService.class.getName()), Map.of(), LookupOptions.defaults()).size());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS));
            }
        } finally {
            restoreProperty("j7m.test.blockedExportMarker", previousMarker);
            restoreProperty("j7m.test.blockedExportRelease", previousRelease);
        }
    }

    @Test
    void preparedPublicationGroupRollsBackPriorRegistryBindWhenSecondCommitFails() throws Exception {
        LocalApplicationRepository repository = repository("prepared-publication-registry-repo");
        ApplicationBundle bundle = registryFailurePublicationBundle();
        repository.publish(bundle, new RepositoryPublishOptions("test", false));
        Path marker = this.temporaryDirectory.resolve("second-bind-marker.txt");
        Path release = this.temporaryDirectory.resolve("second-bind-release.txt");
        BlockingSecondBindRegistry registry = new BlockingSecondBindRegistry(marker, release);
        try (DefaultMiddlewareRuntime runtime = runtime("phase54-prepared-registry-publication", registry)) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<MiddlewareException> activation = executor.submit(() -> assertThrows(MiddlewareException.class,
                        () -> manager.activate(bundle.coordinate(), new ApplicationActivationRequest("operator", "dev", false, Map.of()))));
                waitForMarker(marker);
                assertEquals(0, manager.list(new ApplicationQuery(null, ApplicationRunState.RUNNING)).size());

                Future<List<ObjectRef>> visibleDuringFailure = executor.submit(() -> runtime.list(
                        "//apps/test/failing-export/Good", List.of(FailingExportService.class.getName()), Map.of(), LookupOptions.defaults()));
                assertThrows(TimeoutException.class, () -> visibleDuringFailure.get(100L, TimeUnit.MILLISECONDS));

                Files.writeString(release, "release\n", StandardCharsets.UTF_8);
                assertEquals(MiddlewareErrorCode.REGISTRY_UNAVAILABLE, activation.get(5L, TimeUnit.SECONDS).code());
                assertEquals(0, visibleDuringFailure.get(5L, TimeUnit.SECONDS).size());
                assertEquals(0, registry.activeRecordCount());
                ApplicationActivation failed = manager.list(new ApplicationQuery(null, ApplicationRunState.FAILED)).getFirst();
                assertFalse(failed.metadata().get("transitionTimestamps").contains("RUNNING"));
                assertEquals(0, runtime.list("//apps/test/failing-export/Good",
                        List.of(FailingExportService.class.getName()), Map.of(), LookupOptions.defaults()).size());
                MiddlewareException lookup = assertThrows(MiddlewareException.class, () -> runtime.lookup(
                        "//apps/test/failing-export/Good", FailingExportService.class, LookupOptions.defaults()));
                assertEquals(MiddlewareErrorCode.NOT_FOUND, lookup.code());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void classpathActivationRejectsHostMaskedApplicationClasses() throws IOException {
        LocalApplicationRepository repository = repository("host-masked-repo");
        ApplicationBundle bundle = failingExportBundleWithoutLibraries();
        repository.publish(bundle, new RepositoryPublishOptions("test", false));
        try (DefaultMiddlewareRuntime runtime = runtime("phase54-host-masked")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            MiddlewareException failure = assertThrows(MiddlewareException.class, () -> manager.activate(bundle.coordinate(),
                    new ApplicationActivationRequest("operator", "dev", false, Map.of())));

            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, failure.code());
            assertEquals(0, runtime.list("//apps/test/failing-export/Service",
                    List.of(FailingExportService.class.getName()), Map.of(), LookupOptions.defaults()).size());
        }
    }

    @Test
    void stopFailureDuringRollbackDoesNotOverwriteOriginalActivationFailure() throws IOException {
        LocalApplicationRepository repository = repository("export-stop-failure-repo");
        ApplicationBundle bundle = failingExportBundle();
        repository.publish(bundle, new RepositoryPublishOptions("test", false));
        Path marker = this.temporaryDirectory.resolve("export-stop-failure-marker.txt");
        String previousMarker = System.getProperty("j7m.test.exportFailureMarker");
        String previousStopFailure = System.getProperty("j7m.test.exportFailureStopFails");
        System.setProperty("j7m.test.exportFailureMarker", marker.toString());
        System.setProperty("j7m.test.exportFailureStopFails", "true");
        try (DefaultMiddlewareRuntime runtime = runtime("phase54-export-stop-failure")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            MiddlewareException failure = assertThrows(MiddlewareException.class, () -> manager.activate(bundle.coordinate(),
                    new ApplicationActivationRequest("operator", "dev", false, Map.of())));
            assertEquals(MiddlewareErrorCode.INTERNAL, failure.code());

            assertEquals("start\nstop\n", Files.readString(marker, StandardCharsets.UTF_8));
            ApplicationActivation failed = manager.list(new ApplicationQuery(null, ApplicationRunState.FAILED)).getFirst();
            assertEquals(MiddlewareErrorCode.INTERNAL.name(), failed.metadata().get("safeErrorCode"));
            assertEquals("operation failed", failed.metadata().get("safeMessage"));
            assertEquals("1", failed.metadata().get("stopCleanupFailureCount"));
            assertEquals("application-stop-failed", failed.metadata().get("stopCleanupFailureSummary"));
            assertFalse(failed.metadata().toString().contains("PRIVATE"));
            assertFalse("application stop failed during rollback".equals(failed.metadata().get("safeMessage")));
        } finally {
            restoreProperty("j7m.test.exportFailureMarker", previousMarker);
            restoreProperty("j7m.test.exportFailureStopFails", previousStopFailure);
        }
    }

    @Test
    void stopInterruptedFailureDuringRollbackRestoresInterruptStatus() throws IOException {
        Thread.interrupted();
        LocalApplicationRepository repository = repository("export-stop-interrupted-repo");
        ApplicationBundle bundle = failingExportBundle();
        repository.publish(bundle, new RepositoryPublishOptions("test", false));
        Path marker = this.temporaryDirectory.resolve("export-stop-interrupted-marker.txt");
        String previousMarker = System.getProperty("j7m.test.exportFailureMarker");
        String previousStopInterrupted = System.getProperty("j7m.test.exportFailureStopInterrupted");
        System.setProperty("j7m.test.exportFailureMarker", marker.toString());
        System.setProperty("j7m.test.exportFailureStopInterrupted", "true");
        try (DefaultMiddlewareRuntime runtime = runtime("phase54-export-stop-interrupted")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            MiddlewareException failure = assertThrows(MiddlewareException.class, () -> manager.activate(bundle.coordinate(),
                    new ApplicationActivationRequest("operator", "dev", false, Map.of())));
            assertEquals(MiddlewareErrorCode.INTERNAL, failure.code());

            assertEquals("start\nstop\n", Files.readString(marker, StandardCharsets.UTF_8));
            assertTrue(Thread.currentThread().isInterrupted());
            Thread.interrupted();
            ApplicationActivation failed = manager.list(new ApplicationQuery(null, ApplicationRunState.FAILED)).getFirst();
            assertEquals("1", failed.metadata().get("stopCleanupFailureCount"));
            assertEquals("application-stop-failed", failed.metadata().get("stopCleanupFailureSummary"));
            assertFalse(failed.metadata().toString().contains("fixture stop cleanup interrupted"));
            assertFalse(failed.metadata().toString().contains("InterruptedException"));
        } finally {
            restoreProperty("j7m.test.exportFailureMarker", previousMarker);
            restoreProperty("j7m.test.exportFailureStopInterrupted", previousStopInterrupted);
            Thread.interrupted();
        }
    }

    @Test
    void jpmsLifecycleSelectionUsesOnlyExactApplicationContextHook() {
        LocalApplicationRepository repository = repository("lifecycle-selection-repo");
        try (DefaultMiddlewareRuntime runtime = runtime("phase54-lifecycle-selection")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            Object context = applicationContextProxy();
            LifecycleFixtures.ExactAndObjectLifecycle exact = new LifecycleFixtures.ExactAndObjectLifecycle();

            invokeJpmsLifecycle(manager, exact, "start", context);

            assertEquals(1, exact.exactInvocations());
            assertEquals(0, exact.objectInvocations());
            MiddlewareException objectOnly = assertThrows(MiddlewareException.class,
                    () -> invokeJpmsLifecycle(manager, new LifecycleFixtures.ObjectOnlyLifecycle(), "start", context));
            assertEquals(MiddlewareErrorCode.METHOD_NOT_FOUND, objectOnly.code());
            assertFalse(objectOnly.getMessage().contains("ObjectOnlyLifecycle"));
            MiddlewareException nonVoid = assertThrows(MiddlewareException.class,
                    () -> invokeJpmsLifecycle(manager, new LifecycleFixtures.NonVoidLifecycle(), "start", context));
            assertEquals(MiddlewareErrorCode.METHOD_NOT_FOUND, nonVoid.code());
            assertFalse(nonVoid.getMessage().contains("NonVoidLifecycle"));
        }
    }

    @Test
    void jpmsLifecycleSelectionRejectsLookalikeContextAndMapsHookFailureSafely() throws IOException {
        LocalApplicationRepository repository = repository("lifecycle-lookalike-repo");
        try (DefaultMiddlewareRuntime runtime = runtime("phase54-lifecycle-lookalike");
                DuplicateContextProxy duplicate = duplicateApplicationContextProxy(this.temporaryDirectory.resolve("duplicate-context"))) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            Object context = applicationContextProxy();

            MiddlewareException lookalike = assertThrows(MiddlewareException.class,
                    () -> invokeJpmsLifecycle(manager, new LifecycleFixtures.LookalikeContextLifecycle(), "start", context));
            assertEquals(MiddlewareErrorCode.METHOD_NOT_FOUND, lookalike.code());
            assertFalse(lookalike.getMessage().contains("LookalikeContextLifecycle"));

            MiddlewareException duplicateContext = assertThrows(MiddlewareException.class,
                    () -> invokeJpmsLifecycle(manager, new LifecycleFixtures.ExactAndObjectLifecycle(), "start", duplicate.proxy()));
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, duplicateContext.code());
            assertFalse(duplicateContext.getMessage().contains("ApplicationContext"));

            MiddlewareException thrown = assertThrows(MiddlewareException.class,
                    () -> invokeJpmsLifecycle(manager, new LifecycleFixtures.ThrowingLifecycle(), "start", context));
            assertEquals(MiddlewareErrorCode.INTERNAL, thrown.code());
            assertFalse(thrown.getMessage().contains("PRIVATE"));
            assertFalse(thrown.getMessage().contains("token"));

            Thread.interrupted();
            MiddlewareException interrupted = assertThrows(MiddlewareException.class,
                    () -> invokeJpmsLifecycle(manager, new LifecycleFixtures.InterruptedLifecycle(), "start", context));
            assertEquals(MiddlewareErrorCode.INTERNAL, interrupted.code());
            assertTrue(Thread.currentThread().isInterrupted());
            Thread.interrupted();

            assertThrows(LifecycleFixtures.ControlledVirtualMachineError.class,
                    () -> invokeJpmsLifecycle(manager, new LifecycleFixtures.FatalStopLifecycle(), "stop", context));

            LifecycleFixtures.BridgeLifecycle bridge = new LifecycleFixtures.BridgeLifecycle();
            invokeJpmsLifecycle(manager, bridge, "bridge", context);
            assertEquals(1, bridge.invocations());
        }
    }

    @Test
    void jpmsLifecycleAmbiguityProbeIsDeterministicForReflectionShape() throws Exception {
        LocalApplicationRepository repository = repository("lifecycle-ambiguity-repo");
        try (DefaultMiddlewareRuntime runtime = runtime("phase54-lifecycle-ambiguity")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            Object context = applicationContextProxy();
            LifecycleFixtures.AmbiguousIfReflectionExposesInheritedLifecycle fixture =
                    new LifecycleFixtures.AmbiguousIfReflectionExposesInheritedLifecycle();
            int exactCandidates = exactLifecycleCandidateCount(manager, fixture, "ambiguous", ApplicationContext.class);

            if (exactCandidates > 1) {
                MiddlewareException ambiguous = assertThrows(MiddlewareException.class,
                        () -> invokeJpmsLifecycle(manager, fixture, "ambiguous", context));
                assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, ambiguous.code());
                assertFalse(ambiguous.getMessage().contains("AmbiguousIfReflectionExposesInheritedLifecycle"));
            } else {
                assertEquals(1, exactCandidates);
                invokeJpmsLifecycle(manager, fixture, "ambiguous", context);
            }
        }
    }

    @Test
    void registrationCleanupContinuesAfterCloseFailuresAndPreservesInterrupt() throws Exception {
        Thread.interrupted();
        LocalApplicationRepository repository = repository("registration-cleanup-repo");
        try (DefaultMiddlewareRuntime runtime = runtime("phase54-registration-cleanup")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            Object activation = newManagedActivation(runtime);
            Field registrationsField = activation.getClass().getDeclaredField("registrations");
            registrationsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<ObjectRegistration<?>> registrations = (List<ObjectRegistration<?>>) registrationsField.get(activation);
            CountingRegistration first = new CountingRegistration("first", new RuntimeException("close failed",
                    new InterruptedException("interrupted")));
            CountingRegistration second = new CountingRegistration("second", null);
            CountingRegistration third = new CountingRegistration("third", new RuntimeException("second failure"));
            registrations.add(first);
            registrations.add(second);
            registrations.add(third);

            invokeCloseRegistrations(manager, activation);

            assertEquals(1, first.closeCount());
            assertEquals(1, second.closeCount());
            assertEquals(1, third.closeCount());
            assertTrue(registrations.isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
            Thread.interrupted();
            ApplicationActivation snapshot = activationSnapshot(activation);
            assertEquals("3", snapshot.metadata().get("partialServicesCleanedCount"));
            assertEquals("2", snapshot.metadata().get("registrationCleanupFailureCount"));
            assertEquals("registration-close-failed-partial", snapshot.metadata().get("registrationCleanupFailureSummary"));
            assertFalse(snapshot.metadata().toString().contains("interrupted"));

            invokeCloseRegistrations(manager, activation);
            ApplicationActivation repeated = activationSnapshot(activation);
            assertEquals("3", repeated.metadata().get("partialServicesCleanedCount"));
            assertEquals("2", repeated.metadata().get("registrationCleanupFailureCount"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void kernelEncodedSizeAccountingRejectsUnsupportedPayloadWithoutCallingToString() throws Exception {
        try (DefaultMiddlewareRuntime runtime = runtime("phase54-encoded-size")) {
            Method estimate = DefaultMiddlewareRuntime.class.getDeclaredMethod("estimateEncodedBytes", EncodedValue.class);
            estimate.setAccessible(true);
            AtomicInteger toStringCalls = new AtomicInteger();
            Object hostile = new Object() {
                @Override
                public String toString() {
                    toStringCalls.incrementAndGet();
                    throw new AssertionError("encoded size accounting must not call application toString");
                }
            };
            EncodedValue encoded = new EncodedValue("map", Map.class.getName(), Map.of("payload", hostile), Map.of());

            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> estimate.invoke(runtime, encoded));

            assertTrue(failure.getCause() instanceof MiddlewareException);
            assertEquals(0, toStringCalls.get());
        }
    }

    @Test
    void kernelEncodedSizeAccountingRejectsCyclicCollectionsAndOversizedCollections() throws Exception {
        try (DefaultMiddlewareRuntime runtime = runtime("phase54-encoded-cycle")) {
            Method estimate = DefaultMiddlewareRuntime.class.getDeclaredMethod("estimateEncodedBytes", EncodedValue.class);
            estimate.setAccessible(true);
            List<Object> cyclicList = new java.util.ArrayList<>();
            cyclicList.add(cyclicList);
            Map<String, Object> cyclicMap = new java.util.LinkedHashMap<>();
            cyclicMap.put("self", cyclicMap);
            List<String> oversized = new java.util.ArrayList<>(4097);
            for (int index = 0; index < 4097; index++) {
                oversized.add("x");
            }

            InvocationTargetException listFailure = assertThrows(InvocationTargetException.class,
                    () -> estimate.invoke(runtime, new EncodedValue("list", List.class.getName(), cyclicList, Map.of())));
            assertTrue(listFailure.getCause() instanceof MiddlewareException);
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, ((MiddlewareException) listFailure.getCause()).code());

            InvocationTargetException mapFailure = assertThrows(InvocationTargetException.class,
                    () -> estimate.invoke(runtime, new EncodedValue("map", Map.class.getName(), cyclicMap, Map.of())));
            assertTrue(mapFailure.getCause() instanceof MiddlewareException);
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, ((MiddlewareException) mapFailure.getCause()).code());

            InvocationTargetException boundFailure = assertThrows(InvocationTargetException.class,
                    () -> estimate.invoke(runtime, new EncodedValue("list", List.class.getName(), oversized, Map.of())));
            assertTrue(boundFailure.getCause() instanceof MiddlewareException);
            assertEquals(MiddlewareErrorCode.OVERLOADED, ((MiddlewareException) boundFailure.getCause()).code());
        }
    }

    @Test
    void extractionCleanupPreservesRetryStateWhenBoundIsExceeded() throws Exception {
        ApplicationPlatformLimits defaults = ApplicationPlatformLimits.defaults();
        ApplicationPlatformLimits tight = new ApplicationPlatformLimits(
                defaults.maxBundleBytes(),
                defaults.maxCompressedBytes(),
                defaults.maxEntryExpandedBytes(),
                defaults.maxAggregateExpandedBytes(),
                defaults.maxMetadataBytes(),
                defaults.maxManifestBytes(),
                defaults.maxChecksumBytes(),
                defaults.maxSignatureBytes(),
                2,
                defaults.maxEntryNameBytes(),
                defaults.maxEntryPathDepth(),
                defaults.maxCompressionRatio(),
                defaults.ioBufferBytes(),
                defaults.maxAuditEventsPerPage(),
                defaults.maxAuditLineBytes(),
                defaults.maxReplicationChunks());
        LocalApplicationRepository repository = repository("cleanup-bound-repo");
        try (DefaultMiddlewareRuntime runtime = runtime("phase54-cleanup-bound")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository, tight);
            Object activation = newManagedActivation(runtime);
            Path extracted = this.temporaryDirectory.resolve("cleanup-bound").resolve("extracted");
            Files.createDirectories(extracted);
            Files.writeString(extracted.resolve("one.txt"), "1", StandardCharsets.UTF_8);
            Files.writeString(extracted.resolve("two.txt"), "2", StandardCharsets.UTF_8);
            Field extractedField = activation.getClass().getDeclaredField("extractedDirectory");
            extractedField.setAccessible(true);
            extractedField.set(activation, extracted);

            invokeReleaseManagerReferences(manager, activation);

            ApplicationActivation snapshot = activationSnapshot(activation);
            assertEquals("bounded-retry-required", snapshot.metadata().get("cleanupDeleteStatus"));
            assertEquals("<redacted>", snapshot.metadata().get("extractedDirectory"));
            assertTrue(Files.exists(extracted));
        }
    }

    @Test
    void allUpgradeStrategiesAndRollbackPathsAreSupported() {
        assertStrategy(UpgradeOptions.stopStart(), 1);
        assertStrategy(UpgradeOptions.rolling(), 1);
        assertStrategy(UpgradeOptions.blueGreen(), 2);
    }

    @Test
    void canaryUnsupportedAndUnknownStrategyFailSafely() {
        LocalApplicationRepository repository = repository("canary-failure-repo");
        repository.publish(modularBundle("1.0.0"), new RepositoryPublishOptions("test", false));
        repository.publish(modularBundle("1.1.0"), new RepositoryPublishOptions("test", false));
        try (DefaultMiddlewareRuntime runtime = runtime("phase54-canary-fail")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            DefaultApplicationDeployer deployer = new DefaultApplicationDeployer(repository, manager);
            ApplicationActivation activation = deployer.activate(ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.0.0"),
                    new ApplicationActivationRequest("operator", "dev", false, Map.of()));
            ApplicationDeploymentResult failedCanary = deployer.upgrade(activation.instanceIdentity().instanceId(),
                    ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.1.0"),
                    new UpgradeOptions("canary-baseline", 1, 1, 2, true));
            assertFalse(failedCanary.success());
            assertTrue(failedCanary.details().contains("reason=unsupported-no-probe-boundary"));
            assertEquals("1.0.0", deployer.listActiveApplications(new ApplicationQuery(null, ApplicationRunState.RUNNING))
                    .getFirst().instanceIdentity().application().coordinate().version().value());
            MiddlewareException unknown = assertThrows(MiddlewareException.class, () -> deployer.upgrade(activation.instanceIdentity().instanceId(),
                    ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.1.0"), new UpgradeOptions("mystery")));
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, unknown.code());
        }
    }

    @Test
    void adminStatusExposesLifecycleRepositoryAndUpgradeFields() {
        LocalApplicationRepository repository = repository("status-repo");
        repository.publish(modularBundle("1.0.0"), new RepositoryPublishOptions("test", false));
        repository.publish(modularBundle("1.1.0"), new RepositoryPublishOptions("test", false));
        try (DefaultMiddlewareRuntime runtime = runtime("phase54-status")) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            ApplicationActivation activation = manager.activate(ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.0.0"),
                    new ApplicationActivationRequest("operator", "dev", false, Map.of()));
            manager.upgrade(activation.instanceIdentity().instanceId(),
                    ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.1.0"), UpgradeOptions.rolling(),
                    new ApplicationActivationRequest("operator", "dev", false, Map.of()));
            Map<String, String> status = manager.applicationStatus();
            assertEquals("2", status.get("installedApplications"));
            assertEquals("true", status.get("repositoryAuditChainValid"));
            assertEquals("1", status.get("activeApplicationInstances"));
            assertEquals("1", status.get("upgradeHistoryCount"));
            assertEquals("built-in-ed25519", status.get("repositorySignatureStatus"));
            assertFalse(status.toString().contains("PRIVATE"));
        }
    }

    private void assertStrategy(final UpgradeOptions options, final int expectedRunningAfterUpgrade) {
        LocalApplicationRepository repository = repository("strategy-" + options.strategy());
        repository.publish(modularBundle("1.0.0"), new RepositoryPublishOptions("test", false));
        repository.publish(modularBundle("1.1.0"), new RepositoryPublishOptions("test", false));
        try (DefaultMiddlewareRuntime runtime = runtime("phase54-" + options.strategy())) {
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            DefaultApplicationDeployer deployer = new DefaultApplicationDeployer(repository, manager);
            ApplicationActivation baseline = deployer.activate(ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.0.0"),
                    new ApplicationActivationRequest("operator", "dev", false, Map.of()));
            ApplicationDeploymentResult upgrade = deployer.upgrade(baseline.instanceIdentity().instanceId(),
                    ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.1.0"), options);
            assertTrue(upgrade.success(), options.strategy());
            assertEquals(expectedRunningAfterUpgrade, deployer.listActiveApplications(new ApplicationQuery(null, ApplicationRunState.RUNNING)).size());
            ApplicationActivation selected = deployer.listActiveApplications(new ApplicationQuery(null, ApplicationRunState.RUNNING))
                    .stream()
                    .filter(candidate -> "true".equals(candidate.metadata().get("selectedActiveVersion")))
                    .findFirst().orElseThrow();
            assertEquals("1.1.0", selected.instanceIdentity().application().coordinate().version().value());
            assertTrue(manager.upgradeHistory().stream().anyMatch(entry -> entry.contains(options.strategy().replace("-baseline", ""))));
            assertTrue(deployer.rollback(selected.instanceIdentity().instanceId(), new RollbackOptions("")).success());
            ApplicationActivation restored = deployer.listActiveApplications(new ApplicationQuery(null, ApplicationRunState.RUNNING))
                    .stream()
                    .filter(candidate -> "true".equals(candidate.metadata().get("selectedActiveVersion")))
                    .findFirst().orElseThrow();
            assertEquals("1.0.0", restored.instanceIdentity().application().coordinate().version().value());
            assertTrue(manager.auditEvents().stream().anyMatch(event -> event.startsWith("application.rollback")));
        }
    }

    private LocalApplicationRepository repository(final String name) {
        return new LocalApplicationRepository(this.temporaryDirectory.resolve(name));
    }

    private ApplicationBundle modularBundle(final String version) {
        return new J7mBundleWriter().write(modularManifest(version),
                this.temporaryDirectory.resolve("modular-" + version + "-" + java.util.UUID.randomUUID() + ".j7m"),
                List.of(), List.of(modularApiJar(), modularImplJar()), List.of(modularReadme()));
    }

    private ApplicationBundle badModularBundle() throws IOException {
        String text = Files.readString(modularManifest("1.1.0"), StandardCharsets.UTF_8)
                .replace("com.example.j7m.modular.hello.impl.ModularHelloApplication", "java.lang.String")
                .replace("\"version\": \"1.1.0\"", "\"version\": \"9.9.9\"");
        Path manifest = this.temporaryDirectory.resolve("bad-gate4-manifest.json");
        Files.writeString(manifest, text, StandardCharsets.UTF_8);
        return new J7mBundleWriter().write(manifest,
                this.temporaryDirectory.resolve("modular-bad-gate4.j7m"),
                List.of(), List.of(modularApiJar(), modularImplJar()), List.of(modularReadme()));
    }

    private ApplicationBundle failingExportBundle() throws IOException {
        return failingExportBundle(List.of(failingExportJar()));
    }

    private ApplicationBundle failingExportBundleWithoutLibraries() throws IOException {
        return failingExportBundle(List.of());
    }

    private ApplicationBundle failingExportBundle(final List<Path> libraries) throws IOException {
        return exportFailureBundle(libraries, """
                    {
                      "serviceName": "//apps/test/failing-export/Service",
                      "interfaceName": "org.j7m.middleware.tests.fixtures.FailingExportService",
                      "implementationClass": "org.j7m.middleware.tests.fixtures.FailingExportServiceImpl",
                      "transport": "grpc",
                      "globallyExported": false,
                      "metadata": {
                        "domain": "test"
                      }
                    }
                """);
    }

    private ApplicationBundle partialExportBundle() throws IOException {
        return exportFailureBundle(List.of(failingExportJar()), """
                    {
                      "serviceName": "//apps/test/failing-export/Good",
                      "interfaceName": "org.j7m.middleware.tests.fixtures.FailingExportService",
                      "implementationClass": "org.j7m.middleware.tests.fixtures.PassingExportServiceImpl",
                      "transport": "grpc",
                      "globallyExported": false,
                      "metadata": {
                        "domain": "test"
                      }
                    },
                    {
                      "serviceName": "//apps/test/failing-export/Service",
                      "interfaceName": "org.j7m.middleware.tests.fixtures.FailingExportService",
                      "implementationClass": "org.j7m.middleware.tests.fixtures.FailingExportServiceImpl",
                      "transport": "grpc",
                      "globallyExported": false,
                      "metadata": {
                        "domain": "test"
                      }
                    }
                """);
    }

    private ApplicationBundle blockedPartialExportBundle() throws IOException {
        return exportFailureBundle(List.of(failingExportJar()), """
                    {
                      "serviceName": "//apps/test/failing-export/Good",
                      "interfaceName": "org.j7m.middleware.tests.fixtures.FailingExportService",
                      "implementationClass": "org.j7m.middleware.tests.fixtures.PassingExportServiceImpl",
                      "transport": "grpc",
                      "globallyExported": false,
                      "metadata": {
                        "domain": "test"
                      }
                    },
                    {
                      "serviceName": "//apps/test/failing-export/Service",
                      "interfaceName": "org.j7m.middleware.tests.fixtures.FailingExportService",
                      "implementationClass": "org.j7m.middleware.tests.fixtures.BlockingFailingExportServiceImpl",
                      "transport": "grpc",
                      "globallyExported": false,
                      "metadata": {
                        "domain": "test"
                      }
                    }
                """);
    }

    private ApplicationBundle registryFailurePublicationBundle() throws IOException {
        return exportFailureBundle(List.of(failingExportJar()), """
                    {
                      "serviceName": "//apps/test/failing-export/Good",
                      "interfaceName": "org.j7m.middleware.tests.fixtures.FailingExportService",
                      "implementationClass": "org.j7m.middleware.tests.fixtures.PassingExportServiceImpl",
                      "transport": "grpc",
                      "globallyExported": true,
                      "metadata": {
                        "domain": "test"
                      }
                    },
                    {
                      "serviceName": "//apps/test/failing-export/Later",
                      "interfaceName": "org.j7m.middleware.tests.fixtures.FailingExportService",
                      "implementationClass": "org.j7m.middleware.tests.fixtures.PassingExportServiceImpl",
                      "transport": "grpc",
                      "globallyExported": true,
                      "metadata": {
                        "domain": "test"
                      }
                    }
                """);
    }

    private ApplicationBundle exportFailureBundle(final List<Path> libraries, final String servicesJson) throws IOException {
        Path manifest = this.temporaryDirectory.resolve("failing-export-manifest.json");
        Files.writeString(manifest, """
                {
                  "schemaVersion": "j7m.application/v1",
                  "application": {
                    "group": "org.j7m.tests",
                    "name": "failing-export",
                    "version": "1.0.0",
                    "displayName": "Failing Export Test",
                    "description": "Activation rollback test"
                  },
                  "runtime": {
                    "minJ7mVersion": "0.7.0",
                    "javaVersion": "25",
                    "moduleMode": "CLASSPATH",
                    "bootstrapClass": "org.j7m.middleware.tests.fixtures.FailingExportApplication"
                  },
                  "services": [
                %s
                  ],
                  "security": {
                    "trustDomain": "dev",
                    "confidential": false
                  },
                  "resources": {
                    "cpu": "1",
                    "memory": "128MiB",
                    "maxConcurrentInvocations": 8,
                    "maxInboundPayload": "1MiB"
                  }
                }
                """.formatted(servicesJson), StandardCharsets.UTF_8);
        return new J7mBundleWriter().write(manifest, this.temporaryDirectory.resolve("failing-export.j7m"),
                libraries, List.of());
    }

    private Path failingExportJar() throws IOException {
        Path jar = this.temporaryDirectory.resolve("failing-export-fixture.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            addFixtureClass(output, FailingExportApplication.class);
            addFixtureClass(output, FailingExportService.class);
            addFixtureClass(output, FailingExportServiceImpl.class);
            addFixtureClass(output, BlockingFailingExportServiceImpl.class);
            addFixtureClass(output, PassingExportServiceImpl.class);
        }
        return jar;
    }

    private static void addFixtureClass(final JarOutputStream output, final Class<?> type) throws IOException {
        String entryName = type.getName().replace('.', '/') + ".class";
        URL resource = type.getClassLoader().getResource(entryName);
        if (resource == null) {
            throw new IllegalStateException("missing fixture class resource " + entryName);
        }
        output.putNextEntry(new JarEntry(entryName));
        try (InputStream input = resource.openStream()) {
            input.transferTo(output);
        }
        output.closeEntry();
    }

    private static void restoreProperty(final String name, final String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }

    private static void waitForMarker(final Path marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (!Files.exists(marker)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("lifecycle marker was not written");
            }
            Thread.sleep(10L);
        }
    }

    private static void writeMarker(final Path marker) {
        try {
            Files.writeString(marker, "marker\n", StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to write marker", exception);
        }
    }

    private static void waitForRelease(final Path release) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (!Files.exists(release)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("release marker was not written");
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("release wait interrupted", exception);
            }
        }
    }

    private static Object applicationContextProxy() {
        return Proxy.newProxyInstance(
                ApplicationContext.class.getClassLoader(),
                new Class<?>[] {ApplicationContext.class},
                (Object proxy, Method method, Object[] args) -> defaultValue(method.getReturnType()));
    }

    private static DuplicateContextProxy duplicateApplicationContextProxy(final Path directory) throws IOException {
        Path source = directory.resolve("src").resolve("org").resolve("j7m").resolve("middleware")
                .resolve("application").resolve("api").resolve("ApplicationContext.java");
        Path classes = directory.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, """
                package org.j7m.middleware.application.api;

                public interface ApplicationContext {
                }
                """, StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("system Java compiler is unavailable");
        }
        int result = compiler.run(
                null,
                null,
                null,
                "-d",
                classes.toString(),
                source.toString());
        if (result != 0) {
            throw new IOException("failed to compile duplicate application context fixture");
        }
        URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, null);
        try {
            Class<?> duplicateContext = Class.forName(ApplicationContext.class.getName(), true, loader);
            Object proxy = Proxy.newProxyInstance(
                    loader,
                    new Class<?>[] {duplicateContext},
                    (Object value, Method method, Object[] args) -> defaultValue(method.getReturnType()));
            return new DuplicateContextProxy(proxy, loader);
        } catch (ClassNotFoundException | RuntimeException exception) {
            loader.close();
            throw new IOException("failed to create duplicate application context fixture", exception);
        }
    }

    private static Object defaultValue(final Class<?> type) {
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        if (type == float.class) {
            return Float.valueOf(0.0F);
        }
        if (type == double.class) {
            return Double.valueOf(0.0D);
        }
        return null;
    }

    private static void invokeJpmsLifecycle(
            final ApplicationManager manager,
            final Object application,
            final String methodName,
            final Object context) {
        try {
            Method method = ApplicationManager.class.getDeclaredMethod(
                    "invokeJpmsLifecycle", Object.class, String.class, Object.class);
            method.setAccessible(true);
            method.invoke(manager, application, methodName, context);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static int exactLifecycleCandidateCount(
            final ApplicationManager manager,
            final Object application,
            final String methodName,
            final Class<?> contextType) throws Exception {
        Method selector = ApplicationManager.class.getDeclaredMethod(
                "isExactJpmsLifecycleCandidate", Method.class, String.class, Class.class, Object.class);
        selector.setAccessible(true);
        int count = 0;
        for (Method method : application.getClass().getMethods()) {
            Boolean selected = (Boolean) selector.invoke(manager, method, methodName, contextType, application);
            if (selected.booleanValue()) {
                count++;
            }
        }
        return count;
    }

    private static Object newManagedActivation(final DefaultMiddlewareRuntime runtime) throws Exception {
        Class<?> activationType = Class.forName("org.j7m.middleware.deployer.ApplicationManager$ManagedActivation");
        Constructor<?> constructor = activationType.getDeclaredConstructor(ActivationIdentity.class, ApplicationInstanceIdentity.class);
        constructor.setAccessible(true);
        ApplicationCoordinate coordinate = new ApplicationCoordinate(
                new ApplicationId("org.j7m.tests", "cleanup"), new ApplicationVersion("1.0.0"));
        ApplicationIdentity application = new ApplicationIdentity(coordinate, "Cleanup", "dev");
        ApplicationInstanceIdentity instance = new ApplicationInstanceIdentity(
                new ApplicationInstanceId("app-cleanup"), application, runtime.identity(), "CLASSPATH");
        return constructor.newInstance(new ActivationIdentity("activation-cleanup"), instance);
    }

    private static void invokeCloseRegistrations(final ApplicationManager manager, final Object activation) throws Exception {
        Method method = ApplicationManager.class.getDeclaredMethod("closeRegistrations", activation.getClass());
        method.setAccessible(true);
        method.invoke(manager, activation);
    }

    private static void invokeReleaseManagerReferences(final ApplicationManager manager, final Object activation) throws Exception {
        Method method = ApplicationManager.class.getDeclaredMethod("releaseManagerReferences", activation.getClass());
        method.setAccessible(true);
        method.invoke(manager, activation);
    }

    private static ApplicationActivation activationSnapshot(final Object activation) throws Exception {
        Method method = activation.getClass().getDeclaredMethod("snapshot");
        method.setAccessible(true);
        return (ApplicationActivation) method.invoke(activation);
    }

    private static final class CountingRegistration implements ObjectRegistration<Object> {
        private final ObjectRef ref;
        private final RuntimeException failure;
        private final AtomicInteger closeCount;

        private CountingRegistration(final String id, final RuntimeException failure) {
            this.ref = new ObjectRef(
                    "object-" + id,
                    "",
                    "//apps/test/cleanup/" + id,
                    "runtime-cleanup",
                    "default",
                    List.of(),
                    "local",
                    "",
                    0L,
                    0L,
                    Map.of());
            this.failure = failure;
            this.closeCount = new AtomicInteger();
        }

        @Override
        public ObjectRef ref() {
            return this.ref;
        }

        @Override
        public Object instance() {
            return this;
        }

        @Override
        public InstanceStatus status() {
            return new InstanceStatus(this.ref, ObjectState.EXECUTED, RegistrationState.LOCAL);
        }

        @Override
        public void close() {
            this.closeCount.incrementAndGet();
            if (this.failure != null) {
                throw this.failure;
            }
        }

        private int closeCount() {
            return this.closeCount.get();
        }
    }

    private static final class BlockingSecondBindRegistry implements RegistryClient {
        private final EmbeddedRegistryClient delegate;
        private final Path marker;
        private final Path release;
        private final AtomicInteger bindCount;

        private BlockingSecondBindRegistry(final Path marker, final Path release) {
            this.delegate = new EmbeddedRegistryClient(new EmbeddedRegistryStore(), java.time.Clock.systemUTC());
            this.marker = marker;
            this.release = release;
            this.bindCount = new AtomicInteger();
        }

        @Override
        public void bind(final RegistryRecord record) {
            int count = this.bindCount.incrementAndGet();
            if (count == 2) {
                writeMarker(this.marker);
                waitForRelease(this.release);
                throw MiddlewareException.of(MiddlewareErrorCode.REGISTRY_UNAVAILABLE, "test registry second bind failure");
            }
            this.delegate.bind(record);
        }

        @Override
        public void unbind(final ObjectRef ref) {
            this.delegate.unbind(ref);
        }

        @Override
        public Optional<RegistryRecord> lookup(final ObjectRef ref) {
            return this.delegate.lookup(ref);
        }

        @Override
        public List<RegistryRecord> list(final RegistryQuery query) {
            return this.delegate.list(query);
        }

        @Override
        public void heartbeat(final ObjectRef ref, final long leaseUntil) {
            this.delegate.heartbeat(ref, leaseUntil);
        }

        @Override
        public List<RegistryPeerStatus> peers(final PeerQuery query) {
            return this.delegate.peers(query);
        }

        @Override
        public void close() {
            this.delegate.close();
        }

        private int activeRecordCount() {
            return this.delegate.activeRecordCount();
        }
    }

    private record DuplicateContextProxy(Object proxy, URLClassLoader loader) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            this.loader.close();
        }
    }

    private DefaultMiddlewareRuntime runtime(final String id) {
        return runtime(id, new EmbeddedRegistryClient(new EmbeddedRegistryStore(), java.time.Clock.systemUTC()));
    }

    private DefaultMiddlewareRuntime runtime(final String id, final RegistryClient registry) {
        return new MiddlewareRuntimeBuilder()
                .runtimeId(id)
                .registry(registry)
                .addTransport(new GrpcTransportProvider())
                .disableNuggetAdmin()
                .build();
    }

    private Path modularManifest(final String version) {
        if ("1.1.0".equals(version)) {
            return projectPath("examples/modular-deployable-application/META-INF/j7m-application-1.1.0.json");
        }
        return projectPath("examples/modular-deployable-application/META-INF/j7m-application.json");
    }

    private Path modularApiJar() {
        return projectPath("examples/modular-deployable-application/api/build/libs/com.example.j7m.modular.hello.api.jar");
    }

    private Path modularImplJar() {
        return projectPath("examples/modular-deployable-application/impl/build/libs/com.example.j7m.modular.hello.impl.jar");
    }

    private Path modularReadme() {
        return projectPath("examples/modular-deployable-application/README.md");
    }

    private Path projectPath(final String relative) {
        Path path = Path.of(relative);
        if (Files.exists(path)) {
            return path;
        }
        Path parent = Path.of("..").resolve(relative).normalize();
        if (Files.exists(parent)) {
            return parent;
        }
        throw new IllegalStateException("missing project path " + relative);
    }
}
