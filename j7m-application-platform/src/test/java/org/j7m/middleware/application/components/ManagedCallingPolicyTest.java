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

import java.util.Arrays;
import java.util.concurrent.CompletionStage;
import org.j7m.middleware.annotations.Async;
import org.j7m.middleware.annotations.Deadline;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.NonIdempotent;
import org.j7m.middleware.annotations.OneWay;
import org.j7m.middleware.annotations.Retry;
import org.j7m.middleware.application.api.ManagedMethod;
import org.j7m.middleware.application.api.ManagedServiceContract;
import org.junit.jupiter.api.Test;

class ManagedCallingPolicyTest {
    interface AsyncContract { @Async @Deadline(millis = 1000) CompletionStage<String> work(); }
    static class AsyncWorker implements AsyncContract { public CompletionStage<String> work() { return null; } }
    interface SyncContract { String work(); }
    static class HiddenPolicy implements SyncContract { @Deadline(millis = 10) public String work() { return "value"; } }
    interface Conflict { @Async @OneWay CompletionStage<String> work(); }
    static class ConflictingWorker implements Conflict { public CompletionStage<String> work() { return null; } }
    interface Unsafe { @Idempotent @NonIdempotent String work(); }
    static class UnsafeWorker implements Unsafe { public String work() { return "value"; } }
    interface RetryContract { @Retry(maxAttempts = 1, policy = "registered") @Idempotent String work(); }
    static class RetryWorker implements RetryContract { public String work() { return "value"; } }
    interface InvalidDeadline { @Deadline(millis = 0) String work(); }
    static class InvalidDeadlineWorker implements InvalidDeadline { public String work() { return "value"; } }
    interface WrongAsync { @Async String work(); }
    static class WrongAsyncWorker implements WrongAsync { public String work() { return "value"; } }
    interface RawAsync { @Async CompletionStage work(); }
    static class RawAsyncWorker implements RawAsync { public CompletionStage work() { return null; } }
    interface WrongOneWay { @OneWay String work(); }
    static class WrongOneWayWorker implements WrongOneWay { public String work() { return "value"; } }

    interface ShortDeadline { @Deadline(millis = 10) String work(); }
    interface LongerDeadline { @Deadline(millis = 20) String work(); }
    interface DeadlineDiamond extends ShortDeadline, LongerDeadline { }
    static class DeadlineWorker implements DeadlineDiamond { public String work() { return "value"; } }
    interface FireRetry { @OneWay @Retry(maxAttempts = 0) void work(); }
    static class FireRetryWorker implements FireRetry { public void work() { } }
    @org.j7m.middleware.annotations.LongRunning @org.j7m.middleware.annotations.Cancellable
    interface LongWork { String work(); }
    static class LongWorker implements LongWork { public String work() { return "value"; } }

    private static ManagedServiceContract contract(final Class<?> type) throws Exception {
        var method = type.getMethod("work");
        return new ManagedServiceContract("calling", 1, new ManagedMethod(method.getDeclaringClass().getName(), "work",
                Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(), method.getReturnType().getName()));
    }

    @Test void typedAsyncValidatesButCannotExecuteWithoutActualHostLifetime() throws Exception {
        var policy = new ManagedCallingPolicy();
        policy.validate(contract(AsyncContract.class), AsyncWorker.class, AsyncContract.class);
        assertTrue(policy.annotationTypes().contains(Async.class));
        try (var binding = policy.bind(contract(AsyncContract.class), AsyncWorker.class, AsyncContract.class)) {
            assertThrows(IllegalStateException.class, () -> binding.invoke(
                    org.j7m.middleware.application.api.InvocationContext.empty(), new Object[0], () -> "must not run"));
        }
    }

    @Test void invalidShapesAndOperationalConflictsRejectBeforeAnyInvocation() throws Exception {
        var policy = new ManagedCallingPolicy();
        Class<?>[][] invalid = {{Conflict.class, ConflictingWorker.class}, {Unsafe.class, UnsafeWorker.class},
                {WrongAsync.class, WrongAsyncWorker.class}, {RawAsync.class, RawAsyncWorker.class},
                {WrongOneWay.class, WrongOneWayWorker.class}, {InvalidDeadline.class, InvalidDeadlineWorker.class},
                {SyncContract.class, HiddenPolicy.class}};
        for (var pair : invalid) {
            assertThrows(IllegalArgumentException.class, () -> policy.validate(contract(pair[0]), pair[1], pair[0]), pair[0].getName());
        }
    }

    @Test void conflictingInheritedDeadlineAndEvenZeroRetryOnOneWayFailBeforeConstruction() throws Exception {
        var policy = new ManagedCallingPolicy();
        assertEquals("CONFLICTING_MANAGED_DEADLINE", assertThrows(IllegalArgumentException.class,
                () -> policy.validate(contract(DeadlineDiamond.class), DeadlineWorker.class, DeadlineDiamond.class)).getMessage());
        assertEquals("ONEWAY_RETRY_CONFLICT", assertThrows(IllegalArgumentException.class,
                () -> policy.validate(contract(FireRetry.class), FireRetryWorker.class, FireRetry.class)).getMessage());
    }

    @Test void longRunningDeclarationNeverExtendsIncomingTenMillisecondBudget() throws Exception {
        var policy = new ManagedCallingPolicy();
        policy.validate(contract(LongWork.class), LongWorker.class, LongWork.class);
        var clock = new java.util.concurrent.atomic.AtomicLong();
        try (var container = ManagedContainer.activate(java.util.List.of())) {
            var lifetime = new ManagedInvocationLifetime(container.openCall(),
                    org.j7m.middleware.application.api.InvocationContext.empty(), null, java.time.Duration.ofMillis(10),
                    Runnable::run, 2, 2, () -> { }, clock::get);
            lifetime.call(() -> {
                assertEquals(java.time.Duration.ofMillis(10), org.j7m.middleware.application.api.J7mContext.currentInvocation().control().remainingBudget());
                clock.addAndGet(java.time.Duration.ofMillis(6).toNanos());
                assertEquals(java.time.Duration.ofMillis(4), org.j7m.middleware.application.api.J7mContext.currentInvocation().control().remainingBudget());
                return "value";
            });
            lifetime.close();
            lifetime.physicalCompletion().toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test void retryRequiresActualTrustedClientRegistryAndNeverExecutesServerReplay() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> new ManagedCallingPolicy().validate(
                contract(RetryContract.class), RetryWorker.class, RetryContract.class));
        var retry = new org.j7m.middleware.application.api.ClientCallInterceptor() {
            @Override public void validate(final Class<?> type) { assertEquals(RetryContract.class, type); calls.incrementAndGet(); }
            @Override public Object invoke(final org.j7m.middleware.application.api.ClientCall call,
                    final org.j7m.middleware.application.api.ClientAttempt attempt) { fail("server must not invoke client retry"); return null; }
        };
        new ManagedCallingPolicy(retry).validate(contract(RetryContract.class), RetryWorker.class, RetryContract.class);
        assertEquals(1, calls.get());
    }
}
