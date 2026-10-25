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

package org.j7m.middleware.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.j7m.middleware.annotations.OrderedBy;
import org.j7m.middleware.annotations.ShardKey;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.scheduler.InMemorySchedulerOptions;
import org.j7m.middleware.scheduler.InMemoryWorkloadScheduler;
import org.j7m.middleware.scheduler.StableHashShardRoutingStrategy;
import org.j7m.middleware.scheduler.WorkloadAdmin;
import org.j7m.middleware.security.api.AuthorizationDecision;
import org.j7m.middleware.security.api.AuthorizationPolicy;
import org.j7m.middleware.security.api.Credential;
import org.j7m.middleware.security.api.PermissionAction;
import org.j7m.middleware.security.api.PermissionRequest;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.ResourcePolicy;
import org.j7m.middleware.security.api.ResourcePolicyAction;
import org.j7m.middleware.security.api.ResourcePolicyDecision;
import org.j7m.middleware.security.api.ResourcePolicyRequest;
import org.j7m.middleware.security.api.SecurityConfiguration;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityContexts;
import org.j7m.middleware.security.api.SecurityMode;
import org.j7m.middleware.security.api.TransportSecurityLevel;
import org.j7m.middleware.security.api.TrustDomain;
import org.j7m.middleware.security.defaults.AllowAllAuthorizationPolicy;
import org.j7m.middleware.security.defaults.DefaultRedactor;
import org.j7m.middleware.security.defaults.InMemoryAuditSink;
import org.j7m.middleware.security.defaults.MutableCredentialProvider;
import org.j7m.middleware.workload.api.AsyncResult;
import org.j7m.middleware.workload.api.BackpressureMode;
import org.j7m.middleware.workload.api.FanOutRequest;
import org.j7m.middleware.workload.api.FanOutResult;
import org.j7m.middleware.workload.api.FencingToken;
import org.j7m.middleware.workload.api.LeaseOwner;
import org.j7m.middleware.workload.api.ScatterGatherRequest;
import org.j7m.middleware.workload.api.ScatterGatherResult;
import org.j7m.middleware.workload.api.ShardRoute;
import org.j7m.middleware.workload.api.StreamHandle;
import org.j7m.middleware.workload.api.TaskCheckpoint;
import org.j7m.middleware.workload.api.TaskDescriptor;
import org.j7m.middleware.workload.api.TaskFailure;
import org.j7m.middleware.workload.api.TaskLease;
import org.j7m.middleware.workload.api.TaskOptions;
import org.j7m.middleware.workload.api.TaskProgress;
import org.j7m.middleware.workload.api.TaskRef;
import org.j7m.middleware.workload.api.TaskState;
import org.j7m.middleware.workload.api.TaskStatus;
import org.j7m.middleware.workload.api.TaskSubmission;
import org.j7m.middleware.workload.api.WorkloadHandle;
import org.junit.jupiter.api.Test;

/**
 * Phase 4 workload extension baseline tests.
 */
public final class Phase4WorkloadIntegrationTest {
    @Test
    public void schedulerCompletesFailsAndReportsStatus() throws Exception {
        withCaller(() -> {
            InMemoryAuditSink audit = new InMemoryAuditSink(32);
            try (InMemoryWorkloadScheduler scheduler = scheduler(audit, allowAuth(), allowResource(), Clock.systemUTC(), 16, 2, 8, 128)) {
            WorkloadHandle handle = scheduler.submit(submission("basic", TaskOptions.defaults()));
            LeaseOwner owner = owner("worker-a");
            TaskLease lease = scheduler.acquireLease(handle.ref().taskId(), owner, Duration.ofSeconds(5L));

            scheduler.reportProgress(handle.ref().taskId(), owner, lease.fencingToken(), new TaskProgress(50.0D, "half", Instant.now(), Map.of()));
            TaskCheckpoint checkpoint = scheduler.checkpoint(handle.ref().taskId(), owner, lease.fencingToken(), Map.of("offset", "10"));
            scheduler.complete(handle.ref().taskId(), owner, lease.fencingToken(), "done");

            assertEquals(1L, checkpoint.version());
            assertEquals(TaskState.COMPLETED, scheduler.status(handle.ref().taskId()).state());
            assertEquals("done", scheduler.status(handle.ref().taskId()).successfulResult().orElseThrow().value());
            assertEquals(1L, scheduler.metrics().completed());
            assertTrue(audit.eventCount() >= 2L);

            WorkloadHandle failed = scheduler.submit(submission("failed", TaskOptions.defaults()));
            TaskLease failedLease = scheduler.acquireLease(failed.ref().taskId(), owner, Duration.ofSeconds(5L));
            scheduler.fail(failed.ref().taskId(), owner, failedLease.fencingToken(),
                    new TaskFailure(MiddlewareErrorCode.REMOTE_EXCEPTION, "safe failure", true, Instant.now(), Map.of()));
            assertEquals(TaskState.FAILED, scheduler.status(failed.ref().taskId()).state());
            }
        });
    }

