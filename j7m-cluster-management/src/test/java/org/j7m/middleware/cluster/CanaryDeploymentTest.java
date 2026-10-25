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

package org.j7m.middleware.cluster;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;

class CanaryDeploymentTest {
    static Map<String, ManagedInvocation> pinned(Map<String, ManagedInvocation> calls) {
        Map<String, ManagedInvocation> result = new HashMap<>();
        calls.forEach((node, callback) -> result.put(node, new ManagedInvocation() {
            @Override public byte[] invoke(String id, String contract, byte[] data, Instant deadline) throws IOException {
                return callback.invoke(id, contract, data, deadline);
            }
            @Override public byte[] invokeRevision(String release, String id, String contract, byte[] data, Instant deadline) throws IOException {
                return callback.invoke(id, contract, data, deadline);
            }
        }));
        return result;
    }
    static Instant deadline() { return Instant.now().plusSeconds(5); }
    static CanaryPolicy policy(long revision, int weight) { return new CanaryPolicy(revision, "v1", "v2", weight, 200, .05, 1000, 5000); }
    @Test void lateOldRevisionCompletionCannotEraseNewRevisionObservations() throws Exception {
        var resources = new MemoryResources(); var lifecycle = new Lifecycle();
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        ManagedInvocation candidate = (id, contract, data, end) -> {
            if (id.equals("old")) {
                started.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                return data;
            }
            throw new IOException("candidate failed");
        };
        try (var controller = new CanaryDeployment("late", resources, lifecycle, pinned(Map.of("a", (id, contract, data, end) -> data, "b", candidate)));
                var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            controller.configure("old-policy", 0, policy(1, 10000), deadline());
            var old = tasks.submit(() -> controller.invoke("old", "service", new byte[0], deadline()));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            controller.configure("new-policy", controller.status(deadline()).version(), policy(2, 10000), deadline());
            for (int i = 0; i < 199; i++) {
                String id = "new-" + i;
                assertThrows(IOException.class, () -> controller.invoke(id, "service", new byte[0], deadline()));
            }
            assertEquals("ACTIVE", controller.evaluate(deadline()).state());
            release.countDown(); assertArrayEquals(new byte[0], old.get(5, TimeUnit.SECONDS));
            assertThrows(IOException.class, () -> controller.invoke("new-final", "service", new byte[0], deadline()));
            assertEquals("ROLLED_BACK", controller.evaluate(deadline()).state());
        } finally { release.countDown(); }
    }
    @Test void invokesActualSelectedCollaboratorAndReplaysDurablePolicyAcrossControllerRestart() throws Exception {
        var resources = new MemoryResources(); var lifecycle = new Lifecycle();
        AtomicInteger baseline = new AtomicInteger(), candidate = new AtomicInteger();
        Map<String, ManagedInvocation> calls = Map.of("a", (id, contract, data, end) -> { baseline.incrementAndGet(); return data; },
                "b", (id, contract, data, end) -> { candidate.incrementAndGet(); return data; });
        try (var controller = new CanaryDeployment("tenant", resources, lifecycle, pinned(calls))) {
            assertNull(controller.status(deadline()));
            assertThrows(IOException.class, () -> controller.invoke("missing", "service", new byte[0], deadline()));
            assertEquals("ACTIVE", controller.configure("configure", 0, policy(1, 1000), deadline()).state());
            for (int i = 0; i < 100; i++) assertArrayEquals(new byte[]{1}, controller.invoke("id-" + i, "service", new byte[]{1}, deadline()));
            assertEquals(100, baseline.get() + candidate.get()); assertTrue(candidate.get() > 0);
            assertEquals("ACTIVE", controller.evaluate(deadline()).state());
            assertEquals(0, controller.metrics().get("rollbacks"));
        }
        try (var resumed = new CanaryDeployment("tenant", resources, lifecycle, pinned(calls))) {
            assertEquals(policy(1, 1000), resumed.status(deadline()).policy());
            assertThrows(IOException.class, () -> resumed.configure("older", 1, policy(1, 500), deadline()));
            assertThrows(IOException.class, () -> resumed.configure("wrong-version", 0, policy(2, 500), deadline()));
        }
    }
    @Test void minimumSamplesAndErrorProbeDisableAdmissionBeforeRealLifecycleRollback() throws Exception {
        var resources = new MemoryResources(); var lifecycle = new Lifecycle();
        ManagedInvocation good = (id, contract, data, end) -> data;
        ManagedInvocation bad = (id, contract, data, end) -> { throw new IOException("application failure"); };
        try (var controller = new CanaryDeployment("tenant", resources, lifecycle, pinned(Map.of("a", good, "b", bad)))) {
            controller.configure("start", 0, policy(1, 10000), deadline());
            for (int i = 0; i < 199; i++) {
                int index = i; assertThrows(IOException.class, () -> controller.invoke("bad-" + index, "service", new byte[0], deadline()));
            }
            assertEquals("ACTIVE", controller.evaluate(deadline()).state());
            assertThrows(IOException.class, () -> controller.invoke("bad-199", "service", new byte[0], deadline()));
            assertEquals("ROLLED_BACK", controller.evaluate(deadline()).state());
            assertEquals(1, lifecycle.rollbacks.get());
            assertArrayEquals(new byte[]{2}, controller.invoke("after", "service", new byte[]{2}, deadline()));
        }
    }
    @Test void missingOrStaleProbesHoldAndRejectExposureIncrease() throws Exception {
        var resources = new MemoryResources(); var lifecycle = new Lifecycle(); AtomicBoolean unavailable = new AtomicBoolean();
        ManagedInvocation good = (id, contract, data, end) -> data;
        CanaryDeployment.ProbeReader probes = revision -> unavailable.get() ? null : new CanaryDeployment.Probe(Instant.now(), 0, 0, 0);
        try (var controller = new CanaryDeployment("tenant", resources, lifecycle, pinned(Map.of("a", good, "b", good)), probes)) {
            assertNotNull(probes.read(1)); controller.configure("start", 0, policy(1, 1000), deadline());
            unavailable.set(true); assertEquals("HOLD", controller.evaluate(deadline()).state());
            assertThrows(IOException.class, () -> controller.configure("raise", controller.status(deadline()).version(), policy(2, 2000), deadline()));
            assertEquals(1000, controller.status(deadline()).policy().canaryWeight());
            assertTrue(controller.metrics().get("holds") >= 1);
        }
        try (var stale = new CanaryDeployment("tenant2", new MemoryResources(), lifecycle, pinned(Map.of("a", good, "b", good)),
                revision -> new CanaryDeployment.Probe(Instant.EPOCH, 1, 0, 1))) {
            assertThrows(IOException.class, () -> stale.configure("start", 0, policy(1, 1000), deadline()));
        }
    }
    @Test void latencyProbeAndReadinessBoundaries() throws Exception {
        var lifecycle = new Lifecycle(); ManagedInvocation good = (id, contract, data, end) -> data;
        try (var controller = new CanaryDeployment("latency", new MemoryResources(), lifecycle, pinned(Map.of("a", good, "b", good)),
                revision -> new CanaryDeployment.Probe(Instant.now(), 200, 0, 201_000_000_000L))) {
            controller.configure("start", 0, policy(1, 1000), deadline());
            assertEquals("ROLLED_BACK", controller.evaluate(deadline()).state());
        }
        lifecycle.ready = false;
        try (var controller = new CanaryDeployment("readiness", new MemoryResources(), lifecycle, pinned(Map.of("a", good, "b", good)))) {
            assertThrows(IOException.class, () -> controller.configure("start", 0, policy(1, 1000), deadline()));
        }
        assertThrows(IllegalArgumentException.class, () -> new CanaryDeployment("a", new MemoryResources(), lifecycle, pinned(Map.of())));
        assertThrows(IllegalArgumentException.class, () -> new CanaryDeployment.Probe(Instant.now(), Long.MAX_VALUE, 1, 0));
    }
    @Test void restartAfterDisableReplaysOriginalRollbackArgumentsAndNeverReexposesCandidate() throws Exception {
        var resources = new MemoryResources(); var lifecycle = new Lifecycle(); lifecycle.loseReply = true;
        ManagedInvocation good = (id, contract, bytes, end) -> bytes;
        var endpoints = Map.of("a", good, "b", good);
        CanaryDeployment.ProbeReader bad = revision -> new CanaryDeployment.Probe(Instant.now(), 0, 200, 1);
        try (var first = new CanaryDeployment("restart", resources, lifecycle, pinned(endpoints), bad)) {
            first.configure("start", 0, policy(1, 1000), deadline());
            assertThrows(IOException.class, () -> first.evaluate(deadline()));
            assertEquals("ROLLING_BACK", first.status(deadline()).state());
        }
        lifecycle.loseReply = false;
        try (var resumed = new CanaryDeployment("restart", resources, lifecycle, pinned(endpoints), bad)) {
            assertEquals("ROLLED_BACK", resumed.evaluate(deadline()).state());
            assertEquals(List.of("1:[b]", "1:[b]"), lifecycle.rollbackArguments);
            assertArrayEquals(new byte[]{1}, resumed.invoke("after", "service", new byte[]{1}, deadline()));
        }
    }
    static final class MemoryResources implements ResourceEndpoint {
        final ReplicatedResources machine = new ReplicatedResources(); long index;
        @Override public synchronized ReplicatedResources.Result execute(String operation, String request, List<String> arguments, Instant deadline) {
            machine.apply(++index, new ResourceCommand(operation, "tenant", request, arguments).encode()); return machine.result("tenant", request, index);
        }
        @Override public synchronized ReplicatedResources.Value read(String key, Instant deadline) { return machine.queryAtReadIndex("tenant", key, index); }
        @Override public ReplicatedResources.Result transaction(String tx, Instant deadline) { throw new UnsupportedOperationException(); }
        @Override public List<String> prepared(Instant deadline) { return List.of(); }
        @Override public List<String> participants(String tx, Instant deadline) { return List.of(); }
    }
    static final class Lifecycle implements ClusterLifecycle {
        final AtomicInteger rollbacks = new AtomicInteger(); volatile boolean ready = true; volatile boolean reverted; volatile boolean loseReply;
        final List<String> rollbackArguments = new CopyOnWriteArrayList<>();
        @Override public ClusterView inspect(Instant deadline) {
            return new ClusterView(reverted ? 99 : 1, Map.of("a", new NodeView("a", "v1", ready, false, 0, 1),
                    "b", new NodeView("b", reverted ? "v1" : "v2", ready, false, 0, 1)), "");
        }
        @Override public OperationStatus rollback(String id, long version, String release, List<String> nodes, Instant deadline) throws IOException {
            rollbackArguments.add(version + ":" + nodes); rollbacks.incrementAndGet(); reverted = true;
            if (loseReply) throw new IOException("Reply lost after lifecycle commit");
            return new OperationStatus(id, "COMPLETED", 1, "", "", "", "");
        }
        @Override public DeploymentPlan plan(String id, long version, String release, List<String> nodes, Instant deadline) { throw new UnsupportedOperationException(); }
        @Override public OperationStatus apply(String id, long version, String plan, Instant deadline) { throw new UnsupportedOperationException(); }
        @Override public OperationStatus drain(String id, long version, String node, Instant deadline) { throw new UnsupportedOperationException(); }
        @Override public OperationStatus operationStatus(String id, Instant deadline) { throw new UnsupportedOperationException(); }
    }
}
