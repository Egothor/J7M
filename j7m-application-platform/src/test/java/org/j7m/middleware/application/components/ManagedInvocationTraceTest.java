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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.InvocationTraceContext;
import org.junit.jupiter.api.Test;

class ManagedInvocationTraceTest {
    private static Map<String, String> trace(final char id) {
        return Map.of("traceparent", "00-" + Character.toString(id).repeat(32) + "-1111111111111111-01");
    }
    private static ManagedInvocationLifetime lifetime(final ManagedContainer container,
            final java.util.concurrent.Executor executor) {
        return new ManagedInvocationLifetime(container.openCall(), InvocationContext.empty(), null,
                Duration.ofSeconds(10), executor, 8, 8, () -> { });
    }

    @Test void rootChildrenAndHostFinalizersUseTheirCapturedTraceAndRestoreCallerAndWorker() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            final ManagedInvocationLifetime scope;
            try (var root = InvocationTraceContext.open(trace('a'))) { scope = lifetime(container, executor); }
            try (var caller = InvocationTraceContext.open(trace('b'))) {
                scope.call(() -> {
                    assertEquals(trace('a'), InvocationTraceContext.current());
                    try (var childSpan = InvocationTraceContext.open(trace('c'))) {
                        scope.addResultFinalizer(outcome -> {
                            assertEquals(trace('c'), InvocationTraceContext.current());
                            return CompletableFuture.completedFuture(outcome);
                        });
                        return scope.submit(() -> {
                            assertEquals(trace('c'), InvocationTraceContext.current());
                            assertFalse(InvocationTraceContext.current().containsKey("authorization"));
                            return "payload";
                        });
                    }
                });
                assertEquals(trace('b'), InvocationTraceContext.current());
                assertEquals("payload", scope.logicalResult().toCompletableFuture().get(2, TimeUnit.SECONDS));
                scope.continueInContext(() -> { assertEquals(trace('b'), InvocationTraceContext.current()); return "encoded"; })
                        .toCompletableFuture().get(2, TimeUnit.SECONDS);
                scope.close(); scope.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
                assertEquals(trace('b'), InvocationTraceContext.current());
            }
            assertTrue(InvocationTraceContext.current().isEmpty());
            assertTrue(executor.submit(InvocationTraceContext::current).get(2, TimeUnit.SECONDS).isEmpty());
        }
    }

    @Test void queuedWorkCapturesAtSubmissionRatherThanActorExecutionTime() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            var occupied = new CountDownLatch(1); var release = new CountDownLatch(1);
            executor.execute(() -> {
                try (var unrelated = InvocationTraceContext.open(trace('d'))) {
                    occupied.countDown();
                    try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); fail(failure); }
                }
            });
            assertTrue(occupied.await(2, TimeUnit.SECONDS));
            final ManagedInvocationLifetime scope;
            try (var root = InvocationTraceContext.open(trace('a'))) {
                scope = lifetime(container, executor);
                scope.call(() -> {
                    try (var submitted = InvocationTraceContext.open(trace('c'))) {
                        return scope.submit(() -> InvocationTraceContext.current());
                    }
                });
            }
            try (var changed = InvocationTraceContext.open(trace('b'))) {
                release.countDown();
                assertEquals(trace('c'), scope.logicalResult().toCompletableFuture().get(2, TimeUnit.SECONDS));
                assertEquals(trace('b'), InvocationTraceContext.current());
            }
            scope.close(); scope.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test void invalidCarrierAndFailureCannotLeakTraceIntoNextInvocationOrWorker() throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
            try (var caller = InvocationTraceContext.open(trace('b'))) {
                var failed = lifetime(container, executor);
                assertThrows(IllegalArgumentException.class, () -> failed.call(() -> { throw new IllegalArgumentException("failure"); }));
                assertEquals(trace('b'), InvocationTraceContext.current());
                failed.close(); failed.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            }
            final ManagedInvocationLifetime next;
            try (var bad = InvocationTraceContext.open(Map.of("traceparent", "invalid", "authorization", "synthetic-secret"))) {
                next = lifetime(container, executor);
                next.call(() -> next.submit(InvocationTraceContext::current));
            }
            assertEquals(Map.of(), next.logicalResult().toCompletableFuture().get(2, TimeUnit.SECONDS));
            next.close(); next.physicalCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertTrue(executor.submit(InvocationTraceContext::current).get(2, TimeUnit.SECONDS).isEmpty());
        }
    }
}
