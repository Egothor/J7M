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

/** Test client for ordinary deduplicated appends across transient leader changes. */
final class ConsensusAppendClient {
    @FunctionalInterface interface Sender {
        CompletableFuture<String> send(String node, String command);
    }
    @FunctionalInterface interface Router {
        String leader(String previous, long deadlineNanos) throws Exception;
    }

    private final Sender sender;
    private final Router router;
    private volatile String leader;

    ConsensusAppendClient(String leader, Sender sender, Router router) {
        this.leader = leader; this.sender = sender; this.router = router;
    }

    CompletableFuture<String> append(String requestId, long value, Duration budget) {
        String command = "APPEND " + requestId + " " + value;
        long deadline = System.nanoTime() + budget.toNanos();
        CompletableFuture<String> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                String target = leader;
                while (true) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new TimeoutException("append client deadline: " + requestId);
                    try {
                        result.complete(sender.send(target, command).get(remaining, TimeUnit.NANOSECONDS));
                        return;
                    } catch (ExecutionException failure) {
                        Throwable cause = failure.getCause();
                        if (!(cause instanceof IOException) || !transientOutcome(cause.getMessage())) throw failure;
                        // UNKNOWN may already be committed: retry the identical request, never a new ID.
                        target = router.leader(target, deadline);
                        leader = target;
                    }
                }
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                result.completeExceptionally(failure instanceof ExecutionException ? failure.getCause() : failure);
            }
        });
        return result;
    }

    private static boolean transientOutcome(String message) {
        return message != null && (message.startsWith("UNKNOWN:") || message.startsWith("NOT_LEADER:") || message.startsWith("UNAVAILABLE:"));
    }
}
