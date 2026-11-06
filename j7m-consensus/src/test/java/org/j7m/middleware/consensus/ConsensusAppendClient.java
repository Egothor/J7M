// Copyright (c) 2026, Leo Galambos and the Egothor team
// SPDX-License-Identifier: BSD-4-Clause
// Licensed under the BSD 4-Clause License; see LICENSE for the full terms.

package org.j7m.middleware.consensus;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Test client for ordinary deduplicated appends across transient leader changes. */
final class ConsensusAppendClient {
    @FunctionalInterface interface Sender {
        CompletableFuture<String> send(String node, String command);
    }
    @FunctionalInterface interface Router {
        /** Uses the longest joined deadline; each append still waits only for its own budget. */
        String leader(String previous, LongSupplier deadlineNanos) throws Exception;
    }

    private record Route(String node, long generation) { }
    private static final class Refresh {
        final Route previous;
        final AtomicLong deadline;
        final CompletableFuture<Route> result = new CompletableFuture<>();
        Refresh(Route previous, long deadline) { this.previous = previous; this.deadline = new AtomicLong(deadline); }
    }

    private final Sender sender;
    private final Router router;
    private final Object routing = new Object();
    private volatile Route route;
    private Refresh refresh;

    ConsensusAppendClient(String leader, Sender sender, Router router) {
        this.route = new Route(leader, 0); this.sender = sender; this.router = router;
    }

    CompletableFuture<String> append(String requestId, long value, Duration budget) {
        String command = "APPEND " + requestId + " " + value;
        long deadline = System.nanoTime() + budget.toNanos();
        CompletableFuture<String> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                Route target = route;
                while (true) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new TimeoutException("append client deadline: " + requestId);
                    try {
                        result.complete(sender.send(target.node(), command).get(remaining, TimeUnit.NANOSECONDS));
                        return;
                    } catch (ExecutionException failure) {
                        Throwable cause = failure.getCause();
                        if (!(cause instanceof IOException) || !transientOutcome(cause.getMessage())) throw failure;
                        // UNKNOWN may already be committed: retry the identical request, never a new ID.
                        target = refreshed(target, deadline);
                    }
                }
            } catch (Throwable failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                result.completeExceptionally(failure instanceof ExecutionException ? failure.getCause() : failure);
            }
        });
        return result;
    }

    private Route refreshed(Route failed, long deadline) throws Exception {
        Refresh selected;
        boolean start = false;
        synchronized (routing) {
            if (System.nanoTime() >= deadline) throw new TimeoutException("append routing deadline");
            if (route.generation() != failed.generation()) return route;
            if (refresh == null) { refresh = new Refresh(failed, deadline); start = true; }
            selected = refresh;
            selected.deadline.accumulateAndGet(deadline, Math::max);
        }
        if (start) Thread.ofVirtual().start(() -> discover(selected));
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("append routing deadline");
        return selected.result.get(remaining, TimeUnit.NANOSECONDS);
    }

    private void discover(Refresh selected) {
        try {
            String leader;
            while (true) {
                long attemptedDeadline = selected.deadline.get();
                if (System.nanoTime() >= attemptedDeadline) throw new TimeoutException("leader discovery deadline");
                try { leader = router.leader(selected.previous.node(), selected.deadline::get); break; }
                catch (TimeoutException expiredRpc) {
                    // A longer-lived waiter may have joined while one RPC used the old deadline.
                    if (selected.deadline.get() <= attemptedDeadline || System.nanoTime() >= selected.deadline.get()) throw expiredRpc;
                }
            }
            if (System.nanoTime() >= selected.deadline.get()) throw new TimeoutException("leader discovery deadline");
            Route current;
            synchronized (routing) {
                if (refresh == selected && route.generation() == selected.previous.generation()) {
                    // The same node in a new term still establishes a new routing generation.
                    route = new Route(leader, route.generation() + 1);
                    refresh = null;
                }
                current = route;
            }
            selected.result.complete(current);
        } catch (Throwable failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            synchronized (routing) { if (refresh == selected) refresh = null; }
            selected.result.completeExceptionally(failure);
        }
    }

    private static boolean transientOutcome(String message) {
        return message != null && (message.startsWith("UNKNOWN:") || message.startsWith("NOT_LEADER:") || message.startsWith("UNAVAILABLE:"));
    }
}
