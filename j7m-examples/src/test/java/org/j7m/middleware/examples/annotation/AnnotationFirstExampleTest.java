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

package org.j7m.middleware.examples.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.j7m.middleware.annotations.Drain;
import org.j7m.middleware.annotations.Initialize;
import org.j7m.middleware.annotations.Shutdown;
import org.j7m.middleware.annotations.Startup;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRegistration;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.api.messaging.DurableQueue;
import org.j7m.middleware.api.saga.SagaService;
import org.j7m.middleware.application.api.AnnotatedApplicationDescriptor;
import org.j7m.middleware.application.api.ApplicationContext;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationIdentity;
import org.j7m.middleware.application.api.ApplicationInstanceId;
import org.j7m.middleware.application.api.ApplicationInstanceIdentity;
import org.j7m.middleware.application.api.AuditPublisher;
import org.j7m.middleware.application.api.ConfigurationView;
import org.j7m.middleware.application.api.InvocationApplicationContext;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.InvocationSecurityContext;
import org.j7m.middleware.application.api.J7mContext;
import org.j7m.middleware.application.api.MetricsPublisher;
import org.j7m.middleware.application.api.ServiceExporter;
import org.j7m.middleware.application.components.AnnotationModelProcessor;
import org.j7m.middleware.application.components.DefaultJ7mInjector;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.ResourceUsageSnapshot;
import org.j7m.middleware.security.api.TrustDomain;
import org.junit.jupiter.api.Test;

final class AnnotationFirstExampleTest {
    @Test
    void everyPublicSimplifyingAnnotationHasExampleCoverageAndMatrixRow() throws Exception {
        Path root = repositoryRoot();
        Path annotationRoot = root.resolve("j7m-api/src/main/java/org/j7m/middleware/annotations");
        List<String> annotations = annotationNames(annotationRoot);

        String source = exampleSource(root)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
        String matrix = Files.readString(root.resolve("docs/internal/01000 - Normative Specifications/annotation-coverage.md"), StandardCharsets.UTF_8);

        assertTrue(annotations.size() >= 55, "public annotation inventory unexpectedly shrank");
        for (String annotation : annotations) {
            assertTrue(source.matches("(?s).*(^|[\\s(,])@" + java.util.regex.Pattern.quote(annotation) + "\\b.*"),
                    "missing concrete example usage for @" + annotation);
            assertTrue(matrix.contains("| @" + annotation + " |"), "missing matrix row for @" + annotation);
        }
        assertFalse(matrix.contains("| BLOCKED |"));
        assertFalse(matrix.contains("ACCEPT_WITH_LIMITATIONS"));
    }

    @Test
    void annotationInventoryIncludesManagedAnnotationsAndExcludesHelperEnum() throws Exception {
        List<String> actual = annotationNames(repositoryRoot().resolve("j7m-api/src/main/java/org/j7m/middleware/annotations"));
        assertFalse(actual.contains("BindingRefresh"), "An enum cannot have annotation syntax usage");
        assertTrue(actual.containsAll(List.of("ConfigProperties", "Produces", "Qualifier", "SchemaType", "Scope", "ServiceRef", "WireField", "CircuitBreaker", "Bulkhead", "Observed", "Deduplicated", "IdempotencyKey", "ResourceTransaction", "JdbcTransaction", "PartitionHandler", "EventSubscription", "LeaseGuard", "LeaderTask", "QueuePublisher", "ReconciliationHandler", "DurableJob", "Schedule")));
        assertTrue(actual.size() >= 80, "Real annotations must not disappear when excluding helper types");
    }

    private static List<String> annotationNames(Path root) throws Exception {
        List<String> result = new ArrayList<>();
        try (var paths = Files.list(root)) {
            for (Path path : paths.filter(file -> file.getFileName().toString().endsWith(".java")).toList()) {
                String name = path.getFileName().toString().replace(".java", "");
                // Helper types share this package; loading metadata must not initialize their code.
                Class<?> type = Class.forName("org.j7m.middleware.annotations." + name, false, org.j7m.middleware.annotations.Inject.class.getClassLoader());
                if (type.isAnnotation() && java.lang.reflect.Modifier.isPublic(type.getModifiers())) result.add(name);
            }
        }
        result.sort(String::compareTo); return List.copyOf(result);
    }

    @Test
    void annotationMetadataIsDiscoveredForApplicationAndServices() {
        AnnotationModelProcessor processor = new AnnotationModelProcessor();

        AnnotatedApplicationDescriptor descriptor = processor.describeApplication(AnnotationExampleApplication.class, List.of(
                AnnotationSimpleService.class,
                AnnotationSecureService.class));

        assertEquals("org.j7m.examples", descriptor.group());
        assertEquals("annotation-first", descriptor.name());
        assertEquals(2, descriptor.services().size());
        assertTrue(descriptor.services().stream().anyMatch(service ->
                service.serviceName().equals("//apps/example/annotation/simple/GreetingService")
                        && service.methods().stream().anyMatch(method -> method.methodName().equals("greet")
                        && method.idempotent()
                        && method.metadata().get("deadlineMillis").equals("250")
                        && method.metadata().get("retryMaxAttempts").equals("1"))));
        assertTrue(descriptor.services().stream().anyMatch(service ->
                service.serviceName().equals("//apps/example/annotation/secure/ApprovalService")
                        && service.methods().stream().anyMatch(method -> method.requiredRole().equals("billing-approver")
                        && method.requiredPermission().equals("approve"))));
    }

