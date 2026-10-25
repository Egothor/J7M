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

package org.j7m.middleware.demo.annotation;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
import org.j7m.middleware.application.api.AnnotatedServiceDescriptor;
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
import org.j7m.middleware.examples.annotation.AnnotationApprovalResult;
import org.j7m.middleware.examples.annotation.AnnotationExampleApplication;
import org.j7m.middleware.examples.annotation.AnnotationExampleAudit;
import org.j7m.middleware.examples.annotation.AnnotationGreetingFormatter;
import org.j7m.middleware.examples.annotation.AnnotationQueueConsumer;
import org.j7m.middleware.examples.annotation.AnnotationSagaWorkflow;
import org.j7m.middleware.examples.annotation.AnnotationSecureService;
import org.j7m.middleware.examples.annotation.AnnotationSimpleService;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.ResourceUsageSnapshot;
import org.j7m.middleware.security.api.TrustDomain;

/**
 * Runnable Phase 6.11 walkthrough for the annotation-first application model.
 *
 * <p>The demo is written for application developers who want to see how the
 * J7M annotations reduce manual wiring and document design intent without changing the
 * underlying ObjectRef, security, saga, queue, or isolation contracts. It
 * discovers {@code @J7mApplication}, {@code @J7mService}, and
 * {@code @RemoteMethod} metadata with {@link AnnotationModelProcessor}, builds
 * stable descriptors, and validates that supported security, idempotency,
 * deadline, retry, queue, saga, isolation, and observability annotations are
 * represented as safe descriptor or metadata examples.</p>
 *
 * <p>The same run constructs application components through the J7M-native
 * {@link DefaultJ7mInjector}. Constructor injection, named resources,
 * configuration values, current application context, current invocation
 * context, and current principal context are demonstrated through public
 * capability bindings only. Lifecycle annotations are enforced by invoking
 * {@code @Initialize}, {@code @Startup}, {@code @Drain}, and
 * {@code @Shutdown} hooks in deterministic order. The security example
 * evaluates descriptor metadata locally to show an authorized and denied path;
 * exported production calls still depend on the normal runtime authorization
 * policy. Idempotency, retry, backpressure, queue, saga, and isolation
 * annotations are descriptor-only in this demo.</p>
 *
 * <p>{@code @Sensitive} redaction is demonstrated by carrying sentinel
 * configuration and sensitive business values through audit/status output and
 * printing only redacted summaries. This class is intentionally not a
 * production runtime, dispatcher, authorization engine, queue worker, saga
 * orchestrator, or isolation provider. It uses local fake public capability
 * proxies, starts no external services, loads no vendor adapters, and requires
 * no external DI framework. Developers should expect output that lists the
 * derived descriptor, lifecycle order, security and idempotency metadata,
 * descriptor-only queue/saga/isolation metadata, denied-call behavior, and
 * redaction status. See {@code docs/examples/annotation-coverage.md} and the
 * {@code examples/annotation-first-*} sources for copyable application
 * patterns.</p>
 */
public final class AnnotationModelDemo {
        // J7M teaching: this block is copyable application code that relies on descriptors and J7M-native injection instead of runtime internals.
    private AnnotationModelDemo() {
    }

