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

package org.j7m.middleware.durablelog;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.j7m.middleware.api.durablelog.GroupCommitPolicy;

/**
 * Deterministic baseline group-commit coordinator.
 */
final class GroupCommitCoordinator implements AutoCloseable {
    private final GroupCommitPolicy policy;
    private final Runnable delayFlushAction;
    private final ScheduledExecutorService scheduler;
    private int pendingAppends;
    private ScheduledFuture<?> scheduledFlush;
    private boolean closed;

    GroupCommitCoordinator(final GroupCommitPolicy policy, final Runnable delayFlushAction) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.delayFlushAction = Objects.requireNonNull(delayFlushAction, "delayFlushAction");
        this.scheduler = policy.maxDelay().isZero() ? null : Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "j7m-durable-log-group-commit");
            thread.setDaemon(true);
            return thread;
        });
        this.pendingAppends = 0;
        this.closed = false;
    }

    synchronized boolean afterAppend() {
        if (this.closed) {
            return false;
        }
        this.pendingAppends++;
        if (this.pendingAppends == 1) {
            this.scheduleDelayFlush();
        }
        if (this.pendingAppends >= this.policy.maxAppends()) {
            return this.consumePendingForExplicitFlush();
        }
        return false;
    }

    synchronized boolean consumePendingForDelayedFlush() {
        if (this.closed || this.pendingAppends == 0) {
            return false;
        }
        this.pendingAppends = 0;
        this.scheduledFlush = null;
        return true;
    }

    synchronized boolean consumePendingForExplicitFlush() {
        if (this.pendingAppends == 0) {
            return false;
        }
        this.pendingAppends = 0;
        if (this.scheduledFlush != null) {
            this.scheduledFlush.cancel(false);
            this.scheduledFlush = null;
        }
        return true;
    }

    synchronized boolean hasPending() {
        return this.pendingAppends > 0;
    }

    synchronized boolean schedulerShutdown() {
        return this.scheduler == null || this.scheduler.isShutdown();
    }

    private void scheduleDelayFlush() {
        Duration delay = this.policy.maxDelay();
        if (delay.isZero() || this.scheduler == null || this.scheduledFlush != null) {
            return;
        }
        long delayNanos = Math.max(1L, delay.toNanos());
        this.scheduledFlush = this.scheduler.schedule(this.delayFlushAction, delayNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public synchronized void close() {
        this.closed = true;
        this.pendingAppends = 0;
        if (this.scheduledFlush != null) {
            this.scheduledFlush.cancel(false);
            this.scheduledFlush = null;
        }
        if (this.scheduler != null) {
            this.scheduler.shutdownNow();
        }
    }
}