    @Test
    void constructorInjectionNamedConfigAndLifecycleWorkInExampleClasses() {
        AnnotationExampleAudit audit = new AnnotationExampleAudit();
        DefaultJ7mInjector injector = baseInjector(audit, principal("alice", "billing-approver")).build();
        AnnotationSimpleService service = injector.instantiate(AnnotationSimpleService.class);

        injector.invokeLifecycle(service, Initialize.class);
        injector.invokeLifecycle(service, Startup.class);
        String greeting = service.greet("phase611");
        injector.invokeLifecycle(service, Drain.class);
        injector.invokeLifecycle(service, Shutdown.class);

        assertEquals("formal:eu:hello phase611", greeting);
        assertEquals(List.of("initialize", "startup", "drain", "shutdown"), service.lifecycleEvents());
        assertTrue(service.ready());
        assertEquals("healthy", service.health());
        assertFalse(audit.summary().contains("J7M_SENTINEL_SECRET_TOKEN"));
    }

    @Test
    void currentApplicationPrincipalAndInvocationAreInjectedSafely() {
        AnnotationExampleAudit audit = new AnnotationExampleAudit();
        Principal principal = principal("alice", "billing-approver");

        try (J7mContext.Scope ignored = J7mContext.open(context(principal, "corr-annotation"))) {
            DefaultJ7mInjector serviceInjector = baseInjector(audit, principal).build();
            AnnotationSimpleService simpleService = serviceInjector.instantiate(AnnotationSimpleService.class);
            DefaultJ7mInjector injector = baseInjector(audit, principal)
                    .bind(AnnotationSimpleService.class, simpleService)
                    .build();
            AnnotationExampleApplication application = injector.instantiate(AnnotationExampleApplication.class);
            AnnotationSecureService service = injector.instantiate(AnnotationSecureService.class);

            injector.invokeLifecycle(application, Startup.class);
            AnnotationApprovalResult result = service.approve("invoice-1", "4200");

            assertTrue(application.started());
            assertEquals("org.j7m.examples:annotation-first:1.0.0", application.applicationCoordinate());
            assertEquals("APPROVED", result.status());
            assertTrue(audit.summary().contains("corr-annotation"));
            assertFalse(audit.summary().contains("4200"));
        }

        assertEquals("", J7mContext.currentInvocation().correlationId());
    }

