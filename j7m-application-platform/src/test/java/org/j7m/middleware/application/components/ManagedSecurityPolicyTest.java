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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.j7m.middleware.annotations.Audit;
import org.j7m.middleware.annotations.Confidential;
import org.j7m.middleware.annotations.RequiresPermission;
import org.j7m.middleware.annotations.RequiresRole;
import org.j7m.middleware.annotations.RequiresTrustDomain;
import org.j7m.middleware.annotations.Sensitive;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.InvocationSecurityContext;
import org.j7m.middleware.application.api.ManagedMethod;
import org.j7m.middleware.application.api.ManagedServiceContract;
import org.j7m.middleware.security.api.AuditEvent;
import org.j7m.middleware.security.api.AuthorizationDecision;
import org.j7m.middleware.security.api.AuthorizationPolicy;
import org.j7m.middleware.security.api.PermissionRequest;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.SecurityAuditSink;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityContexts;
import org.j7m.middleware.security.api.TransportSecurityLevel;
import org.j7m.middleware.security.api.TrustDomain;
import org.junit.jupiter.api.Test;

class ManagedSecurityPolicyTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC);
    private static final Set<String> ALL_ROLES = Set.of("api", "method", "component", "implementation");
    private static final ObjectRef TARGET = new ObjectRef("object-7", "logical", "//applications/secured", "server-2",
            "tenant", List.of(Secured.class.getName()), "grpc", "grpc://127.0.0.1:1", 0, 1, Map.of());

    @RequiresRole("api") @RequiresTrustDomain("finance") @Audit
    interface Secured {
        @RequiresRole("method") @RequiresPermission("operation:*")
        String run(@Sensitive String input);
    }
    @RequiresRole("unrelated")
    interface Other { void other(); }
    @RequiresRole("component")
    static class Implementation implements Secured, Other {
        @Override @RequiresRole("implementation") public String run(final String input) { return input; }
        @Override public void other() { }
    }
    interface Plain { String run(String input); }
    @Audit static class Audited implements Plain { public String run(final String input) { return input; } }
    @Audit(policy = "uninstalled") static class UnknownAudit implements Plain { public String run(final String input) { return input; } }
    @Confidential interface ConfidentialService { String secret(); }
    static class Secret implements ConfidentialService { public String secret() { return "value"; } }
    @RequiresTrustDomain("finance") interface Domain { String read(); }
    @RequiresTrustDomain("other-domain") static class Conflict implements Domain { public String read() { return "value"; } }
    interface Generic<T> { @RequiresRole("generic") T convert(T value); }
    static class GenericString implements Generic<String> {
        @Override @RequiresRole("body") public String convert(final String value) { return value; }
    }
    static class PrivateParent {
        @RequiresRole("private-helper") private String run(final String value) { return value; }
    }
    static class IndependentChild extends PrivateParent implements Plain {
        @Override public String run(final String value) { return value; }
    }

    private static final class Sink implements SecurityAuditSink {
        final List<AuditEvent> events = new ArrayList<>();
        int attempts;
        int failAt;
        @Override public void record(final AuditEvent event) {
            if (++this.attempts == this.failAt) { throw new IllegalStateException("SYNTHETIC_SECRET_AUDIT"); }
            this.events.add(event);
        }
        @Override public List<AuditEvent> recentEvents() { return List.copyOf(this.events); }
        @Override public long eventCount() { return this.events.size(); }
    }

    private static final class Authorizer implements AuthorizationPolicy {
        final List<PermissionRequest> requests = new ArrayList<>();
        boolean allowed = true;
        @Override public String id() { return "host-policy"; }
        @Override public AuthorizationDecision evaluate(final SecurityContext context, final PermissionRequest request) {
            this.requests.add(request);
            return this.allowed ? AuthorizationDecision.allow(id()) : AuthorizationDecision.deny(id(), "denied");
        }
    }

    @Test
    void effectiveRequirementsAccumulateAndPreserveActualTargetPermissionScope() throws Exception {
        var sink = new Sink();
        var authorization = new Authorizer();
        var policy = new ManagedSecurityPolicy(authorization, sink, CLOCK);
        var contract = contract(Secured.class, "run", String.class);
        policy.validate(contract, Implementation.class, Secured.class);
        var effects = new AtomicInteger();
        try (var binding = policy.bind(contract, Implementation.class, Secured.class)) {
            Object result = SecurityContexts.call(security(ALL_ROLES, true, TransportSecurityLevel.TEST_AUTHENTICATED),
                    () -> binding.invoke(InvocationContext.empty(), new Object[]{"SYNTHETIC_SECRET_INPUT"}, proceed(effects, "SYNTHETIC_SECRET_RESULT")));
            assertEquals("SYNTHETIC_SECRET_RESULT", result);
            assertEquals(1, effects.get());
            assertEquals(1, authorization.requests.size());
            var request = authorization.requests.getFirst();
            assertEquals("object-7", request.resource());
            assertEquals("tenant", request.registryContext());
            assertEquals("//applications/secured", request.serviceName());
            assertEquals("operation:*", request.metadata().get("requiredPermission"));
            assertTrue(request.methodDescriptor().contains("methodName=run"));
            assertEquals(2, sink.eventCount());
            assertFalse(sink.events.toString().contains("SYNTHETIC_SECRET"));
        }
    }

    @Test
    void missingOneImplementationRoleCannotBeOverriddenByInterfaceOrForgedInvocationContext() throws Exception {
        var policy = new ManagedSecurityPolicy(new Authorizer(), new Sink(), CLOCK);
        var effects = new AtomicInteger();
        var forged = new InvocationContext(new InvocationSecurityContext(new Principal("alice", "Alice", ALL_ROLES, Set.of()),
                ALL_ROLES, new TrustDomain("finance")), null, "correlation");
        try (var binding = policy.bind(contract(Secured.class, "run", String.class), Implementation.class, Secured.class)) {
            var denied = assertThrows(MiddlewareException.class, () -> SecurityContexts.call(
                    security(Set.of("api", "method", "component"), true, TransportSecurityLevel.TEST_AUTHENTICATED),
                    () -> binding.invoke(forged, new Object[]{"value"}, proceed(effects, "value"))));
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, denied.code());
            var untrusted = assertThrows(MiddlewareException.class, () -> binding.invoke(forged, new Object[]{"value"}, proceed(effects, "value")));
            assertEquals(MiddlewareErrorCode.UNAUTHENTICATED, untrusted.code());
            assertEquals(0, effects.get());
        }
    }

    @Test
    void deniedPermissionsAndMissingTrustedTargetFailBeforeDispatch() throws Exception {
        var authorization = new Authorizer();
        authorization.allowed = false;
        var policy = new ManagedSecurityPolicy(authorization, new Sink(), CLOCK);
        var effects = new AtomicInteger();
        try (var binding = policy.bind(contract(Secured.class, "run", String.class), Implementation.class, Secured.class)) {
            var denied = assertThrows(MiddlewareException.class, () -> SecurityContexts.call(
                    security(ALL_ROLES, true, TransportSecurityLevel.TEST_AUTHENTICATED),
                    () -> binding.invoke(InvocationContext.empty(), new Object[]{"value"}, proceed(effects, "value"))));
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, denied.code());
            authorization.allowed = true;
            assertThrows(MiddlewareException.class, () -> SecurityContexts.call(
                    security(ALL_ROLES, true, TransportSecurityLevel.TEST_AUTHENTICATED),
                    () -> binding.invoke(InvocationContext.empty(), new Object[]{"value"}, () -> effects.incrementAndGet())));
            assertEquals(0, effects.get());
        }
    }

    @Test
    void confidentialityRequiresRealEncryptedTransportAndConflictingDomainsRejectAtValidation() throws Exception {
        var policy = new ManagedSecurityPolicy(new Authorizer(), new Sink(), CLOCK);
        var effects = new AtomicInteger();
        try (var binding = policy.bind(contract(ConfidentialService.class, "secret"), Secret.class, ConfidentialService.class)) {
            var denied = assertThrows(MiddlewareException.class, () -> SecurityContexts.call(
                    security(Set.of(), true, TransportSecurityLevel.TEST_AUTHENTICATED),
                    () -> binding.invoke(InvocationContext.empty(), new Object[0], proceed(effects, "value"))));
            assertEquals(MiddlewareErrorCode.UNAUTHENTICATED, denied.code());
            assertEquals("value", SecurityContexts.call(security(Set.of(), true, TransportSecurityLevel.AUTHENTICATED_ENCRYPTED),
                    () -> binding.invoke(InvocationContext.empty(), new Object[0], proceed(effects, "value"))));
            assertEquals(1, effects.get());
        }
        assertThrows(IllegalArgumentException.class, () -> policy.validate(contract(Domain.class, "read"), Conflict.class, Domain.class));
    }

    @Test
    void mandatoryAuditFailureDistinguishesBeforeEffectAndUnknownAfterEffectWithoutRetry() throws Exception {
        for (int failAt : List.of(1, 2)) {
            var sink = new Sink(); sink.failAt = failAt;
            var policy = new ManagedSecurityPolicy(new Authorizer(), sink, CLOCK);
            var effects = new AtomicInteger();
            try (var binding = policy.bind(contract(Plain.class, "run", String.class), Audited.class, Plain.class)) {
                var failure = assertThrows(MiddlewareException.class, () -> binding.invoke(InvocationContext.empty(),
                        new Object[]{"SYNTHETIC_SECRET_INPUT"}, proceed(effects, "SYNTHETIC_SECRET_RESULT")));
                assertEquals(failAt - 1, effects.get());
                assertEquals(failAt == 1 ? "NOT_STARTED" : "UNKNOWN", failure.remoteError().details().get("outcome"));
                assertFalse(failure.retryable());
                assertFalse(failure.dirtyTarget());
                assertNull(failure.getCause());
                assertFalse(failure.toString().contains("SYNTHETIC_SECRET"));
            }
        }
    }

    @Test
    void unknownAuditPolicyAndClosedBindingCannotSilentlyExecute() throws Exception {
        var policy = new ManagedSecurityPolicy(new Authorizer(), new Sink(), CLOCK);
        assertEquals("uninstalled", UnknownAudit.class.getAnnotation(Audit.class).policy());
        assertThrows(IllegalArgumentException.class, () -> policy.validate(contract(Plain.class, "run", String.class), UnknownAudit.class, Plain.class));
        assertThrows(IllegalArgumentException.class, () -> new ManagedSecurityPolicy(new Authorizer(), Map.of(" ", new Sink()), CLOCK));
        var binding = policy.bind(contract(Plain.class, "run", String.class), Audited.class, Plain.class);
        binding.close(); binding.close();
        var effects = new AtomicInteger();
        assertThrows(MiddlewareException.class, () -> binding.invoke(InvocationContext.empty(), new Object[]{"value"}, proceed(effects, "value")));
        assertEquals(0, effects.get());
        assertTrue(policy.annotationTypes().contains(Sensitive.class));
    }

    @Test
    void erasedBridgeEntryRetainsImplementationRoleAndPrivateHelpersDoNotAddConstraints() throws Exception {
        var policy = new ManagedSecurityPolicy(new Authorizer(), new Sink(), CLOCK);
        var effects = new AtomicInteger();
        assertEquals("private", new PrivateParent().run("private"));
        try (var binding = policy.bind(contract(Generic.class, "convert", Object.class), GenericString.class, Generic.class)) {
            assertThrows(MiddlewareException.class, () -> SecurityContexts.call(
                    security(Set.of("generic"), true, TransportSecurityLevel.TEST_AUTHENTICATED),
                    () -> binding.invoke(InvocationContext.empty(), new Object[]{"value"}, proceed(effects, "value"))));
            assertEquals("value", SecurityContexts.call(security(Set.of("generic", "body"), true, TransportSecurityLevel.TEST_AUTHENTICATED),
                    () -> binding.invoke(InvocationContext.empty(), new Object[]{"value"}, proceed(effects, "value"))));
        }
        try (var binding = policy.bind(contract(Plain.class, "run", String.class), IndependentChild.class, Plain.class)) {
            assertEquals("value", binding.invoke(InvocationContext.empty(), new Object[]{"value"}, proceed(effects, "value")));
        }
        assertEquals(2, effects.get());
    }

    @Test
    void sensitiveValidationFailuresCannotLeakMessagesDetailsOrCauses() throws Exception {
        var sink = new Sink();
        var policy = new ManagedSecurityPolicy(new Authorizer(), sink, CLOCK);
        try (var binding = policy.bind(contract(Plain.class, "run", String.class), Audited.class, Plain.class)) {
            var failure = assertThrows(MiddlewareException.class, () -> binding.invoke(InvocationContext.empty(),
                    new Object[]{"SYNTHETIC_SECRET_PAYLOAD"}, () -> { throw sensitiveFailure(); }));
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, failure.code());
            assertEquals("UNKNOWN", failure.remoteError().details().get("outcome"));
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains("SYNTHETIC_SECRET"));
            assertFalse(failure.remoteError().toString().contains("SYNTHETIC_SECRET"));
            assertFalse(sink.events.toString().contains("SYNTHETIC_SECRET"));
        }
    }

    @Test
    void auditTracksActualStageCompletionAndRedactsAsynchronousFailure() throws Exception {
        var sink = new Sink();
        var policy = new ManagedSecurityPolicy(new Authorizer(), sink, CLOCK);
        try (var binding = policy.bind(contract(Plain.class, "run", String.class), Audited.class, Plain.class)) {
            var work = new CompletableFuture<String>();
            var result = (CompletableFuture<?>) binding.invoke(InvocationContext.empty(), new Object[0], () -> work);
            assertEquals(1, sink.eventCount());
            assertFalse(result.isDone());
            work.completeExceptionally(sensitiveFailure());
            var failure = assertThrows(CompletionException.class, result::join);
            assertTrue(failure.getCause() instanceof MiddlewareException);
            assertNull(failure.getCause().getCause());
            assertFalse(failure.getCause().toString().contains("SYNTHETIC_SECRET"));
            assertEquals(2, sink.eventCount());
            assertEquals("FAILED", sink.events.getLast().decision());
            assertFalse(sink.events.toString().contains("SYNTHETIC_SECRET"));
        }
    }

    @Audit interface AsyncPlain { java.util.concurrent.CompletionStage<String> run(); }
    static class AsyncWorker implements AsyncPlain { public java.util.concurrent.CompletionStage<String> run() { return null; } }

    @Test
    void ownedCancelledSecurityWrapperRetainsUntilWorkerFinallyThenAllowsPhysicalDrain() throws Exception {
        var policy = new ManagedSecurityPolicy(new Authorizer(), new Sink(), CLOCK);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var exit = new java.util.concurrent.CountDownLatch(1);
        var workerFinally = new java.util.concurrent.atomic.AtomicBoolean();
        try (var container = ManagedContainer.activate(List.of());
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
                var binding = policy.bind(contract(AsyncPlain.class, "run"), AsyncWorker.class, AsyncPlain.class)) {
            var lifetime = new ManagedInvocationLifetime(container.openCall(), InvocationContext.empty(), null,
                    java.time.Duration.ofSeconds(10), executor, 4, 4, () -> { });
            var wrapper = (java.util.concurrent.CompletionStage<?>) lifetime.call(() -> binding.invoke(InvocationContext.empty(),
                    new Object[0], () -> lifetime.submit(() -> {
                        entered.countDown();
                        try { assertTrue(exit.await(2, java.util.concurrent.TimeUnit.SECONDS)); return "value"; }
                        finally { workerFinally.set(true); }
                    })));
            try {
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(wrapper.toCompletableFuture().cancel(false));
                lifetime.close();
                assertFalse(workerFinally.get());
                assertFalse(lifetime.physicalCompletion().toCompletableFuture().isDone());
            } finally { exit.countDown(); }
            lifetime.physicalCompletion().toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(workerFinally.get());
            assertEquals(0, container.status().activeCalls());
        }
    }

    @Test
    void arbitraryCancelledSecurityWrapperMustNotConvertUnknownSourceIntoPhysicalProof() throws Exception {
        var policy = new ManagedSecurityPolicy(new Authorizer(), new Sink(), CLOCK);
        var container = ManagedContainer.activate(List.of());
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
                var binding = policy.bind(contract(AsyncPlain.class, "run"), AsyncWorker.class, AsyncPlain.class)) {
            var lifetime = new ManagedInvocationLifetime(container.openCall(), InvocationContext.empty(), null,
                    java.time.Duration.ofSeconds(10), executor, 4, 4, () -> { });
            var source = new CompletableFuture<String>();
            lifetime.call(() -> binding.invoke(InvocationContext.empty(), new Object[0], () -> source));
            source.cancel(false);
            lifetime.close();
            assertTrue(lifetime.logicalResult().toCompletableFuture().isCompletedExceptionally());
            assertFalse(lifetime.physicalCompletion().toCompletableFuture().isDone());
            assertEquals("CALL_BUSY", assertThrows(ManagedContainer.Failure.class, container::close).code());
            // UNKNOWN has no forced-disposal authority: this deliberately remains unresolved.
        }
    }

    @Test
    void asyncFailureSanitizationDoesNotInvokeOverriddenApplicationThrowableHooks() throws Exception {
        var policy = new ManagedSecurityPolicy(new Authorizer(), new Sink(), CLOCK);
        var hooks = new AtomicInteger();
        var failure = new CompletionException("synthetic secret", null) {
            @Override public synchronized Throwable getCause() { hooks.incrementAndGet(); throw new AssertionError("application hook outside context"); }
        };
        try (var binding = policy.bind(contract(AsyncPlain.class, "run"), AsyncWorker.class, AsyncPlain.class)) {
            var source = new CompletableFuture<String>();
            var wrapped = (java.util.concurrent.CompletionStage<?>) binding.invoke(InvocationContext.empty(), new Object[0], () -> source);
            source.completeExceptionally(failure);
            assertEquals(0, hooks.get());
            var error = assertThrows(CompletionException.class, () -> wrapped.toCompletableFuture().join());
            assertEquals(MiddlewareErrorCode.REMOTE_EXCEPTION, ((MiddlewareException) error.getCause()).code());
        }
    }

    @Test
    void mandatoryPostAuditWaitsForDurableResultAndReportsDurableFailureWithoutRepeatingBody() throws Exception {
        var sink = new Sink();
        var policy = new ManagedSecurityPolicy(new Authorizer(), sink, CLOCK);
        var durable = new CompletableFuture<org.j7m.middleware.transport.api.InvocationResult>();
        var durableEntered = new java.util.concurrent.CountDownLatch(1);
        var bodies = new AtomicInteger();
        try (var container = ManagedContainer.activate(List.of()); var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
                var binding = policy.bind(contract(AsyncPlain.class, "run"), AsyncWorker.class, AsyncPlain.class)) {
            var scope = new ManagedInvocationLifetime(container.openCall(), InvocationContext.empty(), null,
                    java.time.Duration.ofSeconds(10), executor, 8, 8, () -> { });
            var next = new ManagedInvocationPolicy.Proceed() {
                @Override public boolean supportsResultFinalizers() { return scope.supportsResultFinalizers(); }
                @Override public void addResultFinalizer(final org.j7m.middleware.transport.api.InvocationResultFinalizer finalizer) {
                    scope.addResultFinalizer(finalizer);
                }
                @Override public Object invoke() {
                    scope.addResultFinalizer(outcome -> { durableEntered.countDown(); return durable; });
                    scope.markApplicationEntry(); bodies.incrementAndGet();
                    return CompletableFuture.completedFuture("value");
                }
            };
            scope.call(() -> binding.invoke(InvocationContext.empty(), new Object[0], next));
            assertTrue(durableEntered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, sink.eventCount()); assertEquals("START", sink.events.getFirst().decision());
            assertFalse(scope.logicalResult().toCompletableFuture().isDone());
            durable.completeExceptionally(new IllegalStateException("synthetic-secret-durable-failure"));
            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> scope.logicalResult().toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(error.getCause().getMessage().contains("synthetic-secret"));
            assertEquals(2, sink.eventCount()); assertEquals("FAILED", sink.events.getLast().decision());
            assertEquals(1, bodies.get());
            scope.close(); scope.physicalCompletion().toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static MiddlewareException sensitiveFailure() {
        return new MiddlewareException(new RemoteError(MiddlewareErrorCode.INVALID_ARGUMENT, "SYNTHETIC_SECRET_MESSAGE",
                Map.of("publicField", "SYNTHETIC_SECRET_DETAIL"), null, null, true, true),
                new IllegalArgumentException("SYNTHETIC_SECRET_CAUSE"));
    }

    private static ManagedServiceContract contract(final Class<?> api, final String name, final Class<?>... parameters) throws NoSuchMethodException {
        Method method = api.getMethod(name, parameters);
        return new ManagedServiceContract("sample.contract", 1, new ManagedMethod(method.getDeclaringClass().getName(), name,
                Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(), method.getReturnType().getName()));
    }

    private static SecurityContext security(final Set<String> roles, final boolean authenticated, final TransportSecurityLevel level) {
        return new SecurityContext(new Principal("alice", "Alice", roles, Set.of()), "caller", new TrustDomain("finance"), null,
                "correlation", "invocation", "", authenticated, level, roles, Map.of(), CLOCK.instant());
    }

    private static ManagedInvocationPolicy.Proceed proceed(final AtomicInteger effects, final Object value) {
        return new ManagedInvocationPolicy.Proceed() {
            @Override public Object invoke() { effects.incrementAndGet(); return value; }
            @Override public ObjectRef target() { return TARGET; }
        };
    }
}
