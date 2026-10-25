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

package org.j7m.middleware.application.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.j7m.middleware.annotations.ConfigValue;
import org.j7m.middleware.annotations.CurrentApplication;
import org.j7m.middleware.annotations.CurrentInvocation;
import org.j7m.middleware.annotations.CurrentPrincipal;
import org.j7m.middleware.annotations.Deadline;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.J7mApplication;
import org.j7m.middleware.annotations.J7mComponent;
import org.j7m.middleware.annotations.J7mResource;
import org.j7m.middleware.annotations.J7mService;
import org.j7m.middleware.annotations.LocalOnly;
import org.j7m.middleware.annotations.Named;
import org.j7m.middleware.annotations.NonIdempotent;
import org.j7m.middleware.annotations.RemoteMethod;
import org.j7m.middleware.annotations.RequiresPermission;
import org.j7m.middleware.annotations.RequiresRole;
import org.j7m.middleware.annotations.Retry;
import org.j7m.middleware.annotations.Startup;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.AnnotatedApplicationDescriptor;
import org.j7m.middleware.application.api.ApplicationContext;
import org.j7m.middleware.application.api.ConfigurationView;
import org.j7m.middleware.application.api.InjectionGraphStatus;
import org.j7m.middleware.application.api.InvocationApplicationContext;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.InvocationSecurityContext;
import org.j7m.middleware.application.api.J7mContext;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.TrustDomain;
import org.junit.jupiter.api.Test;

final class J7mInjectionModelTest {
    @Test
    void constructorInjectionResolvesSafeCapabilitiesAndConfiguration() {
        QueueFacade queue = new QueueFacade("primary");
        DefaultJ7mInjector injector = DefaultJ7mInjector.builder()
                .configuration(ConfigurationView.of(Map.of("billing.region", "eu", "token", "J7M_SENTINEL_SECRET_TOKEN")))
                .bind(QueueFacade.class, "primary", queue)
                .build();

        BillingComponent component = injector.instantiate(BillingComponent.class);

        assertSame(queue, component.queue);
        assertEquals("eu", component.region);
        assertEquals("fallback", component.optional);
        assertTrue(injector.status().bindings().stream().anyMatch(key -> key.type() == QueueFacade.class));
    }