    @Test
    public void leaseExpiryAndFencingRejectStaleOwners() throws Exception {
        withCaller(() -> {
            MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
            InMemoryAuditSink audit = new InMemoryAuditSink(32);
            try (InMemoryWorkloadScheduler scheduler = scheduler(audit, allowAuth(), allowResource(), clock, 16, 2, 8, 128)) {
            WorkloadHandle handle = scheduler.submit(submission("lease", TaskOptions.defaults()));
            LeaseOwner oldOwner = owner("old");
            TaskLease oldLease = scheduler.acquireLease(handle.ref().taskId(), oldOwner, Duration.ofSeconds(1L));
            clock.advance(Duration.ofSeconds(2L));

            MiddlewareException renewFailure = assertThrows(MiddlewareException.class,
                    () -> scheduler.renewLease(handle.ref().taskId(), oldOwner, oldLease.fencingToken(), Duration.ofSeconds(1L)));
            assertEquals(MiddlewareErrorCode.STALE_FENCING_TOKEN, renewFailure.code());

            LeaseOwner newOwner = owner("new");
            TaskLease newLease = scheduler.acquireLease(handle.ref().taskId(), newOwner, Duration.ofSeconds(5L));
            assertTrue(newLease.fencingToken().value() > oldLease.fencingToken().value());
            assertThrows(MiddlewareException.class,
                    () -> scheduler.complete(handle.ref().taskId(), oldOwner, oldLease.fencingToken(), "stale"));
            scheduler.complete(handle.ref().taskId(), newOwner, newLease.fencingToken(), "fresh");

            assertEquals(TaskState.COMPLETED, scheduler.status(handle.ref().taskId()).state());
            assertTrue(audit.recentEvents().stream().anyMatch(event -> event.reason().equals("stale-fencing-token")));
            }
        });
    }

    @Test
    public void asyncOneWayCancellationAndFailureUseMiddlewareModel() throws Exception {
        withCaller(() -> {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean sawCancellation = new AtomicBoolean();
            AtomicReference<FencingToken> tokenSeen = new AtomicReference<>();
            try (InMemoryWorkloadScheduler scheduler = scheduler(new InMemoryAuditSink(32), allowAuth(), allowResource(), Clock.systemUTC(), 16, 2, 8, 128)) {
            AsyncResult<Object> result = scheduler.submitAsync(submission("async", TaskOptions.defaults()), (context, cancellationToken) -> {
                tokenSeen.set(context.lease().fencingToken());
                entered.countDown();
                while (!cancellationToken.cancellationRequested()) {
                    if (release.await(20L, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                }
                sawCancellation.set(cancellationToken.cancellationRequested());
                return "async-result";
            });
            assertTrue(entered.await(2L, TimeUnit.SECONDS));
            result.cancel();
            release.countDown();
            assertNotNull(tokenSeen.get());
            result.await(Duration.ofSeconds(2L));
            assertTrue(sawCancellation.get() || scheduler.status(result.ref().taskId()).state() != TaskState.RUNNING);

            CountDownLatch oneWayRan = new CountDownLatch(1);
            TaskRef oneWay = scheduler.submitOneWay(submission("one-way", TaskOptions.defaults()), (context, cancellationToken) -> {
                oneWayRan.countDown();
                return "ignored";
            });
            assertTrue(oneWayRan.await(2L, TimeUnit.SECONDS));
            assertEquals(TaskState.COMPLETED, awaitState(scheduler, oneWay, TaskState.COMPLETED));

            AsyncResult<Object> failed = scheduler.submitAsync(submission("async-failure", TaskOptions.defaults()), (context, cancellationToken) -> {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "safe async failure");
            });
            MiddlewareException failure = assertThrows(MiddlewareException.class, () -> failed.await(Duration.ofSeconds(2L)));
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, failure.code());
            assertEquals(1L, scheduler.metrics().oneWayStarted());
            assertTrue(scheduler.metrics().asyncStarted() >= 2L);
            assertTrue(new WorkloadAdmin(scheduler).formatStatus().contains("WorkloadStatus"));
            }
        });
    }

