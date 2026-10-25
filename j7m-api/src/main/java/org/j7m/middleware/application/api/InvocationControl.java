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

package org.j7m.middleware.application.api;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * Host-owned controls for one managed invocation, safe to consult across its asynchronous work.
 * Implementations are thread-safe and retain the same logical deadline, cancellation state and
 * physical-work tracker. A cancellation request or expired caller reply never proves that an
 * application effect was undone or that its worker exited. This handle grants no new principal,
 * resource permission or durable deduplication guarantee.
 */
public interface InvocationControl {
    /**
     * Returns the nonnegative remaining budget of this invocation's original monotonic deadline.
     * A child operation may shorten this budget and must never restart or extend it.
     * @return remaining duration, or zero when expired or outside an invocation
     */
    Duration remainingBudget();

    /**
     * Reports a cooperative cancellation request without promising interruption or rollback.
     * Application work must check this flag or use its own cooperative termination mechanism.
     * @return true when cancellation of the current invocation was requested
     */
    boolean cancellationRequested();

    /**
     * Submits bounded host-tracked child work with this invocation's context and ownership.
     * The host retains physical ownership until the callable actually exits, including its context
     * cleanup. Cancelling the returned stage does not establish worker exit and does not release
     * admission permits or call-scoped resources early. Submission after physical release is rejected.
     * @param <T> result type
     * @param work child work; must not be null
     * @return child result stage; cancellation is cooperative and does not imply effect reversal
     * @throws NullPointerException if work is null
     * @throws IllegalStateException when no invocation is active or its ownership has ended
     * @throws java.util.concurrent.RejectedExecutionException when the host's finite capacity is full
     */
    <T> CompletionStage<T> submit(Callable<T> work);

    /**
     * Returns a bounded executor that attaches this invocation's safe contexts for each task.
     * Arbitrary application executors do not inherit managed context automatically. Host-tracked
     * execution retains physical ownership until task exit; the returned executor is borrowed and
     * must not close the host executor. Execution after physical release is rejected.
     * @return invocation-owned contextual executor
     * @throws IllegalStateException when no invocation is active or its ownership has ended
     */
    Executor contextualExecutor();

    /**
     * Returns the immutable control used outside managed invocation dispatch.
     * It reports zero remaining budget and no cancellation request, and rejects work submission
     * or executor access. No application callable is evaluated by this control.
     * @return shared thread-safe unavailable control
     */
    static InvocationControl unavailable() { return UnavailableInvocationControl.INSTANCE; }
}

/**
 * Fail-closed default; it never allocates workers or captures application context.
 */
enum UnavailableInvocationControl implements InvocationControl {
    INSTANCE;
    @Override public Duration remainingBudget() { return Duration.ZERO; }
    @Override public boolean cancellationRequested() { return false; }
    @Override public <T> CompletionStage<T> submit(Callable<T> work) {
        Objects.requireNonNull(work, "work");
        throw new IllegalStateException("NO_ACTIVE_INVOCATION");
    }
    @Override public Executor contextualExecutor() { throw new IllegalStateException("NO_ACTIVE_INVOCATION"); }
}