    @Test
    void deniedSecureCallUsesStableErrorAndSafeAudit() {
        AnnotationExampleAudit audit = new AnnotationExampleAudit();
        Principal principal = principal("mallory", "viewer");

        try (J7mContext.Scope ignored = J7mContext.open(context(principal, "corr-denied"))) {
            DefaultJ7mInjector injector = baseInjector(audit, principal).build();
            AnnotationSecureService service = injector.instantiate(AnnotationSecureService.class);

            MiddlewareException exception = assertThrows(MiddlewareException.class,
                    () -> service.approve("invoice-2", "9900"));

            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, exception.code());
            assertTrue(audit.summary().contains("secure.denied"));
            assertFalse(audit.summary().contains("9900"));
        }
    }

    @Test
    void sagaAndQueueAnnotationsAreDescriptorOnlyAndUsePublicCapabilities() {
        AnnotationExampleAudit audit = new AnnotationExampleAudit();
        Principal principal = principal("alice", "billing-approver");

        try (J7mContext.Scope ignored = J7mContext.open(context(principal, "corr-saga"))) {
            DefaultJ7mInjector injector = baseInjector(audit, principal).build();
            AnnotationSagaWorkflow saga = injector.instantiate(AnnotationSagaWorkflow.class);
            AnnotationQueueConsumer queue = injector.instantiate(AnnotationQueueConsumer.class);

            assertEquals(List.of("reserved:invoice-3", "released:invoice-3", "dead-letter:invoice-3", "saga-service=SagaService"),
                    saga.runScenario("invoice-3", true));
            assertEquals("ACK:event-1", queue.consume("event-1"));
            assertEquals("DEAD_LETTER:event-2", queue.deadLetter("event-2"));
            assertTrue(audit.summary().contains("saga.compensate.reserve"));
            assertTrue(audit.summary().contains("queue.consume"));
        }
    }

    private static DefaultJ7mInjector.Builder baseInjector(
            final AnnotationExampleAudit audit,
            final Principal principal) {
        return DefaultJ7mInjector.builder()
                .configuration(ConfigurationView.of(Map.of(
                        "annotation.region", "eu",
                        "apiKey", "J7M_SENTINEL_SECRET_TOKEN")))
                .applicationContext(applicationContext(principal))
                .bind(AnnotationGreetingFormatter.class, "formal", new AnnotationGreetingFormatter("formal"))
                .bind(AnnotationExampleAudit.class, audit)
                .bind(DurableQueue.class, "annotation-events", proxy(DurableQueue.class))
                .bind(SagaService.class, "annotation-saga", proxy(SagaService.class));
    }

    private static Principal principal(final String id, final String role) {
        return new Principal(id, id, Set.of(role), Set.of());
    }

    private static InvocationContext context(final Principal principal, final String correlationId) {
        return new InvocationContext(
                new InvocationSecurityContext(principal, principal.roles(), new TrustDomain("dev")),
                InvocationApplicationContext.empty(),
                correlationId);
    }

    private static ApplicationContext applicationContext(final Principal principal) {
        ApplicationCoordinate coordinate = ApplicationCoordinate.parse("org.j7m.examples:annotation-first:1.0.0");
        ApplicationIdentity identity = new ApplicationIdentity(coordinate, "Annotation First", "dev");
        RuntimeIdentity runtime = new RuntimeIdentity("annotation-test-runtime", "dev", Map.of());
        ApplicationInstanceIdentity instance = new ApplicationInstanceIdentity(
                new ApplicationInstanceId("annotation-test-instance"), identity, runtime, "CLASSPATH");
        return new ApplicationContext() {
            @Override
            public ApplicationIdentity applicationIdentity() {
                return identity;
            }

            @Override
            public ApplicationInstanceIdentity instanceIdentity() {
                return instance;
            }

            @Override
            public Principal activationPrincipal() {
                return principal;
            }

            @Override
            public RuntimeIdentity runtimeIdentity() {
                return runtime;
            }

            @Override
            public ConfigurationView configuration() {
                return ConfigurationView.of(Map.of("annotation.region", "eu"));
            }

            @Override
            public ServiceExporter services() {
                return new ServiceExporter() {
                    @Override
                    public <T> ObjectRegistration<T> export(
                            final String serviceName,
                            final T instance,
                            final Class<T> api,
                            final ExportOptions options) {
                        throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "example exporter is not active in tests");
                    }
                };
            }

            @Override
            public AuditPublisher audit() {
                return AuditPublisher.noop();
            }

            @Override
            public MetricsPublisher metrics() {
                return MetricsPublisher.noop();
            }

            @Override
            public ResourceUsageSnapshot resources() {
                return new ResourceUsageSnapshot(0, 0, 0, 0, 0);
            }
        };
    }

    private static <T> T proxy(final Class<T> type) {
        InvocationHandler handler = (final Object ignoredProxy, final Method method, final Object[] args) -> {
            if (method.getName().equals("toString")) {
                return "annotation-example-" + type.getSimpleName();
            }
            if (method.getName().equals("close")) {
                return null;
            }
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "example capability is descriptor-only");
        };
        Object capabilityProxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
        return type.cast(capabilityProxy);
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (current.getFileName() != null && current.getFileName().toString().equals("j7m-examples")) {
            return current.getParent();
        }
        return current;
    }

    private static String exampleSource(final Path root) throws Exception {
        StringBuilder source = new StringBuilder();
        List<Path> roots = List.of(
                root.resolve("examples/templates/annotation-first-service/src/main/java"),
                root.resolve("examples/annotation-first-simple-service/src/main/java"),
                root.resolve("examples/annotation-first-secure-service/src/main/java"),
                root.resolve("examples/annotation-first-remote-invocation/src/main/java"),
                root.resolve("examples/annotation-first-security-audit/src/main/java"),
                root.resolve("examples/annotation-first-saga-workflow/src/main/java"),
                root.resolve("examples/annotation-first-durable-queue-consumer/src/main/java"),
                root.resolve("examples/annotation-first-lifecycle/src/main/java"),
                root.resolve("examples/annotation-first-isolation-profile/src/main/java"),
                root.resolve("examples/annotation-first-observability/src/main/java"),
                root.resolve("j7m-examples/src/main/java/org/j7m/middleware/examples/annotation"),
                root.resolve("j7m-demo/src/main/java/org/j7m/middleware/demo/annotation"));
        for (Path candidate : roots) {
            if (!Files.isDirectory(candidate)) {
                continue;
            }
            try (java.util.stream.Stream<Path> paths = Files.walk(candidate)) {
                paths.filter(path -> path.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .forEach(path -> {
                            try {
                                source.append(Files.readString(path, StandardCharsets.UTF_8)).append('\n');
                            } catch (java.io.IOException exception) {
                                throw new IllegalStateException(exception);
                            }
                        });
            }
        }
        return source.toString();
    }
}
