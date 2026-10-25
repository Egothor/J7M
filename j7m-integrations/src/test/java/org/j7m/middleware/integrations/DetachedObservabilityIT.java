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
import java.util.Map;
import java.util.concurrent.*;
import org.j7m.middleware.application.api.InvocationTraceContext;
import org.j7m.middleware.distributed.TraceCarrier;
import org.junit.jupiter.api.Test;

class DetachedObservabilityIT {
    @Test void detachedSpanAttachesOnOwnerThreadButEndsOnceOnPhysicalCompletionThread() throws Exception {
        var bodies = new LinkedBlockingQueue<String>();
        var collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        collector.createContext("/v1/traces", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1); exchange.close();
        }); collector.start();
        String parent = "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01";
        try (var telemetry = new Observability(URI.create("http://127.0.0.1:" + collector.getAddress().getPort() + "/v1/traces"),
                    new InetSocketAddress("127.0.0.1", 0), 8); var workers = Executors.newSingleThreadExecutor()) {
            var span = telemetry.startDetached(Observability.Operation.APPLICATION_HANDLER, new TraceCarrier(parent, ""));
            assertTrue(InvocationTraceContext.current().isEmpty());
            try (var attached = span.attach()) {
                assertEquals(span.carrier().asMap(), InvocationTraceContext.current());
                assertEquals(span.carrier(), telemetry.capture());
                var error = assertThrows(ExecutionException.class, () -> workers.submit(attached::close).get(2, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, error.getCause());
                assertNull(bodies.poll());
            }
            assertTrue(InvocationTraceContext.current().isEmpty());
            workers.submit(() -> { span.failed(); span.end(); span.end(); }).get(2, TimeUnit.SECONDS);
            String body = bodies.poll(3, TimeUnit.SECONDS); assertNotNull(body);
            assertTrue(body.contains("1234567890abcdef1234567890abcdef"));
            assertTrue(body.contains("\"parentSpanId\":\"1234567890abcdef\""));
            assertTrue(body.contains("application_handler"));
            assertFalse(body.contains("tracestate")); assertFalse(body.contains("principal"));
            assertTrue(telemetry.scrape().contains("j7m_operations_total{operation=\"application_handler\"} 1"));
            assertTrue(telemetry.scrape().contains("j7m_operations_total{operation=\"rpc\"} 0"));
        } finally { collector.stop(0); }
    }
    @Test void nativeD15CaptureAndSpanScopesBridgeOnlyAllowedGenericInvocationTraceData() throws Exception {
        String parent = "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01";
        try (var telemetry = new Observability(URI.create("http://127.0.0.1:9/v1/traces"), new InetSocketAddress("127.0.0.1", 0), 4);
                var trace = InvocationTraceContext.open(Map.of("traceparent", parent, "tracestate", "SecretToken"))) {
            assertEquals(parent, telemetry.capture().traceparent());
            try (var active = telemetry.start(Observability.Operation.RPC)) {
                assertEquals(active.carrier().asMap(), InvocationTraceContext.current());
                assertEquals(parent.substring(3, 35), active.carrier().traceparent().substring(3, 35));
                assertNotEquals(parent.substring(36, 52), active.carrier().traceparent().substring(36, 52));
            }
            assertEquals(Map.of("traceparent", parent), InvocationTraceContext.current());
        }
        assertTrue(InvocationTraceContext.current().isEmpty());
    }
}
