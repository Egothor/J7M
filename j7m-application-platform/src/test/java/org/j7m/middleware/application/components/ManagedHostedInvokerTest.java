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

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.InvocationSecurityContext;
import org.j7m.middleware.application.api.J7mContext;
import org.j7m.middleware.application.api.ManagedMethod;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.TrustDomain;
import org.j7m.middleware.transport.api.InvocationBoundary;
import org.junit.jupiter.api.Test;

class ManagedHostedInvokerTest {
    @Test void trustedEncodedInputMeasurementPrecedesDecoderAndIgnoresForgedMetadata() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            var decoded = new AtomicInteger();
            var invoker = new ManagedHostedInvoker(Map.of(METHOD, arguments -> "done"), boundary -> {
                if (boundary.inboundBytes() < 0) throw org.j7m.middleware.api.MiddlewareException.of(org.j7m.middleware.api.MiddlewareErrorCode.INVALID_ARGUMENT, "Missing measurement");
                if (boundary.inboundBytes() > 10) throw org.j7m.middleware.api.MiddlewareException.of(org.j7m.middleware.api.MiddlewareErrorCode.OVERLOADED, "Input limit");
                return new ManagedInvocationLifetime(container.openCall(), InvocationContext.empty(), boundary.security(), boundary.options().deadline(), executor, 8, 8, () -> { });
            }, profile(), target());
            var options = new InvocationOptions("measured", Duration.ofSeconds(1), Map.of("inboundBytes", "0"));
            java.util.concurrent.Callable<Object[]> decoder = () -> { decoded.incrementAndGet(); return new Object[]{"value"}; };
            assertThrows(org.j7m.middleware.api.MiddlewareException.class, () -> invoker.invokeDecoded(METHOD, decoder, options));
            assertEquals(org.j7m.middleware.api.MiddlewareErrorCode.OVERLOADED, assertThrows(org.j7m.middleware.api.MiddlewareException.class, () -> invoker.invokeDecoded(METHOD, decoder, options, 11)).code());
            assertThrows(IllegalArgumentException.class, () -> invoker.invokeDecoded(METHOD, decoder, options, -1));
            assertEquals(0, decoded.get());
            assertEquals("done", invoker.invokeDecoded(METHOD, decoder, options, 10).toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(1, decoded.get()); assertEquals(0, container.status().activeCalls());
            ManagedHostedResources.Invoker unsupported = (method, action, invocation) -> CompletableFuture.completedFuture(null);
            assertThrows(UnsupportedOperationException.class, () -> unsupported.invokeDecoded(METHOD, decoder, options, 0));
        }
    }

    @Test void applicationErrorsBecomeSafeFailedStageAfterActualCleanupInsteadOfEscapingToRunner() throws Exception {
        for (boolean decoderError : List.of(false, true)) {
            try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
                var released = new AtomicInteger(); var profile = profile();
                var invoker = new ManagedHostedInvoker(Map.of(METHOD, arguments -> { throw new AssertionError("SYNTHETIC_BODY_ERROR"); }),
                        boundary -> new ManagedInvocationLifetime(container.openCall(), InvocationContext.empty(), boundary.security(),
                                boundary.options().deadline(), executor, 8, 8, released::incrementAndGet), profile, target());
                var result = assertDoesNotThrow(() -> invoker.invokeDecoded(METHOD, () -> {
                    if (decoderError) throw new AssertionError("SYNTHETIC_DECODER_ERROR");
                    return new Object[]{"value"};
                }, InvocationOptions.defaults()));
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> result.toCompletableFuture().get(2, TimeUnit.SECONDS));
                assertFalse(failure.toString().contains("SYNTHETIC_"));
                assertEquals(1, released.get()); assertEquals(0, container.status().activeCalls());
            }
        }
    }

    private static final ManagedMethod METHOD = new ManagedMethod("sample.Worker", "receive", List.of(String.class.getName()), String.class.getName());
    private static ObjectRef target() {
        return new ObjectRef("application/app/instance/feature", "application/app/instance/feature", "feature", "host", "workers",
                List.of(), "hosted", "", 0, 1, Map.of("j7m.hosted.internal", "true"));
    }
    private static ManagedHostedResources.Entry profile() {
        var worker = new SecurityContext(new Principal("worker", "worker", Set.of("consumer"), Set.of()), "host", new TrustDomain("worker"), null,
                "", "", "", false, null, Set.of("consumer"), Map.of(), Instant.EPOCH);
        var factory = new ManagedHostedResources.Factory() {
            @Override public void validate(org.j7m.middleware.application.api.ManagedHostedFeature feature,
                    org.j7m.middleware.application.api.ManagedComponentDefinition<?> definition, ManagedHostedResources.Bundle bundle) { throw new UnsupportedOperationException(); }
            @Override public ManagedHostedResources.Binding bind(org.j7m.middleware.application.api.ManagedHostedFeature feature,
                    org.j7m.middleware.application.api.ManagedComponentDefinition<?> definition, Object component,
                    ManagedHostedResources.Bundle bundle, ManagedHostedResources.Invoker invoker) { throw new UnsupportedOperationException(); }
        };
        return new ManagedHostedResources.Entry(factory, worker, Duration.ofSeconds(5));
    }

    @Test void decoderAndCallbackShareFixedWorkerContextAndReplyWaitsForActualChildFinally() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            String traceparent = "00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01";
            var released = new AtomicInteger(); var boundary = new AtomicReference<InvocationBoundary>();
            var scope = new AtomicReference<ManagedInvocationLifetime>();
            var profile = profile();
            var invoker = new ManagedHostedInvoker(Map.of(METHOD, arguments -> {
                assertEquals("worker", J7mContext.currentInvocation().security().principal().id());
                assertFalse(org.j7m.middleware.security.api.SecurityContexts.current().authenticated());
                assertEquals(traceparent, org.j7m.middleware.application.api.InvocationTraceContext.current().get("traceparent"));
                J7mContext.currentInvocation().control().submit(() -> {
                    assertEquals("worker", J7mContext.currentInvocation().security().principal().id());
                    assertEquals(traceparent, org.j7m.middleware.application.api.InvocationTraceContext.current().get("traceparent"));
                    entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); return "child";
                });
                return "processed:" + arguments[0];
            }), admitted -> {
                boundary.set(admitted);
                var context = new InvocationContext(new InvocationSecurityContext(admitted.security().principal(), admitted.security().roles(),
                        admitted.security().trustDomain()), null, admitted.options().invocationId());
                var lifetime = new ManagedInvocationLifetime(container.openCall(), context, admitted.security(), admitted.options().deadline(),
                        executor, 8, 8, released::incrementAndGet);
                scope.set(lifetime); return lifetime;
            }, profile, target());
            assertTrue(invoker.admissionOpen(), "standalone handle is an explicitly trusted host profile");
            var result = invoker.invokeDecoded(METHOD, () -> {
                assertEquals("worker", J7mContext.currentInvocation().security().principal().id());
                assertTrue(ManagedInvocationLifetime.active()); return new Object[]{"value"};
            }, new InvocationOptions("delivery", Duration.ofHours(1), Map.of("sec.roles", "admin", "traceparent", traceparent)));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(Duration.ofSeconds(5), boundary.get().options().deadline());
                assertEquals(profile.worker(), boundary.get().security());
                assertFalse(result.toCompletableFuture().isDone()); assertEquals(0, released.get());
                assertEquals(1, container.status().activeCalls());
            } finally { release.countDown(); }
            assertEquals("processed:value", result.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(1, released.get()); assertEquals(0, container.status().activeCalls());
            assertTrue(scope.get().physicalCompletion().toCompletableFuture().isDone());
            assertEquals(InvocationContext.empty().security(), J7mContext.currentInvocation().security());
        }
    }

    @Test void undeclaredCallbacksAndDecoderFailuresNeverEnterBodyOrLeakOwnedCall() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            var bodies = new AtomicInteger(); var profile = profile();
            var invoker = new ManagedHostedInvoker(Map.of(METHOD, arguments -> { bodies.incrementAndGet(); return "unexpected"; }),
                    boundary -> new ManagedInvocationLifetime(container.openCall(), InvocationContext.empty(), boundary.security(),
                            boundary.options().deadline(), executor, 8, 8, () -> { }), profile, target());
            var other = new ManagedMethod("sample.Worker", "receive", List.of(Integer.class.getName()), String.class.getName());
            assertThrows(IllegalArgumentException.class, () -> invoker.invoke(other, new Object[]{1}, InvocationOptions.defaults()));
            assertThrows(IllegalArgumentException.class, () -> invoker.invoke(METHOD, new Object[]{"x"}, new InvocationOptions("x", Duration.ZERO, Map.of())));
            var result = invoker.invokeDecoded(METHOD, () -> { throw new IllegalStateException("SYNTHETIC_PRIVATE_DECODE"); }, InvocationOptions.defaults());
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> result.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertFalse(failure.toString().contains("SYNTHETIC_PRIVATE_DECODE"));
            assertEquals(0, bodies.get()); assertEquals(0, container.status().activeCalls());
            assertThrows(IllegalArgumentException.class, () -> new ManagedHostedInvoker(Map.of(), boundary -> { throw new AssertionError(); }, profile, target()));
        }
    }
}
