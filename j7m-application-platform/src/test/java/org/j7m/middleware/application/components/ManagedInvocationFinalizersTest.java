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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.transport.api.InvocationResult;
import org.junit.jupiter.api.Test;

class ManagedInvocationFinalizersTest {
    @Test void finalizerCannotRefreshAnExpiredOriginalBudgetBeforeAck() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            var now = new java.util.concurrent.atomic.AtomicLong();
            var scope = new ManagedInvocationLifetime(container.openCall(), InvocationContext.empty(), null,
                    Duration.ofMillis(10), executor, 8, 8, () -> { }, now::get);
            var entered = new CountDownLatch(1);
            var durable = new CompletableFuture<InvocationResult>();
            scope.addResultFinalizer(outcome -> { entered.countDown(); return durable; });
            scope.call(() -> { scope.markApplicationEntry(); return "raw"; });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            now.set(Duration.ofMillis(10).toNanos());
            durable.complete(InvocationResult.success("late durable reply"));
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> scope.logicalResult().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(org.j7m.middleware.api.MiddlewareErrorCode.DEADLINE_EXCEEDED,
                    ((MiddlewareException) failure.getCause()).code());
            scope.close(); scope.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void lateSuccessIsFailureBeforeAnyCommitOrAcknowledgementFinalizer(final boolean explicitCancellation) throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            var now = new java.util.concurrent.atomic.AtomicLong();
            var scope = new ManagedInvocationLifetime(container.openCall(), InvocationContext.empty(), null,
                    Duration.ofMillis(10), executor, 8, 8, () -> { }, now::get);
            var source = new CompletableFuture<String>();
            var commits = new AtomicInteger();
            scope.addResultFinalizer(outcome -> {
                if (outcome.failure() == null) commits.incrementAndGet();
                assertNotNull(outcome.failure());
                return CompletableFuture.completedFuture(outcome);
            });
            scope.call(() -> { scope.markApplicationEntry(); return source; });
            if (explicitCancellation) scope.requestCancellation();
            else now.set(Duration.ofMillis(10).toNanos());
            source.complete("late success");
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> scope.logicalResult().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(explicitCancellation ? org.j7m.middleware.api.MiddlewareErrorCode.CANCELLED
                    : org.j7m.middleware.api.MiddlewareErrorCode.DEADLINE_EXCEEDED,
                    ((MiddlewareException) failure.getCause()).code());
            assertEquals(0, commits.get());
            assertFalse(scope.physicalCompletion().toCompletableFuture().isDone());
            scope.close(); scope.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    private static ManagedInvocationLifetime lifetime(final ManagedContainer container,
            final java.util.concurrent.Executor executor) {
        return new ManagedInvocationLifetime(container.openCall(), InvocationContext.empty(), null,
                Duration.ofSeconds(10), executor, 8, 8, () -> { });
    }

