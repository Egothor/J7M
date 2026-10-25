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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.J7mContext;
import org.j7m.middleware.application.api.ManagedLifecycle;
import org.j7m.middleware.security.api.SecurityContexts;
import org.junit.jupiter.api.Test;

class ManagedInvocationLifetimeTest {
    private static ManagedInvocationLifetime lifetime(final ManagedContainer container,
            final java.util.concurrent.Executor executor, final AtomicInteger releases, final int capacity) {
        return new ManagedInvocationLifetime(container.openCall(), new InvocationContext(null, null, "correlation"),
                null, Duration.ofSeconds(10), executor, capacity, capacity, releases::incrementAndGet);
    }

    @Test void idleRetentionPreventsContainerShutdownAndReleasesExactlyOnceAfterEncode() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            var releases = new AtomicInteger();
            var lifetime = lifetime(container, executor, releases, 4);
            assertNull(ManagedInvocationLifetime.currentPhysicalCompletion());
            assertEquals("value", lifetime.call(() -> {
                var copy = ManagedInvocationLifetime.currentPhysicalCompletion().toCompletableFuture();
                assertTrue(copy.complete(null));
                assertFalse(lifetime.physicalCompletion().toCompletableFuture().isDone());
                return "value";
            }));
            assertEquals("CALL_BUSY", assertThrows(ManagedContainer.Failure.class, container::close).code());
            assertEquals("CALL_BUSY", assertThrows(ManagedContainer.Failure.class,
                    () -> container.lifecycle(ManagedLifecycle.SHUTDOWN)).code());
            assertFalse(lifetime.physicalCompletion().toCompletableFuture().isDone());
            assertEquals("encoded", lifetime.continueInContext(() -> {
                assertFalse(J7mContext.currentInvocation().control() instanceof org.j7m.middleware.transport.api.InvocationCompletionScope);
                assertTrue(ManagedInvocationLifetime.active());
                return "encoded";
            }).toCompletableFuture().get(2, TimeUnit.SECONDS));
            lifetime.close(); lifetime.close();
            lifetime.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(1, releases.get());
            assertEquals(0, container.status().activeCalls());
            assertThrows(IllegalStateException.class, () -> lifetime.submit(() -> "late"));
            assertThrows(IllegalStateException.class, () -> lifetime.call(() -> "second root"));
        }
    }

    @Test void cancelledReplyAndEarlyLogicalCompletionDoNotReleaseRunningWorker() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            var releases = new AtomicInteger();
            var lifetime = lifetime(container, executor, releases, 4);
            var entered = new CountDownLatch(1);
            var exit = new CountDownLatch(1);
            CompletionStage<String> result = lifetime.call(() -> lifetime.submit(() -> {
                entered.countDown();
                assertTrue(exit.await(2, TimeUnit.SECONDS));
                assertTrue(lifetime.cancellationRequested());
                return "done";
            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTrue(result.toCompletableFuture().cancel(false));
                var forgedReply = result.toCompletableFuture();
                assertTrue(forgedReply.complete("forged reply"));
                assertEquals("forged reply", forgedReply.join());
                assertFalse(result.toCompletableFuture().isDone());
                lifetime.requestCancellation(); lifetime.close();
                assertEquals(0, releases.get());
                assertFalse(lifetime.physicalCompletion().toCompletableFuture().isDone());
                assertEquals("CALL_BUSY", assertThrows(ManagedContainer.Failure.class, container::close).code());
            } finally { exit.countDown(); }
            lifetime.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals("done", result.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(1, releases.get());
        }
    }

    @Test void arbitraryCancelledStageRetainsUnknownOwnershipRatherThanFabricatingExit() throws Exception {
        var container = ManagedContainer.activate(List.of());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var lifetime = lifetime(container, executor, new AtomicInteger(), 4);
            var arbitrary = new CompletableFuture<String>();
            lifetime.call(() -> arbitrary);
            arbitrary.cancel(false);
            lifetime.close();
            assertFalse(lifetime.physicalCompletion().toCompletableFuture().isDone());
            assertEquals("CALL_BUSY", assertThrows(ManagedContainer.Failure.class, container::close).code());
            // This intentionally unresolved invocation models UNKNOWN; no forced-disposal API exists.
        }
    }

    @Test void workerContextExitsBeforePhysicalCallbacksAndCallerContextIsRestored() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            var releases = new AtomicInteger();
            var lifetime = lifetime(container, executor, releases, 4);
            var callback = new CompletableFuture<Void>();
            lifetime.physicalCompletion().whenComplete((ignored, failure) -> {
                assertNull(ManagedInvocationLifetime.current());
                assertFalse(ManagedInvocationLifetime.active());
                assertNull(SecurityContexts.current());
                callback.complete(null);
            });
            try (var ignored = J7mContext.open(new InvocationContext(null, null, "outer"))) {
                var result = lifetime.call(() -> lifetime.submit(() -> {
                    assertSame(lifetime, ManagedInvocationLifetime.current());
                    assertEquals("correlation", J7mContext.currentInvocation().correlationId());
                    return "ok";
                }));
                assertEquals("outer", J7mContext.currentInvocation().correlationId());
                assertEquals("ok", result.toCompletableFuture().get(2, TimeUnit.SECONDS));
                lifetime.close();
            }
            callback.get(2, TimeUnit.SECONDS);
            assertEquals(1, releases.get());
            assertNull(executor.submit(ManagedInvocationLifetime::current).get(2, TimeUnit.SECONDS));
        }
    }

    @Test void inlineCompletionMayQueueEncodingButCannotCloseCallScopeInsideWorkerFinally() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            var releases = new AtomicInteger();
            var lifetime = lifetime(container, executor, releases, 4);
            var complete = new CountDownLatch(1);
            CompletionStage<String> result = lifetime.call(() -> lifetime.submit(() -> {
                assertTrue(complete.await(2, TimeUnit.SECONDS)); return "value";
            }));
            var encoded = result.thenCompose(value -> lifetime.continueInContext(() -> {
                assertSame(lifetime, ManagedInvocationLifetime.current());
                lifetime.close();
                assertEquals(0, releases.get());
                assertEquals(1, container.status().activeCalls());
                return value + " encoded";
            }));
            complete.countDown();
            assertEquals("value encoded", encoded.toCompletableFuture().get(2, TimeUnit.SECONDS));
            lifetime.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(1, releases.get());
        }
    }

    @Test void boundedPendingAdmissionAndRejectedExecutorLeaveNoUnaccountedWork() throws Exception {
        try (var container = ManagedContainer.activate(List.of())) {
            var lifetime = lifetime(container, task -> { throw new RejectedExecutionException("full"); }, new AtomicInteger(), 1);
            CompletionStage<String> result = lifetime.call(() -> {
                var first = lifetime.submit(() -> "must not run");
                assertThrows(RejectedExecutionException.class, () -> lifetime.submit(() -> "overflow"));
                return first;
            });
            assertThrows(java.util.concurrent.ExecutionException.class, () -> result.toCompletableFuture().get(2, TimeUnit.SECONDS));
            lifetime.close();
            lifetime.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(0, container.status().activeCalls());
        }
    }

    @Test void contextualExecutorSharesOneSerialCallProductAndDisposesOnlyAfterAllChildren() throws Exception {
        var closed = new java.util.ArrayList<String>();
        var definition = ManagedContainerTest.definition(ManagedContainerTest.Value.class, "call",
                org.j7m.middleware.annotations.Scope.Kind.CALL,
                resolver -> new ManagedContainerTest.Value("owned-call", closed));
        try (var container = ManagedContainerTest.activate(definition); var executor = Executors.newFixedThreadPool(2)) {
            var lifetime = lifetime(container, executor, new AtomicInteger(), 4);
            var first = new java.util.concurrent.atomic.AtomicReference<ManagedContainerTest.Value>();
            var seen = new java.util.concurrent.CopyOnWriteArrayList<String>();
            var done = new CountDownLatch(2);
            lifetime.call(() -> {
                var contextual = lifetime.contextualExecutor();
                contextual.execute(() -> {
                    first.set(container.provider(ManagedContainerTest.Value.class, "call", List.of()).get());
                    seen.add("one"); done.countDown();
                });
                contextual.execute(() -> {
                    assertSame(first.get(), container.provider(ManagedContainerTest.Value.class, "call", List.of()).get());
                    seen.add("two"); done.countDown();
                });
                return "root returned";
            });
            lifetime.close();
            assertTrue(done.await(2, TimeUnit.SECONDS));
            lifetime.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(List.of("one", "two"), seen);
            assertEquals(List.of("owned-call"), closed);
        }
    }

    @Test void cancellationBeforeRootOrQueuedChildEntryPerformsNoEffectAndBudgetNeverRestarts() throws Exception {
        try (var container = ManagedContainer.activate(List.of())) {
            var clock = new java.util.concurrent.atomic.AtomicLong(100);
            var effects = new AtomicInteger();
            var root = new ManagedInvocationLifetime(container.openCall(), InvocationContext.empty(), null,
                    Duration.ofNanos(100), Runnable::run, 4, 4, () -> { }, clock::get);
            assertEquals(Duration.ofNanos(100), root.remainingBudget());
            clock.addAndGet(40);
            assertEquals(Duration.ofNanos(60), root.remainingBudget());
            root.requestCancellation();
            assertThrows(org.j7m.middleware.api.MiddlewareException.class, () -> root.call(effects::incrementAndGet));
            root.abortBeforeTarget(); root.close();
            root.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(0, effects.get());
            var pendingActor = new java.util.concurrent.atomic.AtomicReference<Runnable>();
            var child = lifetime(container, pendingActor::set, new AtomicInteger(), 4);
            var result = child.call(() -> child.submit(effects::incrementAndGet));
            child.requestCancellation();
            pendingActor.get().run();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> result.toCompletableFuture().get(2, TimeUnit.SECONDS));
            child.close(); child.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(0, effects.get());
        }
    }

    @Test void hostLogicalMirrorNeverInvokesApplicationToCompletableFutureOutsideOwnedContext() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            var lifetime = lifetime(container, executor, new AtomicInteger(), 4);
            var registered = new java.util.concurrent.atomic.AtomicBoolean();
            var application = new CompletableFuture<String>() {
                @Override public CompletableFuture<String> toCompletableFuture() { throw new AssertionError("application conversion invoked"); }
                @Override public CompletableFuture<String> whenComplete(final java.util.function.BiConsumer<? super String, ? super Throwable> action) {
                    assertTrue(ManagedInvocationLifetime.active());
                    registered.set(true);
                    return super.whenComplete(action);
                }
            };
            lifetime.call(() -> application);
            assertTrue(registered.get());
            application.complete("logical payload");
            assertEquals("logical payload", lifetime.logicalResult().toCompletableFuture().get(2, TimeUnit.SECONDS));
            lifetime.close(); lifetime.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test void checkedDispatchExceptionsKeepTheirIdentityAcrossSecurityContextCleanup() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            var lifetime = lifetime(container, executor, new AtomicInteger(), 4);
            var expected = new java.lang.reflect.InvocationTargetException(new IllegalArgumentException("target"));
            assertSame(expected, assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> lifetime.call(() -> { throw expected; })));
            lifetime.close();
            lifetime.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test void abortBeforeDecodeAndRootFailureAreCleanupProofButAbortAfterEntryIsRejected() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            var lifetime = lifetime(container, executor, new AtomicInteger(), 4);
            assertFalse(lifetime.remainingBudget().isZero());
            lifetime.abortBeforeTarget(); lifetime.close();
            lifetime.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> lifetime.call(() -> "late"));
            var failed = lifetime(container, executor, new AtomicInteger(), 4);
            assertThrows(IllegalArgumentException.class, () -> failed.call(() -> { throw new IllegalArgumentException("failure"); }));
            assertThrows(IllegalStateException.class, failed::abortBeforeTarget);
            failed.close();
            failed.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }
}