    @Test
    public void streamingHonorsBackpressureCancellationAndItemLimits() throws Exception {
        withCaller(() -> {
            try (InMemoryWorkloadScheduler scheduler = scheduler(new InMemoryAuditSink(32), allowAuth(), allowResource(), Clock.systemUTC(), 16, 2, 1, 64)) {
            StreamHandle<String> stream = scheduler.stream(submission("stream", TaskOptions.defaults()), (context, cancellationToken, emitter) -> {
                emitter.emit("one", Duration.ofSeconds(1L));
                emitter.emit("two", Duration.ofSeconds(1L));
            });

            assertEquals("one", stream.poll(Duration.ofSeconds(2L)).orElseThrow());
            assertEquals("two", stream.poll(Duration.ofSeconds(2L)).orElseThrow());
            stream.cancel();
            assertTrue(stream.closed());

            StreamHandle<String> oversize = scheduler.stream(submission("oversize", TaskOptions.defaults()), (context, cancellationToken, emitter) ->
                    emitter.emit("this-item-is-intentionally-larger-than-the-canonical-stream-item-limit", Duration.ofSeconds(1L)));
            MiddlewareException oversizeFailure = assertThrows(MiddlewareException.class, () -> oversize.poll(Duration.ofSeconds(2L)));
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, oversizeFailure.code());
            assertTrue(scheduler.listTasks().stream().anyMatch(status -> status.ref().serviceName().equals("oversize")
                    && status.state() == TaskState.FAILED));

            StreamHandle<String> terminal = scheduler.stream(submission("terminal", TaskOptions.defaults()), (context, cancellationToken, emitter) ->
                    emitter.emit("terminal-item", Duration.ofSeconds(1L)));
            assertEquals("terminal-item", terminal.poll(Duration.ofSeconds(2L)).orElseThrow());
            assertFalse(terminal.poll(Duration.ofSeconds(2L)).isPresent());
            assertTrue(terminal.closed());
            assertTrue(scheduler.metrics().streamsStarted() >= 2L);
            assertTrue(scheduler.metrics().streamCancelled() >= 1L);
            }
        });
    }

    @Test
    public void shardRoutingAnnotationsAndScatterGatherHelpersWork() throws Exception {
        withCaller(() -> {
            ObjectRef a = ref("a", "0.0");
            ObjectRef b = ref("b", "0.5");
            StableHashShardRoutingStrategy strategy = new StableHashShardRoutingStrategy();
            ShardRoute first = strategy.route(new org.j7m.middleware.workload.api.ShardKey("customer-1"), List.of(a, b));
            ShardRoute second = strategy.route(new org.j7m.middleware.workload.api.ShardKey("customer-1"), List.of(a, b));

            assertEquals(first.target().objectId(), second.target().objectId());
            assertEquals("stable-hash", first.metadata().get("strategy"));

            Method method = RoutedApi.class.getMethod("route", String.class);
            assertTrue(method.isAnnotationPresent(OrderedBy.class));
            assertTrue(method.getParameters()[0].isAnnotationPresent(ShardKey.class));

            try (InMemoryWorkloadScheduler scheduler = scheduler(new InMemoryAuditSink(32), allowAuth(), allowResource(), Clock.systemUTC(), 16, 2, 8, 128)) {
            FanOutResult fanOut = scheduler.fanOut(new FanOutRequest(List.of(a, b), 2, Duration.ofSeconds(2L), Duration.ofSeconds(1L), Map.of()),
                    candidate -> "ok-" + candidate.objectId());
            assertEquals(2, fanOut.successes().size());

            ScatterGatherResult partial = scheduler.scatterGather(new ScatterGatherRequest(List.of(a, b), 2, Duration.ofSeconds(2L), true, Map.of()),
                    candidate -> {
                        if ("b".equals(candidate.objectId())) {
                            throw new IllegalStateException("boom");
                        }
                        return candidate.objectId();
                    });
            assertEquals(1, partial.successes().size());
            assertEquals(1, partial.failures().size());
            assertTrue(partial.partial());
            assertEquals(1L, scheduler.metrics().fanOutCalls());
            assertEquals(1L, scheduler.metrics().scatterGatherCalls());
            }
        });
    }

    @Test
    public void shardRoutingRejectsUntrustedStaleAmbiguousAndSensitiveKeys() throws Exception {
        withCaller(() -> {
            StableHashShardRoutingStrategy strategy = new StableHashShardRoutingStrategy();
            ObjectRef trusted = ref("trusted", "0.0").withMetadata(Map.of(
                    "load", "0.0",
                    "trustStatus", "trusted",
                    "topologyGeneration", "5",
                    "fencingToken", "7"));
            ObjectRef untrusted = ref("untrusted", "0.0").withMetadata(Map.of(
                    "load", "0.0",
                    "trustStatus", "untrusted",
                    "topologyGeneration", "5",
                    "fencingToken", "7"));

            ShardRoute route = strategy.route(new org.j7m.middleware.workload.api.ShardKey(" customer-secret "), List.of(trusted));
            assertEquals("trusted", route.target().objectId());
            assertFalse(route.key().toString().contains("customer-secret"));
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                    () -> strategy.route(new org.j7m.middleware.workload.api.ShardKey("customer-secret"), List.of(untrusted, trusted))).code());

            ObjectRef missingTrust = ref("missing-trust", "0.0").withMetadata(Map.of(
                    "load", "0.0",
                    "topologyGeneration", "5",
                    "fencingToken", "7"));
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                    () -> strategy.route(new org.j7m.middleware.workload.api.ShardKey("customer-secret"), List.of(missingTrust))).code());

            ObjectRef missingTopology = ref("missing-topology", "0.0").withMetadata(Map.of(
                    "load", "0.0",
                    "trustStatus", "trusted",
                    "fencingToken", "7"));
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                    () -> strategy.route(new org.j7m.middleware.workload.api.ShardKey("customer-secret"), List.of(missingTopology))).code());

            ObjectRef missingFencing = ref("missing-fencing", "0.0").withMetadata(Map.of(
                    "load", "0.0",
                    "trustStatus", "trusted",
                    "topologyGeneration", "5"));
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                    () -> strategy.route(new org.j7m.middleware.workload.api.ShardKey("customer-secret"), List.of(missingFencing))).code());

            ObjectRef expired = trusted.withLeaseUntil(System.currentTimeMillis() - 1_000L);
            MiddlewareException noEligible = assertThrows(MiddlewareException.class,
                    () -> strategy.route(new org.j7m.middleware.workload.api.ShardKey("customer-secret"), List.of(expired)));
            assertEquals(MiddlewareErrorCode.NOT_FOUND, noEligible.code());
            assertFalse(noEligible.getMessage().contains("customer-secret"));

            ObjectRef duplicate = new ObjectRef("trusted", "logical-trusted-copy", "svc", "runtime-trusted", "default",
                    List.of(RoutedApi.class.getName()), "grpc", "127.0.0.1:copy",
                    System.currentTimeMillis() + 30_000L, 1L, trusted.metadata());
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                    () -> strategy.route(new org.j7m.middleware.workload.api.ShardKey("customer-secret"), List.of(trusted, duplicate))).code());

            ObjectRef staleTopology = ref("stale-topology", "0.0").withMetadata(Map.of(
                    "load", "0.0",
                    "trustStatus", "trusted",
                    "topologyGeneration", "6",
                    "fencingToken", "7"));
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                    () -> strategy.route(new org.j7m.middleware.workload.api.ShardKey("customer-secret"), List.of(trusted, staleTopology))).code());

            ObjectRef invalidFencing = ref("invalid-fencing", "0.0").withMetadata(Map.of(
                    "load", "0.0",
                    "trustStatus", "trusted",
                    "topologyGeneration", "5",
                    "fencingToken", "-1"));
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                    () -> strategy.route(new org.j7m.middleware.workload.api.ShardKey("customer-secret"), List.of(invalidFencing))).code());

            StringBuilder oversized = new StringBuilder();
            for (int index = 0; index < 257; index++) {
                oversized.append('x');
            }
            assertThrows(IllegalArgumentException.class, () -> new org.j7m.middleware.workload.api.ShardKey(oversized.toString()));
            assertThrows(IllegalArgumentException.class, () -> new org.j7m.middleware.workload.api.ShardKey("tenant\nsecret"));
        });
    }

    @Test
    public void authorizationResourcePolicyDrainAndOverloadAreEnforcedBeforeExecution() throws Exception {
        withCaller(() -> {
            InMemoryAuditSink audit = new InMemoryAuditSink(64);
            try (InMemoryWorkloadScheduler scheduler = scheduler(audit, denyingAuth(PermissionAction.LOAD), allowResource(), Clock.systemUTC(), 16, 2, 8, 128)) {
            MiddlewareException failure = assertThrows(MiddlewareException.class, () -> scheduler.submit(submission("denied", TaskOptions.defaults())));
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, failure.code());
            }

            try (InMemoryWorkloadScheduler scheduler = scheduler(audit, allowAuth(), denyingResource(ResourcePolicyAction.LOAD, false), Clock.systemUTC(), 16, 2, 8, 128)) {
            MiddlewareException failure = assertThrows(MiddlewareException.class, () -> scheduler.submit(submission("resource-denied", TaskOptions.defaults())));
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, failure.code());
            assertEquals(1L, scheduler.metrics().resourceDenied());
            }

            try (InMemoryWorkloadScheduler scheduler = scheduler(audit, allowAuth(), allowResource(), Clock.systemUTC(), 1, 1, 8, 128)) {
            scheduler.submit(submission("first", TaskOptions.defaults()));
            MiddlewareException failure = assertThrows(MiddlewareException.class, () -> scheduler.submit(submission("second", TaskOptions.defaults())));
            assertEquals(MiddlewareErrorCode.OVERLOADED, failure.code());
            assertEquals(1L, scheduler.metrics().overloadedRejected());
            scheduler.drain();
            assertThrows(MiddlewareException.class, () -> scheduler.submit(submission("after-drain", TaskOptions.defaults())));
            }
        });
    }

    @Test
    public void callerOwnedWorkRequiresExplicitCallerContext() {
        try (InMemoryWorkloadScheduler scheduler = scheduler(new InMemoryAuditSink(32), allowAuth(), allowResource(), Clock.systemUTC(), 16, 2, 8, 128)) {
            MiddlewareException failure = assertThrows(MiddlewareException.class,
                    () -> scheduler.submit(submission("missing-caller", TaskOptions.defaults())));
            assertEquals(MiddlewareErrorCode.UNAUTHENTICATED, failure.code());
        }
    }

    @Test
    public void concurrentAdmissionCannotExceedTaskCap() throws Exception {
        try (InMemoryWorkloadScheduler scheduler = scheduler(new InMemoryAuditSink(64), allowAuth(), allowResource(), Clock.systemUTC(), 4, 2, 8, 128);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> futures = new java.util.ArrayList<>(16);
            for (int index = 0; index < 16; index++) {
                final int taskIndex = index;
                futures.add(executor.submit(() -> SecurityContexts.call(callerContext(), () -> {
                    try {
                        scheduler.submit(submission("admission-" + taskIndex, TaskOptions.defaults()));
                        return Boolean.TRUE;
                    } catch (MiddlewareException exception) {
                        assertEquals(MiddlewareErrorCode.OVERLOADED, exception.code());
                        return Boolean.FALSE;
                    }
                })));
            }
            int accepted = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(2L, TimeUnit.SECONDS).booleanValue()) {
                    accepted++;
                }
            }
            assertEquals(4, accepted);
            assertEquals(4, scheduler.listTasks().size());
        }
    }

    @Test
    public void executionPermitSaturationLeavesTaskFailedNotLeased() throws Exception {
        withCaller(() -> {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (InMemoryWorkloadScheduler scheduler = scheduler(new InMemoryAuditSink(64), allowAuth(), allowResource(), Clock.systemUTC(), 8, 1, 8, 128)) {
                AsyncResult<Object> first = scheduler.submitAsync(submission("first-active", TaskOptions.defaults()), (context, cancellationToken) -> {
                    entered.countDown();
                    release.await(2L, TimeUnit.SECONDS);
                    return "done";
                });
                assertTrue(entered.await(2L, TimeUnit.SECONDS));

                MiddlewareException overloaded = assertThrows(MiddlewareException.class,
                        () -> scheduler.submitAsync(submission("second-rejected", TaskOptions.defaults()), (context, cancellationToken) -> "never"));
                assertEquals(MiddlewareErrorCode.OVERLOADED, overloaded.code());
                assertTrue(scheduler.listTasks().stream().anyMatch(status -> status.ref().serviceName().equals("second-rejected")
                        && status.state() == TaskState.FAILED));

                release.countDown();
                assertEquals("done", first.await(Duration.ofSeconds(2L)));
            }
        });
    }

    @Test
    public void executorRejectionAfterPermitLeavesTaskFailedWithoutLease() throws Exception {
        withCaller(() -> {
            try (InMemoryWorkloadScheduler scheduler = new InMemoryWorkloadScheduler(
                    InMemorySchedulerOptions.defaults("rejecting", "rejecting-runtime"),
                    new RejectingExecutorService())) {
                MiddlewareException failure = assertThrows(MiddlewareException.class,
                        () -> scheduler.submitAsync(submission("rejected-after-permit", TaskOptions.defaults()), (context, cancellationToken) -> "never"));
                assertEquals(MiddlewareErrorCode.UNAVAILABLE, failure.code());

                TaskStatus status = scheduler.listTasks().stream()
                        .filter(taskStatus -> taskStatus.ref().serviceName().equals("rejected-after-permit"))
                        .findFirst()
                        .orElseThrow();
                assertEquals(TaskState.FAILED, status.state());
                assertFalse(status.currentLease().isPresent());
            }
        });
    }

    @Test
    public void fanOutAndScatterGatherTruncateOverCapCandidatesBeforeInvocation() throws Exception {
        withCaller(() -> {
            List<ObjectRef> candidates = new java.util.ArrayList<>(256);
            for (int index = 0; index < 256; index++) {
                candidates.add(ref("bulk-" + index, "0.0"));
            }
            try (InMemoryWorkloadScheduler scheduler = scheduler(new InMemoryAuditSink(32), allowAuth(), allowResource(), Clock.systemUTC(), 16, 4, 8, 128)) {
                AtomicInteger fanOutInvocations = new AtomicInteger();
                FanOutResult fanOut = scheduler.fanOut(new FanOutRequest(candidates, 256, Duration.ofSeconds(2L), Duration.ofSeconds(1L),
                        Map.of("j7m.reliability.idempotent", "true")), candidate -> {
                    fanOutInvocations.incrementAndGet();
                    return candidate.objectId();
                });
                assertEquals(16, fanOutInvocations.get());
                assertTrue(fanOut.skippedCandidates().size() <= 64);
                assertEquals("240", fanOut.metadata().get("candidateOverflow"));

                AtomicInteger scatterInvocations = new AtomicInteger();
                ScatterGatherResult scatter = scheduler.scatterGather(new ScatterGatherRequest(candidates, 256, Duration.ofSeconds(2L), true,
                        Map.of("j7m.reliability.idempotent", "true")), candidate -> {
                    scatterInvocations.incrementAndGet();
                    return candidate.objectId();
                });
                assertEquals(16, scatterInvocations.get());
                assertTrue(scatter.skippedCandidates().size() <= 64);
                assertEquals("240", scatter.metadata().get("candidateOverflow"));
            }
        });
    }

    @Test
    public void fanOutTimeoutDoesNotAllowConcurrentSaturationToExceedPermitCap() throws Exception {
        withCaller(() -> {
            AtomicBoolean release = new AtomicBoolean();
            CountDownLatch entered = new CountDownLatch(1);
            try (InMemoryWorkloadScheduler scheduler = scheduler(new InMemoryAuditSink(32), allowAuth(), allowResource(), Clock.systemUTC(), 16, 1, 8, 128)) {
                FanOutResult timedOut = scheduler.fanOut(new FanOutRequest(List.of(ref("blocking", "0.0")), 1,
                        Duration.ofSeconds(1L), Duration.ofMillis(25L), Map.of("j7m.reliability.idempotent", "true")), candidate -> {
                    entered.countDown();
                    while (!release.get()) {
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10L));
                        Thread.interrupted();
                    }
                    return "late";
                });
                assertTrue(entered.await(1L, TimeUnit.SECONDS));
                assertEquals(1, timedOut.failures().size());

                FanOutResult saturated = scheduler.fanOut(new FanOutRequest(List.of(ref("second", "0.0")), 1,
                        Duration.ofSeconds(1L), Duration.ofMillis(25L), Map.of("j7m.reliability.idempotent", "true")),
                        candidate -> "should-not-run");
                assertEquals(MiddlewareErrorCode.OVERLOADED, saturated.failures().getFirst().code());
                release.set(true);
            } finally {
                release.set(true);
            }
        });
    }

    @Test
    public void fanOutAndScatterGatherRejectInvalidCandidatesBeforeInvocation() throws Exception {
        withCaller(() -> {
            long nowMillis = System.currentTimeMillis();
            ObjectRef untrusted = ref("fanout-untrusted", "0.0").withMetadata(Map.of(
                    "load", "0.0",
                    "trustStatus", "untrusted",
                    "topologyGeneration", "1",
                    "fencingToken", "1"));
            ObjectRef expired = ref("fanout-expired", "0.0").withLeaseUntil(nowMillis - 1_000L);
            ObjectRef missingFence = ref("fanout-missing-fence", "0.0").withMetadata(Map.of(
                    "load", "0.0",
                    "trustStatus", "trusted",
                    "topologyGeneration", "1"));
            ObjectRef dead = ref("fanout-dead", "0.0").withMetadata(Map.of(
                    "load", "0.0",
                    "trustStatus", "trusted",
                    "topologyGeneration", "1",
                    "fencingToken", "1",
                    "state", "dead"));
            List<ObjectRef> candidates = List.of(untrusted, expired, missingFence, dead);
            AtomicInteger invocations = new AtomicInteger();
            try (InMemoryWorkloadScheduler scheduler = scheduler(new InMemoryAuditSink(32), allowAuth(), allowResource(), Clock.systemUTC(), 16, 4, 8, 128)) {
                FanOutResult fanOut = scheduler.fanOut(new FanOutRequest(candidates, 4, Duration.ofSeconds(1L), Duration.ofMillis(100L),
                        Map.of("j7m.reliability.idempotent", "true")), candidate -> {
                    invocations.incrementAndGet();
                    return "should-not-run";
                });
                ScatterGatherResult scatter = scheduler.scatterGather(new ScatterGatherRequest(candidates, 4, Duration.ofSeconds(1L), true,
                        Map.of("j7m.reliability.idempotent", "true")), candidate -> {
                    invocations.incrementAndGet();
                    return "should-not-run";
                });

                assertEquals(0, invocations.get());
                assertEquals(4, fanOut.failures().size());
                assertEquals(4, scatter.failures().size());
            }
        });
    }

    @Test
    public void terminalTasksReleaseCapacityAndHistoryStaysBounded() throws Exception {
        withCaller(() -> {
            try (InMemoryWorkloadScheduler scheduler = scheduler(new InMemoryAuditSink(32), allowAuth(), allowResource(), Clock.systemUTC(), 1, 1, 8, 128)) {
                WorkloadHandle first = scheduler.submit(submission("first-terminal", TaskOptions.defaults()));
                LeaseOwner owner = owner("history-worker");
                TaskLease firstLease = scheduler.acquireLease(first.ref().taskId(), owner, Duration.ofSeconds(5L));
                scheduler.complete(first.ref().taskId(), owner, firstLease.fencingToken(), "done");
                assertEquals(TaskState.COMPLETED, scheduler.status(first.ref().taskId()).state());

                WorkloadHandle second = scheduler.submit(submission("second-terminal", TaskOptions.defaults()));
                assertEquals(1, scheduler.listTasks().size());
                assertEquals(MiddlewareErrorCode.NOT_FOUND, assertThrows(MiddlewareException.class,
                        () -> scheduler.status(first.ref().taskId())).code());

                TaskLease secondLease = scheduler.acquireLease(second.ref().taskId(), owner, Duration.ofSeconds(5L));
                scheduler.fail(second.ref().taskId(), owner, secondLease.fencingToken(),
                        new TaskFailure(MiddlewareErrorCode.REMOTE_EXCEPTION, "safe failure", true, Instant.now(), Map.of()));
                WorkloadHandle third = scheduler.submit(submission("third-terminal", TaskOptions.defaults()));
                scheduler.cancel(third.ref().taskId());
                WorkloadHandle fourth = scheduler.submit(submission("fourth-terminal", TaskOptions.defaults()));

                assertEquals(1, scheduler.listTasks().size());
                assertEquals("fourth-terminal", scheduler.listTasks().getFirst().ref().serviceName());
            }
        });
    }

    @Test
    public void defaultPayloadLimitRejectsOversizedSubmission() throws Exception {
        withCaller(() -> {
            try (InMemoryWorkloadScheduler scheduler = scheduler(new InMemoryAuditSink(32), allowAuth(), allowResource(), Clock.systemUTC(), 16, 2, 8, 128)) {
                String payload = "x".repeat(1024 * 1024 + 1);
                TaskSubmission submission = new TaskSubmission(new TaskDescriptor("oversized-payload", "handler", payload,
                        org.j7m.middleware.workload.api.WorkloadMetadata.empty(), Map.of()), TaskOptions.defaults(), Map.of());

                MiddlewareException failure = assertThrows(MiddlewareException.class, () -> scheduler.submit(submission));

                assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, failure.code());
                assertTrue(scheduler.listTasks().isEmpty());
            }
        });
    }

    @Test
    public void closeClearsRetainedTaskStateAndRejectsNewWorkIdempotently() throws Exception {
        InMemoryWorkloadScheduler scheduler = scheduler(new InMemoryAuditSink(32), allowAuth(), allowResource(), Clock.systemUTC(), 4, 1, 8, 128);
        withCaller(() -> {
            scheduler.submit(submission("close-cleanup", TaskOptions.defaults()));
            assertEquals(1, scheduler.listTasks().size());
            scheduler.close();
            scheduler.close();

            assertTrue(scheduler.listTasks().isEmpty());
            assertEquals(MiddlewareErrorCode.UNAVAILABLE, assertThrows(MiddlewareException.class,
                () -> scheduler.submit(submission("after-close", TaskOptions.defaults()))).code());
        });
    }

    @Test
    public void closeAfterDrainStillClearsRetainedStateAndNormalizesCapacity() throws Exception {
        InMemoryWorkloadScheduler scheduler = scheduler(new InMemoryAuditSink(32), allowAuth(), allowResource(), Clock.systemUTC(), 1, 1, 8, 128);
        withCaller(() -> {
            scheduler.submit(submission("drain-close-cleanup", TaskOptions.defaults()));
            assertEquals(1, scheduler.listTasks().size());
            scheduler.drain();
            scheduler.close();
            scheduler.close();

            assertTrue(scheduler.listTasks().isEmpty());
            assertEquals(MiddlewareErrorCode.UNAVAILABLE, assertThrows(MiddlewareException.class,
                    () -> scheduler.submit(submission("after-drain-close", TaskOptions.defaults()))).code());
        });
    }

    @Test
    public void terminalTasksRejectFurtherProgressCheckpointAndResultUpdates() throws Exception {
        withCaller(() -> {
            try (InMemoryWorkloadScheduler scheduler = scheduler(new InMemoryAuditSink(32), allowAuth(), allowResource(), Clock.systemUTC(), 16, 2, 8, 128)) {
                WorkloadHandle handle = scheduler.submit(submission("terminal", TaskOptions.defaults()));
                LeaseOwner owner = owner("worker-terminal");
                TaskLease lease = scheduler.acquireLease(handle.ref().taskId(), owner, Duration.ofSeconds(5L));
                scheduler.complete(handle.ref().taskId(), owner, lease.fencingToken(), "done");

                assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                        () -> scheduler.reportProgress(handle.ref().taskId(), owner, lease.fencingToken(),
                                new TaskProgress(100.0D, "late", Instant.now(), Map.of()))).code());
                assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                        () -> scheduler.checkpoint(handle.ref().taskId(), owner, lease.fencingToken(), Map.of("late", "true"))).code());
                assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                        () -> scheduler.complete(handle.ref().taskId(), owner, lease.fencingToken(), "late")).code());
            }
        });
    }

    private static void withCaller(final CheckedRunnable body) throws Exception {
        SecurityContexts.call(callerContext(), () -> {
            body.run();
            return null;
        });
    }

    private static TaskState awaitState(
            final InMemoryWorkloadScheduler scheduler,
            final TaskRef ref,
            final TaskState expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        TaskState current = scheduler.status(ref.taskId()).state();
        while (System.nanoTime() < deadline && current != expected) {
            new CountDownLatch(1).await(20L, TimeUnit.MILLISECONDS);
            current = scheduler.status(ref.taskId()).state();
        }
        return current;
    }

    private static SecurityContext callerContext() {
        Principal principal = new Principal("phase4-caller", "Phase 4 Caller", Set.of("user"), Set.of());
        return new SecurityContext(principal, "phase4-runtime", new TrustDomain("default"), new Credential("test", "1", Map.of()),
                "phase4-correlation", "phase4-invocation", "loopback", true, TransportSecurityLevel.TEST_AUTHENTICATED,
                principal.roles(), Map.of(), Instant.now());
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static InMemoryWorkloadScheduler scheduler(
            final InMemoryAuditSink audit,
            final AuthorizationPolicy authorizationPolicy,
            final ResourcePolicy resourcePolicy,
            final Clock clock,
            final int maxTasks,
            final int maxConcurrent,
            final int streamBuffer,
            final int maxStreamItemBytes) {
        SecurityConfiguration security = new SecurityConfiguration(SecurityMode.DEV, authorizationPolicy, audit, new DefaultRedactor(),
                false, true, 1024 * 1024, 1024 * 1024, maxConcurrent, Duration.ofSeconds(2L),
                new MutableCredentialProvider(new Credential("test", "1", Map.of())),
                false, Duration.ofSeconds(30L), 4096, false, resourcePolicy);
        return new InMemoryWorkloadScheduler(new InMemorySchedulerOptions("test-scheduler", "test-runtime", "default", clock,
                security, maxTasks, maxConcurrent, streamBuffer, maxStreamItemBytes, Duration.ofSeconds(5L)));
    }

    private static TaskSubmission submission(final String name, final TaskOptions options) {
        return new TaskSubmission(new TaskDescriptor(name, "handler", "payload", org.j7m.middleware.workload.api.WorkloadMetadata.empty(), Map.of()),
                options, Map.of("demo", "true"));
    }

    private static LeaseOwner owner(final String worker) {
        return new LeaseOwner("runtime", worker, Map.of());
    }

    private static ObjectRef ref(final String id, final String load) {
        return new ObjectRef(id, "logical-" + id, "svc", "runtime-" + id, "default", List.of(RoutedApi.class.getName()),
                "grpc", "127.0.0.1:" + id, System.currentTimeMillis() + 30_000L, 1L,
                Map.of("load", load, "trustStatus", "trusted", "topologyGeneration", "1", "fencingToken", "1"));
    }

    private static AuthorizationPolicy allowAuth() {
        return new AllowAllAuthorizationPolicy();
    }

    private static AuthorizationPolicy denyingAuth(final PermissionAction denied) {
        return new AuthorizationPolicy() {
            @Override
            public String id() {
                return "phase4-deny";
            }

            @Override
            public AuthorizationDecision evaluate(final SecurityContext context, final PermissionRequest request) {
                if (request.action() == denied) {
                    return AuthorizationDecision.deny(this.id(), "phase4-denied");
                }
                return AuthorizationDecision.allow(this.id());
            }
        };
    }

    private static ResourcePolicy allowResource() {
        return new ResourcePolicy() {
            @Override
            public String id() {
                return "phase4-resource-allow";
            }

            @Override
            public ResourcePolicyDecision evaluate(final SecurityContext context, final ResourcePolicyRequest request) {
                return ResourcePolicyDecision.allow(this.id());
            }
        };
    }

    private static ResourcePolicy denyingResource(final ResourcePolicyAction action, final boolean overloaded) {
        return new ResourcePolicy() {
            @Override
            public String id() {
                return "phase4-resource-deny";
            }

            @Override
            public ResourcePolicyDecision evaluate(final SecurityContext context, final ResourcePolicyRequest request) {
                if (request.action() == action) {
                    return overloaded
                            ? ResourcePolicyDecision.overload(this.id(), "phase4-overload")
                            : ResourcePolicyDecision.deny(this.id(), "phase4-deny");
                }
                return ResourcePolicyDecision.allow(this.id());
            }
        };
    }

    /**
     * API carrying routing annotations for metadata extraction tests.
     */
    public interface RoutedApi {
        /**
         * Routes by customer key.
         */
        @OrderedBy("customer")
        String route(@ShardKey("customer") String customer);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(final Instant instant) {
            this.instant = instant;
        }

        private void advance(final Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.instant;
        }
    }

    private static final class RejectingExecutorService extends AbstractExecutorService {
        private final AtomicBoolean shutdown;

        private RejectingExecutorService() {
            this.shutdown = new AtomicBoolean();
        }

        @Override
        public void shutdown() {
            this.shutdown.set(true);
        }

        @Override
        public List<Runnable> shutdownNow() {
            this.shutdown.set(true);
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return this.shutdown.get();
        }

        @Override
        public boolean isTerminated() {
            return this.shutdown.get();
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(final Runnable command) {
            throw new RejectedExecutionException("deterministic rejection");
        }
    }
}
