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

package org.j7m.middleware.application.distributed;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TypedResourceBindingProcessIT {
    @TempDir Path directory;
    private static Instant deadline() { return Instant.now().plusSeconds(8); }
    private static ManagedDependency edge(Class<?> type, BindingRefresh mode) { return new ManagedDependency(type, "typed", List.of(), true,
            Map.of("resource.name", "typed", "binding.refresh", mode.name(), "binding.policy", "default")); }
    private ResourceBindingProcessIT fixture() throws Exception {
        // Reuse the actual TCP/disk process fixture, never the test methods or a simulated backend.
        var fixture = new ResourceBindingProcessIT(); fixture.directory = directory; fixture.keys(); return fixture;
    }
    @ParameterizedTest @EnumSource(BindingRefresh.class)
    void queueTypedClientPreservesDeliveryReceiptIdempotencyAndPinnedOwnerPolicy(BindingRefresh mode) throws Exception {
        var fixture = fixture();
        try (var topology = fixture.new Node("topology", true, 0); var left = fixture.new Node("left", false, 1, "queue"); var right = fixture.new Node("right", false, 0, "queue")) {
            var authority = topology.admin(); authority.register("partition", "left", deadline());
            var registration = ResourceBindingFactory.queue("partition", "tenant", new PartitionAdminClient(topology.client(false)), Map.of("left", left.client(false), "right", right.client(false)), Duration.ofSeconds(5));
            var factory = new ResourceBindingFactory(Map.of("typed", registration), Set.of("default")); factory.validate(edge(ReplicatedQueue.class, mode), Scope.Kind.ACTIVATION);
            try (var injected = new GeneratedResourceConsumer(directory, ReplicatedQueue.class, mode)) {
                injected.activate(factory); var queue = (ReplicatedQueue) injected.value(); assertSame(queue, injected.value());
                assertEquals(1, injected.consumer.getField("created").getInt(null));
                assertEquals("OK\t1", queue.join("join", "workers", "worker", 60000, deadline()));
                String sent = queue.send("send", "orders", "message", new byte[]{7}, 3, deadline()); assertTrue(sent.startsWith("SENT\t"));
                String receipt = queue.claim("claim", "orders", "workers", "worker", 1, 60000, deadline()); assertTrue(receipt.startsWith("MESSAGE\tmessage\t"));
                long fence = Long.parseLong(receipt.split("\t")[4]);
                new PartitionMigration(authority, Map.of("left", left.admin(), "right", right.admin())).migrate("move", "partition", "right", 1, deadline());
                if (mode == BindingRefresh.NEVER) {
                    assertThrows(IOException.class, () -> queue.ack("ack", "orders", "workers", "worker", 1, "message", fence, Instant.now().plusMillis(150)));
                    var target = new ReplicatedQueue("partition", "tenant", (p, d) -> new ReplicatedQueue.Route(right.client(false), 2));
                    assertEquals("ACKED", target.ack("ack", "orders", "workers", "worker", 1, "message", fence, deadline()));
                } else {
                    assertEquals("ACKED", queue.ack("ack", "orders", "workers", "worker", 1, "message", fence, deadline()));
                    assertEquals(sent, queue.send("send", "orders", "message", new byte[]{7}, 3, deadline()));
                    assertEquals("EMPTY", queue.claim("empty", "orders", "workers", "worker", 1, 60000, deadline()));
                }
                injected.close(); assertThrows(IOException.class, () -> queue.send("closed", "orders", "after", new byte[]{8}, 3, deadline()));
            }
        }
    }
    @ParameterizedTest @EnumSource(BindingRefresh.class)
    void executionTypedClientPreservesJobFenceCheckpointAndResultAcrossHandoff(BindingRefresh mode) throws Exception {
        var fixture = fixture();
        try (var topology = fixture.new Node("topology", true, 0); var left = fixture.new Node("left", false, 1, "execution"); var right = fixture.new Node("right", false, 0, "execution")) {
            var authority = topology.admin(); authority.register("partition", "left", deadline());
            var observed = new java.util.concurrent.atomic.AtomicInteger();
            String trace = "00-11111111111111111111111111111111-2222222222222222-01";
            ExecutionClient.Observer observer = operation -> new ExecutionClient.Observation() {
                @Override public void completed(ExecutionResult result) { observed.incrementAndGet(); }
                @Override public void close() { }
            };
            var registration = mode == BindingRefresh.ON_TOPOLOGY_CHANGE
                    ? ResourceBindingFactory.execution("partition", "tenant", new PartitionAdminClient(topology.client(false)), Map.of("left", left.client(false), "right", right.client(false)), Duration.ofSeconds(5), () -> Map.of("traceparent", trace), observer)
                    : ResourceBindingFactory.execution("partition", "tenant", new PartitionAdminClient(topology.client(false)), Map.of("left", left.client(false), "right", right.client(false)), Duration.ofSeconds(5));
            var factory = new ResourceBindingFactory(Map.of("typed", registration), Set.of("default"));
            try (var injected = new GeneratedResourceConsumer(directory, ExecutionClient.class, mode)) {
                injected.activate(factory); var client = (ExecutionClient) injected.value(); var jobs = new DurableJobs(client);
                var submitted = jobs.submit("submit", "job", "input", deadline()); assertEquals("READY", submitted.status());
                if (mode == BindingRefresh.ON_TOPOLOGY_CHANGE) assertEquals(trace, submitted.context().get("traceparent"));
                var claim = jobs.claim("claim", "job", "worker", 60000, deadline()); long fence = Long.parseLong(claim.fields().get("fence"));
                assertEquals("RUNNING", jobs.checkpoint("checkpoint", "job", "worker", fence, "saved-progress", deadline()).status());
                new PartitionMigration(authority, Map.of("left", left.admin(), "right", right.admin())).migrate("move", "partition", "right", 1, deadline());
                if (mode == BindingRefresh.NEVER) {
                    assertThrows(IOException.class, () -> jobs.complete("complete", "job", "worker", fence, "done", List.of(), Instant.now().plusMillis(150)));
                    var target = new DurableJobs(new ExecutionClient("partition", "tenant", (p, d) -> new ReplicatedQueue.Route(right.client(false), 2)));
                    assertEquals("saved-progress", target.status("job", deadline()).fields().get("checkpoint"));
                    assertEquals("COMPLETED", target.complete("complete", "job", "worker", fence, "done", List.of(), deadline()).status());
                } else {
                    assertEquals("saved-progress", jobs.status("job", deadline()).fields().get("checkpoint"));
                    assertEquals("COMPLETED", jobs.complete("complete", "job", "worker", fence, "done", List.of(), deadline()).status());
                    assertEquals(submitted, jobs.submit("submit", "job", "input", deadline()));
                }
                if (mode == BindingRefresh.ON_TOPOLOGY_CHANGE) assertTrue(observed.get() > 0);
                injected.close(); assertThrows(IOException.class, () -> jobs.status("job", deadline()));
            }
        }
    }
}