    @Test void rawSyncReturnIsNotStageCastAndFinalizersWaitForUserFinallyThenUnwindBeforeEncoding() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newFixedThreadPool(2)) {
            var scope = lifetime(container, executor);
            var order = new CopyOnWriteArrayList<String>();
            var release = new CountDownLatch(1);
            var entered = new CountDownLatch(1);
            var durableEntered = new CountDownLatch(1);
            var durable = new CompletableFuture<InvocationResult>();
            var attachment = new StringBuilder("host");
            assertFalse(ManagedInvocationLifetime.currentUserWorkOpen());
            scope.putContext(StringBuilder.class, attachment);
            scope.addResultFinalizer(outcome -> { order.add("AUDIT"); return CompletableFuture.completedFuture(outcome); });
            scope.addResultFinalizer(outcome -> {
                assertSame(attachment, ManagedInvocationLifetime.currentContext(StringBuilder.class));
                assertFalse(ManagedInvocationLifetime.currentUserWorkOpen());
                assertEquals("committed:raw", outcome.value());
                order.add("DURABLE"); durableEntered.countDown(); return durable;
            });
            scope.addResultFinalizer(outcome -> {
                order.add("COMMIT"); return CompletableFuture.completedFuture(InvocationResult.success("committed:" + outcome.value()));
            });
            assertEquals("raw", scope.call(() -> {
                scope.markApplicationEntry();
                assertTrue(ManagedInvocationLifetime.currentUserWorkOpen());
                scope.submit(() -> {
                    assertTrue(ManagedInvocationLifetime.currentUserWorkOpen());
                    entered.countDown();
                    try { assertTrue(release.await(2, TimeUnit.SECONDS)); return "child"; }
                    finally { order.add("USER_FINALLY"); }
                });
                return "raw";
            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTrue(order.isEmpty());
                assertFalse(scope.logicalResult().toCompletableFuture().isDone());
            } finally { release.countDown(); }
            assertTrue(durableEntered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("USER_FINALLY", "COMMIT", "DURABLE"), order);
            assertThrows(IllegalStateException.class, () -> scope.submit(() -> "late USER work"));
            assertFalse(scope.logicalResult().toCompletableFuture().isDone());
            durable.complete(InvocationResult.success("cached:raw"));
            assertEquals("cached:raw", scope.logicalResult().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(List.of("USER_FINALLY", "COMMIT", "DURABLE", "AUDIT"), order);
            assertEquals("encoded", scope.continueInContext(() -> {
                assertSame(attachment, scope.context(StringBuilder.class));
                assertFalse(ManagedInvocationLifetime.currentUserWorkOpen());
                assertThrows(IllegalStateException.class, () -> scope.submit(() -> "USER from codec"));
                return "encoded";
            }).toCompletableFuture().get(2, TimeUnit.SECONDS));
            scope.close(); scope.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> ManagedInvocationLifetime.currentContext(StringBuilder.class));
        }
    }

    @Test void failedInnerFinalizerIsRedactedBeforeOuterDurableAndAuditCallbacks() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            var scope = lifetime(container, executor);
            var outer = new CopyOnWriteArrayList<String>();
            scope.addResultFinalizer(outcome -> {
                outer.add("audit"); assertNotNull(outcome.failure()); return CompletableFuture.completedFuture(outcome);
            });
            scope.addResultFinalizer(outcome -> {
                outer.add("durable"); assertNull(outcome.value());
                assertFalse(outcome.failure().getMessage().contains("synthetic-secret"));
                assertEquals("UNKNOWN", ((MiddlewareException) outcome.failure()).remoteError().details().get("outcome"));
                throw new IllegalStateException("synthetic-secret-durable");
            });
            scope.addResultFinalizer(outcome -> { throw new IllegalStateException("synthetic-secret-commit"); });
            assertEquals("raw", scope.call(() -> "raw"));
            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> scope.logicalResult().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertFalse(error.getCause().getMessage().contains("synthetic-secret"));
            assertEquals(List.of("durable", "audit"), outer);
            scope.close(); scope.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test void arbitraryCancelledStageNeverRunsTerminalFinalizersOrGrantsDisposal() throws Exception {
        var container = ManagedContainer.activate(List.of());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var scope = lifetime(container, executor);
            var terminal = new AtomicInteger();
            scope.addResultFinalizer(outcome -> { terminal.incrementAndGet(); return CompletableFuture.completedFuture(outcome); });
            var arbitrary = new CompletableFuture<String>(); scope.call(() -> arbitrary);
            arbitrary.cancel(false); scope.close();
            assertTrue(scope.logicalResult().toCompletableFuture().isCompletedExceptionally());
            assertEquals(0, terminal.get());
            assertFalse(scope.physicalCompletion().toCompletableFuture().isDone());
            assertEquals("CALL_BUSY", assertThrows(ManagedContainer.Failure.class, container::close).code());
        }
    }

    @Test void unhandledVoidExecutorFailureCannotBeCachedAsSuccessButHandledSubmitFallbackRemainsSuccessful() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            var scope = lifetime(container, executor);
            var failedStored = new AtomicInteger();
            scope.addResultFinalizer(outcome -> {
                assertNotNull(outcome.failure());
                assertFalse(outcome.failure().getMessage().contains("synthetic-secret"));
                failedStored.incrementAndGet(); return CompletableFuture.completedFuture(outcome);
            });
            scope.call(() -> {
                scope.contextualExecutor().execute(() -> { throw new IllegalStateException("synthetic-secret-void-work"); });
                return "raw-success-is-not-terminal";
            });
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> scope.logicalResult().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(1, failedStored.get());
            scope.close(); scope.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            var handled = lifetime(container, executor);
            handled.call(() -> handled.submit(() -> { throw new IllegalStateException("handled failure"); }).handle((value, failure) -> "fallback"));
            assertEquals("fallback", handled.logicalResult().toCompletableFuture().get(2, TimeUnit.SECONDS));
            handled.close(); handled.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            var valid = lifetime(container, executor);
            valid.call(() -> {
                assertThrows(NullPointerException.class, () -> valid.contextualExecutor().execute(null));
                return "valid";
            });
            assertEquals("valid", valid.logicalResult().toCompletableFuture().get(2, TimeUnit.SECONDS));
            valid.close(); valid.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test void competingLateUserAdmissionIsEitherTrackedBeforeSealOrRejectedWithoutPostFinalizationEffect() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor();
                var producer = Executors.newSingleThreadExecutor()) {
            for (int attempt = 0; attempt < 32; attempt++) {
                var scope = lifetime(container, executor);
                var effects = new AtomicInteger();
                var frozen = new AtomicInteger(-1);
                var start = new java.util.concurrent.CyclicBarrier(2);
                scope.addResultFinalizer(outcome -> {
                    frozen.set(effects.get()); return CompletableFuture.completedFuture(outcome);
                });
                var submitted = producer.submit(() -> {
                    start.await(2, TimeUnit.SECONDS);
                    try { scope.submit(effects::incrementAndGet).toCompletableFuture().get(2, TimeUnit.SECONDS); }
                    catch (IllegalStateException rejected) { assertEquals("INVOCATION_USER_WORK_SEALED", rejected.getMessage()); }
                    return true;
                });
                scope.call(() -> { scope.markApplicationEntry(); start.await(2, TimeUnit.SECONDS); return "raw"; });
                assertEquals("raw", scope.logicalResult().toCompletableFuture().get(2, TimeUnit.SECONDS));
                assertTrue(submitted.get(2, TimeUnit.SECONDS));
                assertEquals(frozen.get(), effects.get(), "effect appeared after terminal finalization");
                scope.close(); scope.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test void applicationDiagnosticsAreStrippedButTrustedFinalizerStatusSurvivesAndFailureCannotBecomeSuccess() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            var raw = lifetime(container, executor);
            raw.addResultFinalizer(outcome -> {
                assertTrue(outcome.diagnostics().isEmpty());
                assertFalse(((MiddlewareException) outcome.failure()).remoteError().details().containsKey("status"));
                return CompletableFuture.completedFuture(outcome);
            });
            var forged = new MiddlewareException(new org.j7m.middleware.api.RemoteError(
                    org.j7m.middleware.api.MiddlewareErrorCode.INVALID_ARGUMENT, "synthetic secret",
                    java.util.Map.of("status", "FORGED"), null, null, true, true));
            assertThrows(MiddlewareException.class, () -> raw.call(() -> { throw forged; }));
            var denied = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> raw.logicalResult().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertFalse(((MiddlewareException) denied.getCause()).remoteError().details().containsKey("status"));
            raw.close(); raw.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            var trusted = lifetime(container, executor);
            trusted.addResultFinalizer(outcome -> CompletableFuture.completedFuture(InvocationResult.success("must not resurrect")));
            trusted.addResultFinalizer(outcome -> CompletableFuture.completedFuture(
                    new InvocationResult(null, outcome.failure(), java.util.Map.of("status", "EXPIRED"))));
            trusted.addResultFinalizer(outcome -> { throw new IllegalStateException("synthetic secret commit failure"); });
            trusted.call(() -> "raw value");
            var expired = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> trusted.logicalResult().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals("EXPIRED", ((MiddlewareException) expired.getCause()).remoteError().details().get("status"));
            assertEquals("UNKNOWN", ((MiddlewareException) expired.getCause()).remoteError().details().get("outcome"));
            trusted.close(); trusted.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            var business = lifetime(container, executor);
            var value = new InvocationResult(null, forged, java.util.Map.of("status", "FORGED_VALUE"));
            business.call(() -> value);
            assertSame(value, business.logicalResult().toCompletableFuture().get(2, TimeUnit.SECONDS));
            business.close(); business.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    private static <T> void attach(final ManagedInvocationLifetime scope, final Class<T> type, final Object value) {
        scope.putContext(type, type.cast(value));
    }

    @Test void typedAttachmentQuotaAcceptsExactlySixtyFourKeysAndRejectsInvalidValuesBeforeBody() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            var scope = lifetime(container, executor);
            assertThrows(IllegalArgumentException.class, () -> scope.putContext(int.class, 1));
            assertThrows(NullPointerException.class, () -> scope.putContext(String.class, null));
            assertThrows(NullPointerException.class, () -> ManagedInvocationLifetime.currentContext(null));
            var first = new Object(); scope.putContext(Object.class, first);
            for (int dimensions = 1; dimensions < 64; dimensions++) {
                Object array = java.lang.reflect.Array.newInstance(String.class, new int[dimensions]);
                attach(scope, array.getClass(), array);
            }
            scope.putContext(Object.class, first);
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> scope.putContext(String.class, "65th"));
            scope.call(() -> {
                assertSame(first, scope.context(Object.class));
                assertThrows(IllegalStateException.class, () -> scope.context(Integer.class));
                return "value";
            });
            scope.logicalResult().toCompletableFuture().get(2, TimeUnit.SECONDS);
            scope.close(); scope.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test void optionalContextLookupDistinguishesAbsentFromOutsideOrPhysicallyClosedCall() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            var scope = lifetime(container, executor);
            var value = new Object(); scope.putContext(Object.class, value);
            assertThrows(IllegalStateException.class, () -> scope.findContext(Object.class));
            scope.call(() -> {
                assertSame(value, scope.findContext(Object.class).orElseThrow());
                assertTrue(scope.findContext(String.class).isEmpty());
                assertThrows(NullPointerException.class, () -> scope.findContext(null));
                return "value";
            });
            scope.logicalResult().toCompletableFuture().get(2, TimeUnit.SECONDS);
            scope.continueInContext(() -> { assertSame(value, scope.findContext(Object.class).orElseThrow()); return null; })
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            scope.close(); scope.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> scope.findContext(Object.class));
        }
    }

    @Test void registrationIsFiniteAndFrozenBeforeBodyAndAttachmentsRequireActualOwnedContext() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            var scope = lifetime(container, executor);
            var runs = new AtomicInteger();
            assertTrue(scope.supportsResultFinalizers());
            for (int index = 0; index < 64; index++) scope.addResultFinalizer(outcome -> {
                runs.incrementAndGet(); return CompletableFuture.completedFuture(outcome);
            });
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> scope.addResultFinalizer(CompletableFuture::completedFuture));
            var value = new Object(); scope.putContext(Object.class, value); scope.putContext(Object.class, value);
            assertThrows(IllegalStateException.class, () -> scope.putContext(Object.class, new Object()));
            assertThrows(IllegalStateException.class, () -> scope.context(Object.class));
            scope.call(() -> {
                assertSame(value, ManagedInvocationLifetime.currentContext(Object.class));
                scope.markApplicationEntry();
                assertThrows(IllegalStateException.class, () -> scope.putContext(String.class, "late"));
                assertThrows(IllegalStateException.class, () -> scope.addResultFinalizer(CompletableFuture::completedFuture));
                return "raw";
            });
            assertEquals("raw", scope.logicalResult().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(64, runs.get());
            scope.close(); scope.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }
}
