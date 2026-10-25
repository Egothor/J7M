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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finite trusted host capacity for explicitly tracked invocation children and codec continuations.
 * Threads expire while idle and carry the host class loader, never an application's class loader.
 * Limits are admission bounds, not a promise of parallel CALL execution within one invocation.
 * @param maximumWorkers maximum host workers executing serial invocation actors
 * @param maximumQueuedActors maximum pending actors across all invocations
 * @param maximumTasksPerInvocation maximum lifetime child and continuation admissions
 * @param maximumPendingPerInvocation maximum pending tasks for one invocation
 */
public record ManagedInvocationSettings(int maximumWorkers, int maximumQueuedActors,
        int maximumTasksPerInvocation, int maximumPendingPerInvocation) {
    /**
     * Validates finite positive capacities.
     * @throws IllegalArgumentException for nonpositive, excessive or inconsistent capacities
     */
    public ManagedInvocationSettings {
        if (maximumWorkers < 1 || maximumWorkers > 256 || maximumQueuedActors < 1 || maximumQueuedActors > 65536
                || maximumTasksPerInvocation < 1 || maximumTasksPerInvocation > 65536
                || maximumPendingPerInvocation < 1 || maximumPendingPerInvocation > maximumTasksPerInvocation) {
            throw new IllegalArgumentException("INVALID_INVOCATION_CAPACITY");
        }
    }

    /**
     * Returns the bounded development-qualified host profile.
     * @return eight workers, 256 waiting actors and at most 1024 tasks/128 pending per invocation
     */
    public static ManagedInvocationSettings defaults() { return new ManagedInvocationSettings(8, 256, 1024, 128); }

    /**
     * Creates a host-owned finite executor. Rejection never starts an unaccounted fallback thread.
     * The owner may shut down this executor only after all retained invocations have drained.
     * @return daemon executor with one-second idle expiry and explicit overload rejection
     */
    public ThreadPoolExecutor newExecutor() {
        final AtomicInteger sequence = new AtomicInteger();
        final ThreadPoolExecutor result = new ThreadPoolExecutor(maximumWorkers, maximumWorkers, 1L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(maximumQueuedActors), work -> {
                    final Thread thread = Thread.ofPlatform().daemon(true).inheritInheritableThreadLocals(false)
                            .name("j7m-managed-invocation-" + sequence.incrementAndGet()).unstarted(work);
                    thread.setContextClassLoader(ManagedInvocationSettings.class.getClassLoader());
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        result.allowCoreThreadTimeOut(true);
        return result;
    }
}
