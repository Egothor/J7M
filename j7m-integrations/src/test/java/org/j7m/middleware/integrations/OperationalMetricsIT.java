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

package org.j7m.middleware.integrations;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.j7m.middleware.consensus.*;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual authorized lease observations and durable startup recovery through public scrape paths.
 */
@Timeout(30)
class OperationalMetricsIT {
    @TempDir Path directory;
    @Test void realAuthorizedLeaseExpiresAndRestoredNodeScrapesRecoveryWhileLateFreshWatchStaysZero() throws Exception {
        String id = "metrics";
        var config = new NetworkConsensusConfig(id, Map.of(id, new InetSocketAddress("127.0.0.1", IntegrationCluster.port())), Set.of(id), directory.resolve("node"),
                new byte[32], Duration.ofMillis(200), Duration.ofMillis(20), 1 << 20, 16 << 20);
        URI unavailable = URI.create("http://127.0.0.1:" + IntegrationCluster.port() + "/v1/traces"); long committed;
        try (var node = new NetworkConsensusNode(config, new ReplicatedResources());
             var metrics = new Observability(unavailable, new InetSocketAddress("127.0.0.1", 0), 4);
             HttpClient http = HttpClient.newHttpClient()) {
            node.start(); ObservabilityIT.await(() -> node.status().leaderId().equals(id));
            // The test deliberately registers recovery observation only after fresh application work.
            node.append("fresh", new ResourceCommand("BATCH", "tenant", "fresh", List.of("key", "0", "value")).encode(), IntegrationProcess.deadline());
            assertEquals(0, node.recoveredIndex());
            try (var watch = metrics.watchConsensus(node)) {
                ObservabilityIT.await(() -> scrape(http, metrics).contains("j7m_local_leader_quorum_available 1"));
                assertTrue(scrape(http, metrics).contains("j7m_recovered_groups_total 0"));
            }
            committed = node.status().commitIndex();
        }
        var restoredResources = new ReplicatedResources();
        try (var node = new NetworkConsensusNode(config, restoredResources);
             var metrics = new Observability(unavailable, new InetSocketAddress("127.0.0.1", 0), 4);
             HttpClient http = HttpClient.newHttpClient()) {
            assertEquals(committed, node.recoveredIndex()); node.start(); ObservabilityIT.await(() -> node.status().leaderId().equals(id));
            var clock = new MeasuredClockHealth(System::currentTimeMillis, System::nanoTime, System::currentTimeMillis, () -> 1); clock.sample(); clock.sample();
            var local = new ReplicatedResourceClient(node, restoredResources, "tenant", operation -> true, clock);
            try (var server = new RemoteResourceServer(new InetSocketAddress("127.0.0.1", 0), Map.of("observer", new RemoteResourceServer.Binding(local, new byte[32], false)), null)) {
                var endpoint = new RemoteResourceClient(List.of(server.address()), "observer", new byte[32], null);
                var acquired = endpoint.execute("ACQUIRE", "lease-acquire", List.of("SecretLeaseKey", "SecretOwner", "2000", "0"), IntegrationProcess.deadline()); assertEquals("OK", acquired.status());
                try (var quorumWatch = metrics.watchConsensus(node); var leaseWatch = metrics.watchLease(endpoint, "SecretLeaseKey")) {
                    ObservabilityIT.await(() -> scrape(http, metrics).contains("j7m_active_leases 1"));
                    String active = scrape(http, metrics); assertTrue(active.contains("j7m_recovered_groups_total 1"));
                    ObservabilityIT.await(() -> scrape(http, metrics).contains("j7m_active_leases 0"));
                    assertNull(endpoint.lease("SecretLeaseKey", IntegrationProcess.deadline()));
                    String expired = scrape(http, metrics); assertFalse(expired.contains("SecretLeaseKey")); assertFalse(expired.contains("SecretOwner"));
                    assertTrue(server.dispatchedRequests() > 0);
                    Path evidence = Path.of("build/reports/integrations/lease-recovery-positive.prom"); Files.createDirectories(evidence.getParent()); Files.writeString(evidence, "# live lease\n" + active + "# expired lease\n" + expired);
                }
            }
        }
    }
    private static String scrape(HttpClient client, Observability metrics) {
        try { return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + metrics.address().getPort() + "/metrics")).timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.ofString()).body(); }
        catch (Exception unavailable) { return ""; }
    }
}
