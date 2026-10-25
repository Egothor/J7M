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
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.*;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(90)
class ObservabilityIT {
    @TempDir Path directory;
    static void await(java.util.function.BooleanSupplier condition) {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) { if (System.nanoTime() > end) fail("Observation did not converge"); LockSupport.parkNanos(1_000_000); }
    }
    @Test void realThreeJvmQueueChainExportsOneTraceWithPersistedParentLinksAndNoSensitiveData() throws Exception {
        BlockingQueue<String> exports = new LinkedBlockingQueue<>();
        HttpServer collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        collector.createContext("/v1/traces", exchange -> {
            assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
            exports.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); exchange.sendResponseHeaders(200, -1); exchange.close();
        }); collector.start();
        try (var cluster = new IntegrationCluster(directory.resolve("brokers"))) {
            URI endpoint = URI.create("http://127.0.0.1:" + collector.getAddress().getPort() + "/v1/traces");
            List<String> bodies = new ArrayList<>();
            for (String stage : List.of("produce", "forward", "consume")) {
                Process child = IntegrationCluster.child(directory, "TRACE", cluster.ports, endpoint.toString(), stage);
                try {
                    var reader = child.inputReader(); String line;
                    do { line = IntegrationCluster.read(reader); assertNotNull(line, "Trace worker ended before export " + stage); } while (!line.startsWith("EXPORTED "));
                    int expected = stage.equals("produce") ? 2 : 2;
                    int spans = 0; long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    while (spans < expected && System.nanoTime() < end) {
                        String body = exports.poll(1, TimeUnit.SECONDS); if (body != null) { bodies.add(body); spans += count(body, "\"traceId\""); }
                    }
                    assertTrue(spans >= expected, "Expected real OTLP spans for " + stage);
                    try (var input = child.outputWriter()) { input.write("done\n"); input.flush(); }
                    assertTrue(child.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, child.exitValue());
                } finally { if (child.isAlive()) IntegrationCluster.kill(child); }
            }
            exports.drainTo(bodies); String exported = String.join("\n", bodies);
            assertFalse(exported.contains("SensitivePayload")); assertFalse(exported.contains("SecretToken")); assertFalse(exported.contains("tracestate")); assertFalse(exported.contains("tenant"));
            Pattern spans = Pattern.compile("\\{\"traceId\":\"([^\"]+)\",\"spanId\":\"([^\"]+)\",\"parentSpanId\":\"([^\"]*)\",\"name\":\"([^\"]+)\"");
            Matcher matches = spans.matcher(exported); Set<String> spanIds = new HashSet<>(); List<String> parents = new ArrayList<>(); Set<String> operations = new HashSet<>();
            while (matches.find()) { assertEquals("1234567890abcdef1234567890abcdef", matches.group(1)); spanIds.add(matches.group(2)); parents.add(matches.group(3)); operations.add(matches.group(4)); }
            assertTrue(operations.containsAll(Set.of("rpc", "queue_send", "queue_consume", "job_run")));
            for (String parent : parents) assertTrue(parent.equals("1234567890abcdef") || spanIds.contains(parent), "Lost persisted parent " + parent);
            Path artifact = Path.of("build/reports/integrations/three-jvm-otlp.jsonl"); Files.createDirectories(artifact.getParent()); Files.writeString(artifact, exported);
        } finally { collector.stop(0); }
    }
    @Test void collectorOutageNeverBlocksRealQueueAndBoundsMemoryWhilePrometheusCountsDrops() throws Exception {
        int unavailable = IntegrationCluster.port();
        try (var cluster = new IntegrationCluster(directory.resolve("brokers"));
             var telemetry = new Observability(URI.create("http://127.0.0.1:" + unavailable + "/v1/traces"), new InetSocketAddress("127.0.0.1", 0), 4)) {
            var traced = new TracedQueue(cluster.queue, telemetry);
            long start = System.nanoTime();
            for (int i = 0; i < 10000; i++) try (var span = telemetry.start(Observability.Operation.RPC)) { assertTrue(telemetry.buffered() <= 4); }
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0);
            for (int i = 0; i < 50; i++) assertTrue(traced.send("outage" + i, "outage", "m" + i,
                    new TracedQueue.Message(TraceCarrier.empty(), "SensitivePayload SecretToken".getBytes(StandardCharsets.UTF_8)), 10, IntegrationProcess.deadline()).startsWith("SENT"));
            assertEquals(List.of(), traced.deadLetters("outage", IntegrationProcess.deadline()));
            await(() -> telemetry.dropped() > 0); assertEquals(4, telemetry.capacity()); assertTrue(telemetry.buffered() <= telemetry.capacity());
            HttpClient http = HttpClient.newHttpClient();
            String scrape = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + telemetry.address().getPort() + "/metrics")).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(scrape.contains("j7m_telemetry_dropped_total")); assertTrue(scrape.contains("operation=\"queue_send\"} 50"));
            assertFalse(scrape.contains("SensitivePayload")); assertFalse(scrape.contains("SecretToken")); assertFalse(scrape.contains("m49"));
            Files.createDirectories(Path.of("build/reports/integrations")); Files.writeString(Path.of("build/reports/integrations/outage.prom"), scrape);
        }
    }
    @Test void realFaultScrapeReportsQuorumLossAndObservedStaleOwnershipWithoutIdentityLabels() throws Exception {
        try (var cluster = new IntegrationCluster(directory.resolve("brokers"));
             var telemetry = new Observability(URI.create("http://127.0.0.1:" + IntegrationCluster.port() + "/v1/traces"), new InetSocketAddress("127.0.0.1", 0), 4);
             HttpClient http = HttpClient.newHttpClient()) {
            int[] leader = {-1};
            await(() -> {
                for (int i = 0; i < cluster.metrics.size(); i++) if (scrape(http, cluster.metrics.get(i)).contains("j7m_local_leader_quorum_available 1")) { leader[0] = i; return true; }
                return false;
            });
            var addresses = Arrays.stream(cluster.ports.split(",")).map(p -> new InetSocketAddress("127.0.0.1", Integer.parseInt(p))).toList();
            PartitionEndpoint observed = telemetry.observe(new PartitionClient(addresses, "app", new byte[32]));
            byte[] command = new ResourceCommand("Q_SEND", "tenant", "stale-route", List.of("q", "message", "", "1")).encode();
            byte[] query = new ResourceCommand("Q_RESULT", "tenant", "query", List.of("stale-route")).encode();
            assertEquals("MOVED", observed.execute(2, "stale-route", command, query, IntegrationProcess.deadline()).status());
            assertTrue(telemetry.scrape().contains("j7m_migration_redirects_total 1"));
            for (int i = 0; i < cluster.brokers.size(); i++) if (i != leader[0]) IntegrationCluster.kill(cluster.brokers.get(i));
            assertThrows(IOException.class, () -> cluster.queue.send("quorum-fault", "q", "fault", new byte[0], 1, Instant.now().plusMillis(500)));
            await(() -> scrape(http, cluster.metrics.get(leader[0])).contains("j7m_local_leader_quorum_available 0"));
            String fault = scrape(http, cluster.metrics.get(leader[0])); assertTrue(fault.contains("j7m_observer_failures_total"));
            assertFalse(fault.contains("tenant")); assertFalse(fault.contains("message"));
            Path evidence = Path.of("build/reports/integrations/quorum-fault.prom"); Files.createDirectories(evidence.getParent()); Files.writeString(evidence, fault);
        }
    }
    private static String scrape(HttpClient client, int port) {
        try { return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metrics")).timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.ofString()).body(); }
        catch (Exception unavailable) { return ""; }
    }
    @Test void boundedEnvelopeRoundTripRejectsCorruptionAndPreservesContextScope() throws Exception {
        var carrier = new TraceCarrier("00-1234567890abcdef1234567890abcdef-1234567890abcdef-01", "vendor=value");
        var message = new TracedQueue.Message(carrier, new byte[]{1,2}); byte[] encoded = TracedQueue.encode(message);
        assertEquals(carrier, TracedQueue.decode(encoded).context()); assertArrayEquals(new byte[]{1,2}, TracedQueue.decode(encoded).payload());
        encoded[0] ^= 1; assertThrows(IOException.class, () -> TracedQueue.decode(encoded));
        assertThrows(IllegalArgumentException.class, () -> new TracedQueue.Message(carrier, new byte[30001]));
        try (var telemetry = new Observability(URI.create("http://127.0.0.1:" + IntegrationCluster.port() + "/v1/traces"), new InetSocketAddress("127.0.0.1", 0), 4)) {
            assertEquals(TraceCarrier.empty(), telemetry.capture());
            try (var attached = telemetry.attach(carrier)) { assertEquals(carrier, telemetry.capture()); }
            assertEquals(TraceCarrier.empty(), telemetry.capture());
            try (var span = telemetry.start(Observability.Operation.JOB_RUN, carrier)) {
                assertEquals(carrier.traceparent().substring(3, 35), telemetry.capture().traceparent().substring(3, 35)); assertEquals(telemetry.capture(), span.carrier()); span.failed();
            }
            var unusedQueue = new ReplicatedQueue("unused", "tenant", (p,d) -> { throw new IOException("Unexpected routing"); });
            var traced = new TracedQueue(unusedQueue, telemetry);
            TracedQueue.Handler callback = payload -> { assertArrayEquals(new byte[]{1,2}, payload); assertTrue(telemetry.capture().traceparent().startsWith("00-1234567890abcdef1234567890abcdef-")); };
            traced.consume(TracedQueue.encode(message), payload -> callback.process(payload));
            assertThrows(IOException.class, () -> traced.consume(TracedQueue.encode(message), payload -> { throw new IOException("SensitiveFailure"); }));
            assertEquals(TraceCarrier.empty(), telemetry.capture());
        }
    }
    @Test void actualQueueProtocolRejectionsAndAuthorizationFailuresExportErrorSpans() throws Exception {
        List<String> exports = new CopyOnWriteArrayList<>(); HttpServer collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        collector.createContext("/v1/traces", exchange -> { exports.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); exchange.sendResponseHeaders(200, -1); exchange.close(); }); collector.start();
        try (var cluster = new IntegrationCluster(directory.resolve("brokers"));
             var telemetry = new Observability(URI.create("http://127.0.0.1:" + collector.getAddress().getPort() + "/v1/traces"), new InetSocketAddress("127.0.0.1", 0), 128)) {
            var traced = new TracedQueue(cluster.queue, telemetry);
            var first = new TracedQueue.Message(TraceCarrier.empty(), "SensitiveFirstPayload".getBytes(StandardCharsets.UTF_8));
            var other = new TracedQueue.Message(TraceCarrier.empty(), "SecretTokenChangedPayload".getBytes(StandardCharsets.UTF_8));
            assertTrue(traced.send("first", "errors", "same", first, 10, IntegrationProcess.deadline()).startsWith("SENT"));
            assertEquals("MESSAGE_CONFLICT", traced.send("other-operation", "errors", "same", other, 10, IntegrationProcess.deadline()));
            assertThrows(IllegalArgumentException.class, () -> traced.send("first", "errors", "same", other, 10, IntegrationProcess.deadline()));
            for (int i = 0; i < 1023; i++) assertTrue(cluster.queue.send("fill-" + i, "capacity-" + i, "fill-" + i, new byte[0], 1, IntegrationProcess.deadline()).startsWith("SENT"));
            assertEquals("OVERLOADED", traced.send("overload", "overflow", "overflow", first, 10, IntegrationProcess.deadline()));
            var forbidden = new ReplicatedQueue("queue-partition", "forbidden", (p,d) -> new ReplicatedQueue.Route(cluster.endpoint, 1));
            var forbiddenTrace = new TracedQueue(forbidden, telemetry);
            assertThrows(SecurityException.class, () -> forbiddenTrace.send("denied", "errors", "denied", other, 10, IntegrationProcess.deadline()));
            await(() -> count(String.join("", exports), "\"name\":\"queue_send\"") == 5);
            String exported = String.join("\n", exports);
            assertEquals(4, count(exported, "\"status\":{\"code\":2}"), "Every actual queue rejection/auth failure must be failed telemetry");
            assertTrue(telemetry.scrape().contains("operation=\"queue_send\"} 5"), "Operation metric counts all five actual attempts");
            assertFalse(exported.contains("Sensitive")); assertFalse(exported.contains("SecretToken")); assertFalse(exported.contains("forbidden"));
            Path evidence = Path.of("build/reports/integrations/queue-rejection-otlp.jsonl"); Files.createDirectories(evidence.getParent()); Files.writeString(evidence, exported);
        } finally { collector.stop(0); }
    }
    @Test void unsupportedLeaseSourceDoesNotSilentlyStopFutureObservations() throws Exception {
        ResourceEndpoint unsupported = (ResourceEndpoint) java.lang.reflect.Proxy.newProxyInstance(ResourceEndpoint.class.getClassLoader(), new Class<?>[]{ResourceEndpoint.class},
                (proxy, method, arguments) -> { throw new UnsupportedOperationException("SensitiveSourceFailure"); });
        try (var telemetry = new Observability(URI.create("http://127.0.0.1:" + IntegrationCluster.port() + "/v1/traces"), new InetSocketAddress("127.0.0.1", 0), 4);
             var watch = telemetry.watchLease(unsupported, "SecretResourceKey")) {
            await(() -> telemetry.scrape().contains("j7m_observer_failures_total 2"));
            assertTrue(telemetry.scrape().contains("j7m_active_leases 0")); assertFalse(telemetry.scrape().contains("SecretResourceKey")); assertFalse(telemetry.scrape().contains("SensitiveSourceFailure"));
        }
    }
    static int count(String text, String needle) { return (text.length() - text.replace(needle, "").length()) / needle.length(); }
}
