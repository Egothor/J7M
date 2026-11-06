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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class ConsensusAppendClientTest {
    @Test void concurrentFailuresShareTheInFlightDiscovery() throws Exception {
        var pending = new ConcurrentHashMap<String, CompletableFuture<String>>();
        var initial = new CountDownLatch(32); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var routes = new AtomicInteger();
        var client = new ConsensusAppendClient("a", (node, command) -> {
            if (node.equals("b")) return CompletableFuture.completedFuture(command);
            var reply = new CompletableFuture<String>(); pending.put(command, reply); initial.countDown(); return reply;
        }, (previous, deadline) -> {
            routes.incrementAndGet(); entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return "b";
        });
        var results = new ArrayList<CompletableFuture<String>>();
        for (int i = 0; i < 32; i++) results.add(client.append("inflight-" + i, i, Duration.ofSeconds(10)));
        assertTrue(initial.await(5, TimeUnit.SECONDS));
        pending.get("APPEND inflight-0 0").completeExceptionally(new IOException("NOT_LEADER: b"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        pending.values().forEach(reply -> reply.completeExceptionally(new IOException("UNKNOWN: command outcome")));
        release.countDown();
        CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        assertEquals(1, routes.get());
        for (int i = 0; i < 32; i++) assertEquals("APPEND inflight-" + i + " " + i, results.get(i).join());
    }

    @Test void sameNodeRefreshStillAdvancesTheRoutingGeneration() throws Exception {
        var pending = new ConcurrentHashMap<String, CompletableFuture<String>>();
        var sends = new ConcurrentHashMap<String, AtomicInteger>(); var routes = new AtomicInteger();
        var client = new ConsensusAppendClient("a", (node, command) -> {
            assertEquals("a", node);
            if (sends.computeIfAbsent(command, ignored -> new AtomicInteger()).incrementAndGet() > 1)
                return CompletableFuture.completedFuture(command);
            var reply = new CompletableFuture<String>(); pending.put(command, reply); return reply;
        }, (previous, deadline) -> { routes.incrementAndGet(); return "a"; });
        var first = client.append("first", 1, Duration.ofSeconds(5));
        var second = client.append("second", 2, Duration.ofSeconds(5));
        await(() -> pending.size() == 2);
        pending.get("APPEND first 1").completeExceptionally(new IOException("UNKNOWN: leadership changed"));
        assertEquals("APPEND first 1", first.get(5, TimeUnit.SECONDS));
        pending.get("APPEND second 2").completeExceptionally(new IOException("UNKNOWN: leadership changed"));
        assertEquals("APPEND second 2", second.get(5, TimeUnit.SECONDS)); assertEquals(1, routes.get());
        var third = client.append("new-generation", 3, Duration.ofSeconds(5));
        await(() -> pending.containsKey("APPEND new-generation 3"));
        pending.get("APPEND new-generation 3").completeExceptionally(new IOException("UNAVAILABLE: no quorum"));
        assertEquals("APPEND new-generation 3", third.get(5, TimeUnit.SECONDS)); assertEquals(2, routes.get());
    }

    @Test void failedDiscoveryDoesNotPoisonTheNextAppend() throws Exception {
        var routes = new AtomicInteger();
        var client = new ConsensusAppendClient("a", (node, command) -> node.equals("b")
                ? CompletableFuture.completedFuture(command)
                : CompletableFuture.failedFuture(new IOException("NOT_LEADER: b")), (previous, deadline) -> {
            if (routes.incrementAndGet() == 1) throw new IOException("discovery unavailable");
            return "b";
        });
        var failure = assertThrows(ExecutionException.class,
                () -> client.append("first", 1, Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS));
        assertEquals("discovery unavailable", failure.getCause().getMessage());
        assertEquals("APPEND second 2", client.append("second", 2, Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS));
        assertEquals(2, routes.get());
    }

    @Test void shortWaiterExpiresWithoutCancellingTheLongerDiscovery() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var routes = new AtomicInteger();
        var client = new ConsensusAppendClient("a", (node, command) -> node.equals("b")
                ? CompletableFuture.completedFuture(command)
                : CompletableFuture.failedFuture(new IOException("NOT_LEADER: b")), (previous, deadline) -> {
            routes.incrementAndGet(); entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return "b";
        });
        var longer = client.append("long", 1, Duration.ofSeconds(5));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var shorter = client.append("short", 2, Duration.ofMillis(100));
        var failure = assertThrows(ExecutionException.class, () -> shorter.get(2, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause()); assertFalse(longer.isDone());
        release.countDown();
        assertEquals("APPEND long 1", longer.get(2, TimeUnit.SECONDS)); assertEquals(1, routes.get());
    }

    @Test void longerWaiterCanOutliveAnRpcStartedWithTheShorterDeadline() throws Exception {
        var entered = new CompletableFuture<LongSupplier>(); var release = new CountDownLatch(1); var routes = new AtomicInteger();
        var client = new ConsensusAppendClient("a", (node, command) -> node.equals("b")
                ? CompletableFuture.completedFuture(command)
                : CompletableFuture.failedFuture(new IOException("NOT_LEADER: b")), (previous, deadline) -> {
            if (routes.incrementAndGet() == 1) {
                entered.complete(deadline); assertTrue(release.await(5, TimeUnit.SECONDS));
                throw new TimeoutException("RPC captured the original shorter deadline");
            }
            return "b";
        });
        var shorter = client.append("short-first", 1, Duration.ofMillis(200));
        LongSupplier shared = entered.get(2, TimeUnit.SECONDS); long original = shared.getAsLong();
        var longer = client.append("long-second", 2, Duration.ofSeconds(5));
        await(() -> shared.getAsLong() > original);
        var failure = assertThrows(ExecutionException.class, () -> shorter.get(2, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause()); assertFalse(longer.isDone());
        release.countDown();
        assertEquals("APPEND long-second 2", longer.get(2, TimeUnit.SECONDS)); assertEquals(2, routes.get());
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.yield();
        assertTrue(condition.getAsBoolean(), "The controlled asynchronous transition did not occur");
    }

    @Test void tenThousandLateFailuresReuseTheCompletedRoutingGeneration() throws Exception {
        int count = 10_000;
        var pending = new ConcurrentHashMap<String, CompletableFuture<String>>();
        var initial = new CountDownLatch(count);
        var routes = new AtomicInteger();
        var client = new ConsensusAppendClient("a", (node, command) -> {
            if (node.equals("a")) {
                var reply = new CompletableFuture<String>();
                assertNull(pending.put(command, reply)); initial.countDown(); return reply;
            }
            assertEquals("b", node);
            return CompletableFuture.completedFuture(command);
        }, (previous, deadline) -> { routes.incrementAndGet(); return "b"; });
        var requests = new ArrayList<CompletableFuture<String>>();
        for (int i = 0; i < count; i++) requests.add(client.append("request-" + i, 1, Duration.ofSeconds(30)));
        assertTrue(initial.await(10, TimeUnit.SECONDS));
        pending.get("APPEND request-0 1").completeExceptionally(new IOException("NOT_LEADER: b"));
        assertEquals("APPEND request-0 1", requests.getFirst().get(10, TimeUnit.SECONDS));
        pending.values().forEach(reply -> reply.completeExceptionally(new IOException("UNKNOWN: leadership changed")));
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        for (int i = 0; i < count; i++) assertEquals("APPEND request-" + i + " 1", requests.get(i).join());
        assertEquals(1, routes.get(), "Late failures from one old generation must share its completed refresh");
    }

    @Test void retriesTransientOutcomesWithIdenticalRequestAndDeduplicatedApplication() throws Exception {
        for (String outcome : List.of("UNKNOWN: leadership changed", "NOT_LEADER: b", "UNAVAILABLE: quorum confirmation expired")) {
            List<String> commands = new ArrayList<>(); List<String> targets = new ArrayList<>();
            Map<String, String> committed = new ConcurrentHashMap<>(); AtomicInteger applications = new AtomicInteger();
            var client = new ConsensusAppendClient("a", (node, command) -> {
                commands.add(command); targets.add(node);
                String position = committed.computeIfAbsent(command, ignored -> Integer.toString(applications.incrementAndGet()));
                if (commands.size() == 1) return CompletableFuture.failedFuture(new IOException(outcome));
                return CompletableFuture.completedFuture(position);
            }, (previous, deadline) -> { assertEquals("a", previous); assertTrue(deadline.getAsLong() > System.nanoTime()); return "b"; });
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