    @Test
    void duplicateCandidateFailsWithStableErrorAndSafeDiagnostic() {
        DefaultJ7mInjector injector = DefaultJ7mInjector.builder()
                .bind(QueueFacade.class, new QueueFacade("one"))
                .bind(QueueFacade.class, new QueueFacade("two"))
                .build();

        MiddlewareException exception = assertThrows(MiddlewareException.class, () -> injector.instantiate(DuplicateConsumer.class));

        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, exception.code());
        InjectionGraphStatus status = injector.status();
        assertTrue(status.diagnostics().stream().anyMatch(diagnostic -> diagnostic.reasonCode().equals("DUPLICATE_DEPENDENCY")));
        assertFalse(status.toString().contains("J7M_SENTINEL_SECRET_TOKEN"));
    }

    @Test
    void missingRequiredSecretConfigIsRedactedInDiagnostics() {
        DefaultJ7mInjector injector = DefaultJ7mInjector.builder()
                .configuration(ConfigurationView.of(Map.of()))
                .build();

        MiddlewareException exception = assertThrows(MiddlewareException.class, () -> injector.instantiate(SecretConfigConsumer.class));

        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, exception.code());
        assertFalse(injector.status().toString().contains("apiKey"));
        assertFalse(injector.status().toString().contains("J7M_SENTINEL_SECRET_TOKEN"));
    }

    @Test
    void lifecycleHookIsInvokedDeterministically() {
        DefaultJ7mInjector injector = DefaultJ7mInjector.builder().build();
        LifecycleComponent component = new LifecycleComponent();

        injector.invokeLifecycle(component, Startup.class);

        assertEquals("started", component.state);
        assertTrue(injector.status().diagnostics().stream().anyMatch(diagnostic -> diagnostic.reasonCode().equals("LIFECYCLE_INVOKED")));
    }

    @Test
    void invocationScopedContextAndPrincipalAreInjectedAndCleared() {
        Principal principal = new Principal("alice", "Alice", java.util.Set.of("billing-user"), java.util.Set.of());
        InvocationContext context = new InvocationContext(
                new InvocationSecurityContext(principal, principal.roles(), new TrustDomain("regulated")),
                InvocationApplicationContext.empty(),
                "corr-1");
        DefaultJ7mInjector injector = DefaultJ7mInjector.builder().build();

        try (J7mContext.Scope ignored = J7mContext.open(context)) {
            InvocationConsumer consumer = injector.instantiate(InvocationConsumer.class);
            assertEquals("alice", consumer.principal.id());
            assertEquals("corr-1", consumer.context.correlationId());
        }

        assertEquals("", J7mContext.currentInvocation().correlationId());
    }

    @Test
    void virtualThreadContextDoesNotLeakBetweenExecutions() throws Exception {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();

        Thread one = Thread.startVirtualThread(() -> {
            try (J7mContext.Scope ignored = J7mContext.open(new InvocationContext(null, null, "virtual-one"))) {
                first.set(J7mContext.currentInvocation().correlationId());
            }
        });
        Thread two = Thread.startVirtualThread(() -> second.set(J7mContext.currentInvocation().correlationId()));
        one.join();
        two.join();

        assertEquals("virtual-one", first.get());
        assertEquals("", second.get());
        assertEquals("", J7mContext.currentInvocation().correlationId());
    }

    @Test
    void annotationDescriptorCapturesSecurityIdempotencyAndDeadlineMetadata() {
        AnnotationModelProcessor processor = new AnnotationModelProcessor();

        AnnotatedApplicationDescriptor descriptor = processor.describeApplication(BillingApplication.class, java.util.List.of(BillingService.class));

        assertEquals("com.acme", descriptor.group());
        assertEquals(1, descriptor.services().size());
        assertEquals("//apps/acme/billing/BillingService", descriptor.services().getFirst().serviceName());
        assertEquals("approve", descriptor.services().getFirst().methods().getFirst().methodName());
        assertTrue(descriptor.services().getFirst().methods().getFirst().idempotent());
        assertEquals("billing-user", descriptor.services().getFirst().methods().getFirst().requiredRole());
        assertEquals("approve", descriptor.services().getFirst().methods().getFirst().requiredPermission());
        assertEquals("500", descriptor.services().getFirst().methods().getFirst().metadata().get("deadlineMillis"));
        assertEquals("2", descriptor.services().getFirst().methods().getFirst().metadata().get("retryMaxAttempts"));
    }

    @Test
    void invalidAnnotationCombinationsFailClearly() {
        AnnotationModelProcessor processor = new AnnotationModelProcessor();

        MiddlewareException exception = assertThrows(MiddlewareException.class, () -> processor.describeService(InvalidService.class));

        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, exception.code());
    }

    @J7mComponent
    static final class BillingComponent {
        final QueueFacade queue;
        final String region;
        final String optional;

        @Inject
        BillingComponent(
                @Named("primary") final QueueFacade queue,
                @ConfigValue("billing.region") final String region,
                @ConfigValue(value = "billing.optional", defaultValue = "fallback") final String optional) {
            this.queue = queue;
            this.region = region;
            this.optional = optional;
        }
    }

    static final class DuplicateConsumer {
        final QueueFacade queue;

        @Inject
        DuplicateConsumer(final QueueFacade queue) {
            this.queue = queue;
        }
    }

    static final class SecretConfigConsumer {
        @ConfigValue(value = "apiKey", required = true)
        String apiKey;
    }

    static final class LifecycleComponent {
        String state = "new";

        @Startup
        void start() {
            this.state = "started";
        }
    }

    static final class InvocationConsumer {
        final InvocationContext context;
        final Principal principal;

        @Inject
        InvocationConsumer(
                @CurrentInvocation final InvocationContext context,
                @CurrentPrincipal final Principal principal) {
            this.context = context;
            this.principal = principal;
        }
    }

    @J7mApplication(group = "com.acme", name = "billing", version = "1.0.0")
    static final class BillingApplication {
        @Inject
        @CurrentApplication
        ApplicationContext applicationContext;
    }

    @J7mService(name = "//apps/acme/billing/BillingService", exported = true, transport = "default")
    static final class BillingService {
        @RemoteMethod
        @Idempotent
        @RequiresRole("billing-user")
        @RequiresPermission(value = "approve", resource = "//apps/acme/billing")
        @Deadline(millis = 500)
        @Retry(maxAttempts = 2)
        String approve() {
            return "approved";
        }
    }

    @J7mService(name = "//apps/acme/billing/Invalid")
    static final class InvalidService {
        @RemoteMethod
        @LocalOnly
        @Idempotent
        @NonIdempotent
        String invalid() {
            return "invalid";
        }
    }

    static final class QueueFacade {
        final String name;

        QueueFacade(final String name) {
            this.name = name;
        }
    }
}
