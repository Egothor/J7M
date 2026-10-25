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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.ObjectRegistration;
import org.j7m.middleware.api.reliability.CausalContext;
import org.j7m.middleware.api.reliability.CorrelationContext;
import org.j7m.middleware.api.reliability.DeliverySemantics;
import org.j7m.middleware.api.reliability.IdempotencyKey;
import org.j7m.middleware.api.reliability.InvocationReliabilityOptions;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.api.MethodDescriptor;
import org.j7m.middleware.transport.api.TransportProvider;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.j7m.middleware.transport.rmi.RmiTransportProvider;
import org.junit.jupiter.api.Test;

public final class ReliabilityTransportRoundTripTest {
    @Test
    public void grpcMetadataPropagatesThroughGenericInvocationMetadata() throws Exception {
        GrpcTransportProvider clientTransport = new GrpcTransportProvider();

        roundTrip("grpc", new GrpcTransportProvider(), clientTransport);
    }

    @Test
    public void rmiMetadataPropagatesThroughGenericInvocationMetadata() throws Exception {
        RmiTransportProvider clientTransport = new RmiTransportProvider();

        roundTrip("rmi", new RmiTransportProvider(), clientTransport);
    }

    @Test
    public void coreAndRuntimeDoNotImportConcreteTransportImplementations() throws Exception {
        assertFalse(importsConcreteTransport(projectPath("j7m-kernel", "src", "main", "java")));
        assertFalse(importsConcreteTransport(projectPath("j7m-runtime", "src", "main", "java")));
    }

    private static void roundTrip(final String transportId, final TransportProvider serverTransport, final TransportProvider clientTransport) throws Exception {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime server = runtime("server-" + transportId, store, serverTransport);
             DefaultMiddlewareRuntime client = runtime("client-" + transportId, store, clientTransport)) {
            ObjectRegistration<RoundTripService> registration = server.export("round-trip", new RoundTripServiceImpl(),
                    RoundTripService.class, exportOptions(transportId));
            Method method = RoundTripService.class.getMethod("ping");
            InvocationOptions options = InvocationOptions.defaults().withReliabilityOptions(optionsFor(transportId));

            Object result = clientTransport.invoke(registration.ref(), MethodDescriptor.fromMethod(method), List.of(), options);

            assertEquals("pong", result);
            Map<String, String> metadata = server.status().recentInvocations().getLast().metadata();
            assertEquals("corr-" + transportId, metadata.get("reliability.correlationId"));
            assertEquals("trace-" + transportId, metadata.get("reliability.causalTraceId"));
            assertEquals("[redacted]", metadata.get("reliability.idempotencyKey"));
        }
    }

    private static DefaultMiddlewareRuntime runtime(final String id, final EmbeddedRegistryStore store, final TransportProvider transport) {
        return new MiddlewareRuntimeBuilder()
                .runtimeId(id)
                .registry(new EmbeddedRegistryClient(store, java.time.Clock.systemUTC()))
                .addTransport(transport)
                .disableNuggetAdmin()
                .build();
    }

    private static ExportOptions exportOptions(final String transportId) {
        return new ExportOptions(false, transportId, "default", List.of(), Map.of(), Duration.ofSeconds(30L), Duration.ofSeconds(5L));
    }

    private static InvocationReliabilityOptions optionsFor(final String transportId) {
        InvocationReliabilityOptions defaults = InvocationReliabilityOptions.defaults();
        return new InvocationReliabilityOptions(DeliverySemantics.atLeastOnceRetryable(), new IdempotencyKey("idem-" + transportId),
                null, null, null, null, null, new CausalContext("trace-" + transportId, "span-" + transportId, ""),
                new CorrelationContext("corr-" + transportId, "invoke-" + transportId, ""), defaults.replayPolicy(),
                defaults.duplicateDetectionPolicy(), defaults.backpressurePolicy(), false, 1024L, 1024L, null);
    }

    private static boolean importsConcreteTransport(final Path root) throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java")).anyMatch(path -> {
                try {
                    String source = Files.readString(path);
                    return source.contains("org.j7m.middleware.transport.grpc") || source.contains("org.j7m.middleware.transport.rmi");
                } catch (java.io.IOException exception) {
                    throw new AssertionError(exception);
                }
            });
        }
    }

    private static Path projectPath(final String first, final String second, final String third, final String fourth) {
        Path direct = Path.of(first, second, third, fourth);
        if (Files.exists(direct)) {
            return direct;
        }
        return Path.of("..", first, second, third, fourth);
    }

    public interface RoundTripService {
        @Idempotent
        String ping();
    }

    public static final class RoundTripServiceImpl implements RoundTripService {
        @Override
        public String ping() {
            return "pong";
        }
    }
}
