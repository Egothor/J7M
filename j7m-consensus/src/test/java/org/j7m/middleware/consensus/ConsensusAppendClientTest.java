// Copyright (c) 2026, Leo Galambos and the Egothor team
// SPDX-License-Identifier: BSD-4-Clause
// Licensed under the BSD 4-Clause License; see LICENSE for the full terms.

package org.j7m.middleware.consensus;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConsensusAppendClientTest {
    @Test void retriesTransientOutcomesWithIdenticalRequestAndDeduplicatedApplication() throws Exception {
        for (String outcome : List.of("UNKNOWN: leadership changed", "NOT_LEADER: b", "UNAVAILABLE: quorum confirmation expired")) {
            List<String> commands = new ArrayList<>(); List<String> targets = new ArrayList<>();
            Map<String, String> committed = new ConcurrentHashMap<>(); AtomicInteger applications = new AtomicInteger();
            var client = new ConsensusAppendClient("a", (node, command) -> {
                commands.add(command); targets.add(node);
                String position = committed.computeIfAbsent(command, ignored -> Integer.toString(applications.incrementAndGet()));
                if (commands.size() == 1) return CompletableFuture.failedFuture(new IOException(outcome));
                return CompletableFuture.completedFuture(position);
            }, (previous, deadline) -> { assertEquals("a", previous); assertTrue(deadline > System.nanoTime()); return "b"; });
            assertEquals("1", client.append("stable-id", 7, Duration.ofSeconds(1)).get(2, TimeUnit.SECONDS));
            assertEquals(List.of("APPEND stable-id 7", "APPEND stable-id 7"), commands);
            assertEquals(List.of("a", "b"), targets); assertEquals(1, applications.get());
        }
    }

    @Test void permanentFailureIsNotRetried() {
        AtomicInteger sends = new AtomicInteger(); AtomicInteger routes = new AtomicInteger();
        var client = new ConsensusAppendClient("a", (node, command) -> {
            sends.incrementAndGet(); return CompletableFuture.failedFuture(new IOException("CONFLICT: request id"));
        }, (previous, deadline) -> { routes.incrementAndGet(); return "b"; });
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> client.append("conflict", 1, Duration.ofSeconds(1)).get(2, TimeUnit.SECONDS));
        assertEquals("CONFLICT: request id", failure.getCause().getMessage());
        assertEquals(1, sends.get()); assertEquals(0, routes.get());
    }

    @Test void expiredBudgetDoesNotSendOrRoute() {
        AtomicInteger sends = new AtomicInteger(); AtomicInteger routes = new AtomicInteger();
        var client = new ConsensusAppendClient("a", (node, command) -> {
            sends.incrementAndGet(); return new CompletableFuture<>();
        }, (previous, deadline) -> { routes.incrementAndGet(); return "b"; });
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> client.append("expired", 1, Duration.ZERO).get(2, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertEquals(0, sends.get()); assertEquals(0, routes.get());
    }

    @Test void routingDeadlineStopsRetryingAnUnknownOutcome() {
        AtomicInteger sends = new AtomicInteger(); AtomicInteger routes = new AtomicInteger();
        var client = new ConsensusAppendClient("a", (node, command) -> {
            sends.incrementAndGet(); return CompletableFuture.failedFuture(new IOException("UNKNOWN: command outcome"));
        }, (previous, deadline) -> { routes.incrementAndGet(); throw new TimeoutException("no quorum before deadline"); });
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> client.append("uncertain", 1, Duration.ofSeconds(1)).get(2, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertEquals(1, sends.get()); assertEquals(1, routes.get());
    }
}
