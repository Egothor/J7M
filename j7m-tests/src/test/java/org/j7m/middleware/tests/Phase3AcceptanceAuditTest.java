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

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRegistration;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.security.api.Credential;
import org.j7m.middleware.security.api.ResourcePolicy;
import org.j7m.middleware.security.api.ResourcePolicyAction;
import org.j7m.middleware.security.api.ResourcePolicyDecision;
import org.j7m.middleware.security.api.ResourcePolicyRequest;
import org.j7m.middleware.security.api.SecurityConfiguration;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityMode;
import org.j7m.middleware.security.api.TransportSecurityLevel;
import org.j7m.middleware.security.defaults.AllowAllAuthorizationPolicy;
import org.j7m.middleware.security.defaults.DefaultRedactor;
import org.j7m.middleware.security.defaults.InMemoryAuditSink;
import org.j7m.middleware.security.defaults.MutableCredentialProvider;
import org.j7m.middleware.transport.api.MethodDescriptor;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;

/**
 * Final Phase 3 acceptance audit tests.
 */
public final class Phase3AcceptanceAuditTest {
    @Test
    public void resourcePolicyCanDenyExportAndAuditTheDecision() {
        InMemoryAuditSink audit = new InMemoryAuditSink(16);
        SecurityConfiguration security = security(audit, policyDenying(ResourcePolicyAction.EXPORT, false), 1024 * 1024, 1024 * 1024, 1024);
        try (DefaultMiddlewareRuntime runtime = runtime("resource-export-deny", new EmbeddedRegistryStore(), security)) {
            MiddlewareException failure = assertThrows(MiddlewareException.class,
                    () -> runtime.export("resource", new CountingServiceImpl(), CountingService.class, export()));

            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, failure.code());
            assertTrue(audit.recentEvents().stream().anyMatch(event -> event.reason().contains("resource-policy")));
            assertEquals("1", runtime.status().security().get("resourcePolicyDeniedCount"));
        }
    }

    @Test
    public void resourcePolicyCanDenyInvocationBeforeTargetExecution() {
        InMemoryAuditSink audit = new InMemoryAuditSink(32);
        SecurityConfiguration security = security(audit, policyDenying(ResourcePolicyAction.INVOKE, false), 1024 * 1024, 1024 * 1024, 1024);
        CountingServiceImpl target = new CountingServiceImpl();
        try (DefaultMiddlewareRuntime runtime = runtime("resource-invoke-deny", new EmbeddedRegistryStore(), security)) {
            ObjectRegistration<CountingService> registration = runtime.export("counting", target, CountingService.class, export());
            MethodDescriptor descriptor = new MethodDescriptor(CountingService.class.getName(), "touch", List.of(), Void.TYPE.getName(), null);

            MiddlewareException failure = assertThrows(MiddlewareException.class,
                    () -> runtime.dispatch(registration.ref(), descriptor, List.of(), InvocationOptions.defaults()));

            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, failure.code());
            assertEquals(0, target.invocations.get());
            assertTrue(audit.recentEvents().stream().anyMatch(event -> event.metadata().getOrDefault("resourceAction", "").equals("INVOKE")));
        }
    }

    @Test
    public void resourcePolicyCanDenyCallbackAutoExport() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        SecurityConfiguration serverSecurity = security(new InMemoryAuditSink(16), allowPolicy(), 1024 * 1024, 1024 * 1024, 1024);
        InMemoryAuditSink clientAudit = new InMemoryAuditSink(16);
        SecurityConfiguration clientSecurity = security(clientAudit, policyDenying(ResourcePolicyAction.CALLBACK_EXPORT, false), 1024 * 1024, 1024 * 1024, 1024);
        try (DefaultMiddlewareRuntime server = runtime("callback-server", store, serverSecurity);
             DefaultMiddlewareRuntime client = runtime("callback-client", store, clientSecurity)) {
            server.export("callback", new CallbackServiceImpl(), CallbackService.class, export());
            CallbackService service = client.lookup("callback", CallbackService.class, LookupOptions.defaults());

            MiddlewareException failure = assertThrows(MiddlewareException.class, () -> service.register(message -> { }));

            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, failure.code());
            assertTrue(clientAudit.recentEvents().stream().anyMatch(event -> event.metadata().getOrDefault("resourceAction", "").equals("CALLBACK_EXPORT")));
        }
    }

    @Test
    public void outboundOversizeResultIsRejectedSafely() {
        InMemoryAuditSink audit = new InMemoryAuditSink(32);
        SecurityConfiguration security = security(audit, allowPolicy(), 1024 * 1024, 32, 1024);
        try (DefaultMiddlewareRuntime runtime = runtime("outbound-limit", new EmbeddedRegistryStore(), security)) {
            ObjectRegistration<LargeResultService> registration = runtime.export("large", new LargeResultServiceImpl(), LargeResultService.class, export());
            MethodDescriptor descriptor = new MethodDescriptor(LargeResultService.class.getName(), "large", List.of(), String.class.getName(), null);

            MiddlewareException failure = assertThrows(MiddlewareException.class,
                    () -> runtime.dispatch(registration.ref(), descriptor, List.of(), InvocationOptions.defaults()));

            assertEquals(MiddlewareErrorCode.OVERLOADED, failure.code());
            assertEquals("1", runtime.status().security().get("outboundLimitRejectedCount"));
            assertFalse(failure.getMessage().contains("secret"));
            assertTrue(audit.recentEvents().stream().anyMatch(event -> event.reason().equals("outbound-payload-limit")));
        }
    }

    @Test
    public void concurrentInvocationLimitRejectsAdditionalCallAndAuditsOverload() throws Exception {
        InMemoryAuditSink audit = new InMemoryAuditSink(32);
        SecurityConfiguration security = security(audit, allowPolicy(), 1024 * 1024, 1024 * 1024, 1);
        BlockingServiceImpl service = new BlockingServiceImpl();
        try (DefaultMiddlewareRuntime runtime = runtime("concurrency-limit", new EmbeddedRegistryStore(), security);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ObjectRegistration<BlockingService> registration = runtime.export("blocking", service, BlockingService.class, export());
            MethodDescriptor descriptor = new MethodDescriptor(BlockingService.class.getName(), "block", List.of(), Void.TYPE.getName(), null);
            Future<?> first = executor.submit(() -> runtime.dispatch(registration.ref(), descriptor, List.of(), InvocationOptions.defaults()));
            assertTrue(service.entered.await(2L, TimeUnit.SECONDS));

            MiddlewareException failure = assertThrows(MiddlewareException.class,
                    () -> runtime.dispatch(registration.ref(), descriptor, List.of(), InvocationOptions.defaults()));

            service.release.countDown();
            first.get(2L, TimeUnit.SECONDS);
            assertEquals(MiddlewareErrorCode.OVERLOADED, failure.code());
            assertEquals("1", runtime.status().security().get("overloadRejectedCount"));
            assertTrue(audit.recentEvents().stream().anyMatch(event -> event.reason().equals("runtime-invocation-limit")));
        }
    }

    @Test
    public void statusExposesSafeHardeningFieldsOnly() {
        InMemoryAuditSink audit = new InMemoryAuditSink(16);
        SecurityConfiguration security = new SecurityConfiguration(SecurityMode.PRODUCTION, new AllowAllAuthorizationPolicy(), audit,
                new DefaultRedactor(), false, true, 128, 256, 4, Duration.ofSeconds(2L),
                new MutableCredentialProvider(new Credential("test", "safe-version", Map.of("secret", "must-not-leak"))),
                true, Duration.ofSeconds(5L), 16, true, allowPolicy());
        try (DefaultMiddlewareRuntime runtime = runtime("status-hardening", new EmbeddedRegistryStore(), security)) {
            Map<String, String> status = runtime.status().security();

            assertEquals("PRODUCTION", status.get("mode"));
            assertEquals("allow-resource", status.get("resourcePolicy"));
            assertEquals("128", status.get("maxInboundMessageBytes"));
            assertEquals("256", status.get("maxOutboundMessageBytes"));
            assertEquals("4", status.get("maxConcurrentInvocations"));
            assertTrue(status.containsKey("activeInvocations"));
            assertFalse(status.toString().contains("must-not-leak"));
        }
    }

    private static DefaultMiddlewareRuntime runtime(final String id, final EmbeddedRegistryStore store, final SecurityConfiguration security) {
        return new MiddlewareRuntimeBuilder()
                .runtimeId(id)
                .registry(new EmbeddedRegistryClient(store, Clock.systemUTC()))
                .security(security)
                .addTransport(new GrpcTransportProvider())
                .disableNuggetAdmin()
                .build();
    }

    private static SecurityConfiguration security(
            final InMemoryAuditSink audit,
            final ResourcePolicy resourcePolicy,
            final int maxInbound,
            final int maxOutbound,
            final int maxConcurrent) {
        return new SecurityConfiguration(SecurityMode.DEV, new AllowAllAuthorizationPolicy(), audit, new DefaultRedactor(), false,
                true, maxInbound, maxOutbound, maxConcurrent, Duration.ofSeconds(2L),
                new MutableCredentialProvider(new Credential("test", "1", Map.of())), false, Duration.ofSeconds(30L), 64, false, resourcePolicy);
    }

    private static ResourcePolicy allowPolicy() {
        return new ResourcePolicy() {
            @Override
            public String id() {
                return "allow-resource";
            }

            @Override
            public ResourcePolicyDecision evaluate(final SecurityContext context, final ResourcePolicyRequest request) {
                return ResourcePolicyDecision.allow(this.id());
            }
        };
    }

    private static ResourcePolicy policyDenying(final ResourcePolicyAction deniedAction, final boolean overloaded) {
        return new ResourcePolicy() {
            @Override
            public String id() {
                return overloaded ? "test-overload-resource" : "test-deny-resource";
            }

            @Override
            public ResourcePolicyDecision evaluate(final SecurityContext context, final ResourcePolicyRequest request) {
                if (request.action() == deniedAction) {
                    return overloaded
                            ? ResourcePolicyDecision.overload(this.id(), "resource-policy-overload")
                            : ResourcePolicyDecision.deny(this.id(), "resource-policy-deny");
                }
                return ResourcePolicyDecision.allow(this.id());
            }
        };
    }

    private static ExportOptions export() {
        return new ExportOptions(false, "grpc", "default", List.of(), Map.of(), Duration.ofSeconds(30L), Duration.ofSeconds(10L));
    }

    /**
     * Counting service API.
     */
    public interface CountingService {
        /**
         * Increments a counter.
         */
        void touch();
    }

    private static final class CountingServiceImpl implements CountingService {
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public void touch() {
            this.invocations.incrementAndGet();
        }
    }

    /**
     * Callback service API.
     */
    public interface CallbackService {
        /**
         * Registers listener.
         */
        void register(Listener listener);
    }

    /**
     * Listener API.
     */
    public interface Listener {
        /**
         * Receives message.
         */
        void onMessage(String message);
    }

    private static final class CallbackServiceImpl implements CallbackService {
        @Override
        public void register(final Listener listener) {
            listener.onMessage("registered");
        }
    }

    /**
     * Large result API.
     */
    public interface LargeResultService {
        /**
         * Returns a large value.
         */
        String large();
    }

    private static final class LargeResultServiceImpl implements LargeResultService {
        @Override
        public String large() {
            return "secret-value-that-is-too-large-for-the-outbound-limit";
        }
    }

    /**
     * Blocking API.
     */
    public interface BlockingService {
        /**
         * Blocks until released.
         */
        void block();
    }

    private static final class BlockingServiceImpl implements BlockingService {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void block() {
            this.entered.countDown();
            try {
                if (!this.release.await(2L, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
    }
}