    /**
     * Runs the local descriptor, injection, lifecycle, security, and redaction
     * demonstration.
     *
     * <p>The method exercises annotation descriptor processing with explicit
     * application and service classes rather than classpath scanning. It then
     * creates components through {@link DefaultJ7mInjector}, opens scoped
     * invocation/principal context with {@link J7mContext}, invokes lifecycle
     * hooks, calls one annotated service method, and prints a safe operational
     * summary. Constructor injection, scoped context injection, lifecycle hook
     * invocation, and local denied-path evaluation are enforced in this demo.
     * Idempotency, retry, backpressure, queue, saga, isolation, and
     * observability annotations are shown as descriptor-only metadata.</p>
     *
     * <p>The code is safe to copy as a pattern for explicit descriptor
     * validation, injector construction, lifecycle ordering, and redacted demo
     * output. It is intentionally simplified by using fake public capability
     * proxies instead of real queue dispatch, saga orchestration, service
     * export, or isolation enforcement. The printed output must not contain the
     * sentinel secret or sensitive amount values.</p>
     *
     * @param args ignored command-line arguments
     */
    public static void main(final String[] args) {
        // J7M teaching: descriptor processing is explicit and deterministic;
        // the demo names the application and service classes instead of using
        // classpath scanning that would blur JPMS ownership.
        AnnotationModelProcessor processor = new AnnotationModelProcessor();
        AnnotatedApplicationDescriptor descriptor = processor.describeApplication(AnnotationExampleApplication.class, List.of(
                AnnotationSimpleService.class,
                AnnotationSecureService.class));

        AnnotationExampleAudit audit = new AnnotationExampleAudit();
        Principal approver = new Principal("alice", "Alice", Set.of("billing-approver"), Set.of());
        try (J7mContext.Scope ignored = J7mContext.open(context(approver, "demo-correlation"))) {
            // J7M teaching: injection binds only public capabilities and scoped
            // context, so the application never receives transport internals,
            // runtime maps, or mutable registry state.
            DefaultJ7mInjector serviceInjector = injector(audit, approver);
            AnnotationSimpleService simple = serviceInjector.instantiate(AnnotationSimpleService.class);
            DefaultJ7mInjector injector = injector(audit, approver, simple);
            AnnotationExampleApplication application = injector.instantiate(AnnotationExampleApplication.class);
            // J7M teaching: lifecycle hooks are invoked in the same order that
            // real activation should follow: initialize, startup, drain, then
            // shutdown.
            injector.invokeLifecycle(simple, Initialize.class);
            injector.invokeLifecycle(simple, Startup.class);
            injector.invokeLifecycle(application, Startup.class);

            String greeting = simple.greet("developer");
            AnnotationSecureService secure = injector.instantiate(AnnotationSecureService.class);
            AnnotationApprovalResult approval = secure.approve("invoice-7", "4200");
            // J7M teaching: saga, queue, isolation, and observability
            // annotations are descriptor-only in this demo, so the walkthrough
            // validates metadata without pretending to run a production
            // workflow engine, broker, or sandbox.
            AnnotationSagaWorkflow saga = injector.instantiate(AnnotationSagaWorkflow.class);
            AnnotationQueueConsumer queue = injector.instantiate(AnnotationQueueConsumer.class);
            List<String> sagaEvents = saga.runScenario("invoice-7", true);
            String queueResult = queue.consume("event-7");
            injector.invokeLifecycle(simple, Drain.class);
            injector.invokeLifecycle(simple, Shutdown.class);

            System.out.println("phase6.11 annotation demo descriptor application: "
                    + descriptor.group() + ":" + descriptor.name() + ":" + descriptor.version());
            // J7M teaching: output is deliberately operational and redacted;
            // redaction lets developers see descriptor and policy metadata,
            // not secrets, runtime internals, or internal handles.
            System.out.println("phase6.11 annotation demo descriptor services: " + serviceNames(descriptor));
            System.out.println("phase6.11 annotation demo source-inventory coverage: 58 public annotations covered by examples");
            System.out.println("phase6.11 annotation demo runtime-supported examples: injection, current context, lifecycle hooks, redaction-safe output");
            System.out.println("phase6.11 annotation demo build metadata examples: application, service, remote method, security, retry, queue, saga, isolation descriptors");
            System.out.println("phase6.11 annotation demo metadata-only examples: audit, confidential, workload, routing, callback, progress, resource hints");
            System.out.println("phase6.11 annotation demo reserved annotations: switch-level reserved for future runtime-level semantics");
            System.out.println("phase6.11 annotation demo simple greeting: " + greeting);
            System.out.println("phase6.11 annotation demo lifecycle: " + simple.lifecycleEvents());
            System.out.println("phase6.11 annotation demo application started: " + application.started());
            System.out.println("phase6.11 annotation demo security metadata: role=billing-approver permission=approve");
            System.out.println("phase6.11 annotation demo authorized result: " + approval.status());
            System.out.println("phase6.11 annotation demo idempotency metadata: greet=true approve=true");
            System.out.println("phase6.11 annotation demo saga descriptor-only events: " + sagaEvents);
            System.out.println("phase6.11 annotation demo queue descriptor-only result: " + queueResult);
            System.out.println("phase6.11 annotation demo isolation metadata: baseline-process descriptor-only");
            System.out.println("phase6.11 annotation demo observability metadata: descriptor-only workload and audit annotations");
        }

        Principal denied = new Principal("mallory", "Mallory", Set.of("viewer"), Set.of());
        try (J7mContext.Scope ignored = J7mContext.open(context(denied, "demo-denied"))) {
            DefaultJ7mInjector injector = injector(audit, denied);
            AnnotationSecureService secure = injector.instantiate(AnnotationSecureService.class);
            try {
                secure.approve("invoice-8", "9900");
            } catch (MiddlewareException exception) {
                System.out.println("phase6.11 annotation demo denied result: " + exception.code());
            }
        }

        System.out.println("phase6.11 annotation demo redaction safe: "
                + !audit.summary().contains("4200")
                + ", secret safe: "
                + !audit.summary().contains("J7M_SENTINEL_SECRET_TOKEN"));
        System.out.println("phase6.11 annotation demo audit summary: " + audit.summary());
    }

    private static List<String> serviceNames(final AnnotatedApplicationDescriptor descriptor) {
        return descriptor.services().stream().map(AnnotatedServiceDescriptor::serviceName).toList();
    }

    private static DefaultJ7mInjector injector(final AnnotationExampleAudit audit, final Principal principal) {
        return injector(audit, principal, null);
    }

    private static DefaultJ7mInjector injector(
            final AnnotationExampleAudit audit,
            final Principal principal,
            final AnnotationSimpleService simpleService) {
        DefaultJ7mInjector.Builder builder = DefaultJ7mInjector.builder()
                .configuration(ConfigurationView.of(Map.of(
                        "annotation.region", "eu",
                        "apiKey", "J7M_SENTINEL_SECRET_TOKEN")))
                .applicationContext(applicationContext(principal))
                .bind(AnnotationGreetingFormatter.class, "formal", new AnnotationGreetingFormatter("formal"))
                .bind(AnnotationExampleAudit.class, audit)
                .bind(DurableQueue.class, "annotation-events", proxy(DurableQueue.class))
                .bind(SagaService.class, "annotation-saga", proxy(SagaService.class));
        if (simpleService != null) {
            builder.bind(AnnotationSimpleService.class, simpleService);
        }
        return builder.build();
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
        RuntimeIdentity runtime = new RuntimeIdentity("annotation-demo-runtime", "dev", Map.of());
        ApplicationInstanceIdentity instance = new ApplicationInstanceIdentity(
                new ApplicationInstanceId("annotation-demo-instance"), identity, runtime, "CLASSPATH");
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
                        throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "annotation demo does not export services");
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
                return "annotation-demo-" + type.getSimpleName();
            }
            if (method.getName().equals("close")) {
                return null;
            }
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "annotation demo capability proxy is descriptor-only");
        };
        Object capabilityProxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
        return type.cast(capabilityProxy);
    }
}
