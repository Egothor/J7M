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
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
class ExecutionObservationIT {
    @TempDir Path directory;
    @Test void automaticRpcJobSagaSpansPreserveContextAcrossRetryAndExposeActualReplayCounters() throws Exception {
        List<String> exports = new CopyOnWriteArrayList<>(); HttpServer collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        collector.createContext("/v1/traces", exchange -> { exports.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); exchange.sendResponseHeaders(200, -1); exchange.close(); }); collector.start();
        try (var cluster = new IntegrationCluster(directory.resolve("brokers"), true);
             var telemetry = new Observability(URI.create("http://127.0.0.1:" + collector.getAddress().getPort() + "/v1/traces"), new InetSocketAddress("127.0.0.1", 0), 128)) {
            var client = new ExecutionClient("execution-partition", "tenant", (p,d) -> new ReplicatedQueue.Route(cluster.endpoint, 1), () -> telemetry.capture().asMap(), telemetry.executionObserver());
            var jobs = new DurableJobs(client); var sagas = new DurableSagas(client); var rpc = new DeduplicatedRpc(client);
            var parent = new TraceCarrier("00-1234567890abcdef1234567890abcdef-1234567890abcdef-01", "vendor=SecretToken");
            ExecutionResult first;
            try (var scope = telemetry.attach(parent)) {
                first = rpc.invoke("first", "rpc", "a".repeat(64), 100000, "SensitiveResult", "", List.of("value", "0", "once"), IntegrationProcess.deadline());
                ExecutionResult retry = rpc.invoke("retry", "rpc", "a".repeat(64), 100000, "SensitiveResult", "", List.of("value", "0", "once"), IntegrationProcess.deadline());
                assertEquals(first, retry); assertEquals("COMPLETED", first.status());
                assertEquals(first, rpc.invoke("first", "rpc", "a".repeat(64), 100000, "SensitiveResult", "", List.of("value", "0", "once"), IntegrationProcess.deadline()), "Same operation retry keeps semantic identity under a fresh child span");
                assertFalse(first.context().isEmpty()); assertNotEquals(parent.traceparent(), first.context().get("traceparent"));
                assertEquals("READY", jobs.submit("submit", "job", "SensitiveInput", IntegrationProcess.deadline()).status());
                assertEquals("READY", sagas.start("saga", "saga", "SensitiveSagaInput", 2, 10, IntegrationProcess.deadline()).status());
            }
            ExecutionResult claim = jobs.claim("claim", "job", "worker", 100000, IntegrationProcess.deadline()); assertEquals("RUNNING", claim.status());
            assertEquals("finished", telemetry.runJob(claim, () -> { assertTrue(telemetry.capture().traceparent().startsWith("00-1234567890abcdef1234567890abcdef-")); return "finished"; }));
            try (var watch = telemetry.watchExecution(client)) {
                ObservabilityIT.await(() -> telemetry.scrape().contains("j7m_dedupe_replays_total 1"));
                assertTrue(telemetry.scrape().contains("j7m_retained_jobs 1")); assertTrue(telemetry.scrape().contains("j7m_retained_sagas 1"));
                ObservabilityIT.await(() -> exports.stream().anyMatch(body -> body.contains("\"name\":\"rpc\""))
                        && exports.stream().anyMatch(body -> body.contains("\"name\":\"saga_step\"")) && exports.stream().anyMatch(body -> body.contains("\"name\":\"job_submit\"")));
                String all = String.join("\n", exports); assertFalse(all.contains("Sensitive")); assertFalse(all.contains("SecretToken"));
                assertTrue(telemetry.scrape().contains("operation=\"rpc\"}")); assertTrue(telemetry.scrape().contains("operation=\"saga_step\"} 1"));
                Path evidence = Path.of("build/reports/integrations/automatic-execution-otlp.jsonl"); Files.createDirectories(evidence.getParent()); Files.writeString(evidence, all); Files.writeString(evidence.resolveSibling("automatic-execution.prom"), telemetry.scrape());
            }
        } finally { collector.stop(0); }
    }
}
