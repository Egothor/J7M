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
import java.util.List;
import java.util.Map;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.ObjectRegistration;
import org.j7m.middleware.api.reliability.DedupeKey;
import org.j7m.middleware.api.reliability.DeliverySemantics;
import org.j7m.middleware.api.reliability.DuplicateDetectionPolicy;
import org.j7m.middleware.api.reliability.IdempotencyKey;
import org.j7m.middleware.api.reliability.InvocationReliabilityOptions;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.api.MethodDescriptor;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;

public final class ReliabilityAdminStatusTest {
    @Test
    public void reliabilityCountersAndRedactedMetadataAreVisible() throws Exception {
        try (DefaultMiddlewareRuntime runtime = runtime("reliability-admin")) {
            ObjectRegistration<AdminService> registration = runtime.export("admin-service", new AdminServiceImpl(),
                    AdminService.class, exportOptions());
            Method method = AdminService.class.getMethod("retrySafe");
            InvocationOptions options = InvocationOptions.defaults().withReliabilityOptions(options());

            Object result = runtime.decodeValue(runtime.dispatch(registration.ref(), MethodDescriptor.fromMethod(method), List.of(), options), String.class);

            assertEquals("ok", result);
            Map<String, String> status = runtime.status().security();
            assertEquals("1", status.get("reliability.idempotencyKeyUsageCount"));
            assertEquals("1", status.get("reliability.duplicateDetectionDecisionCount"));
            assertEquals("2", status.get("reliability.retrySafeDecisionCount"));
            assertEquals("non-durable", status.get("reliability.dedupeStoreDurability"));
            assertEquals("unsupported", status.get("reliability.exactlyOnce"));
            String recentMetadata = runtime.status().recentInvocations().getLast().metadata().toString();
            assertTrue(recentMetadata.contains("reliability.idempotencyKey=[redacted]"));
            assertFalse(recentMetadata.contains("idem-admin"));
            assertFalse(recentMetadata.contains("dedupe-admin"));
        }
    }

    private static DefaultMiddlewareRuntime runtime(final String id) {
        return new MiddlewareRuntimeBuilder()
                .runtimeId(id)
                .registry(new EmbeddedRegistryClient())
                .addTransport(new GrpcTransportProvider())
                .disableNuggetAdmin()
                .build();
    }

    private static ExportOptions exportOptions() {
        return new ExportOptions(false, "grpc", "default", List.of(), Map.of(), java.time.Duration.ofSeconds(30L),
                java.time.Duration.ofSeconds(5L));
    }

    private static InvocationReliabilityOptions options() {
        InvocationReliabilityOptions defaults = InvocationReliabilityOptions.defaults();
        return new InvocationReliabilityOptions(DeliverySemantics.atLeastOnceRetryable(), new IdempotencyKey("idem-admin"),
                new DedupeKey("dedupe-admin"), null, null, null, null,
                org.j7m.middleware.api.reliability.CausalContext.root(),
                org.j7m.middleware.api.reliability.CorrelationContext.root(), defaults.replayPolicy(),
                DuplicateDetectionPolicy.REJECT_DUPLICATE, defaults.backpressurePolicy(), false, 1024L, 1024L, null);
    }

    public interface AdminService {
        @Idempotent
        String retrySafe();
    }

    public static final class AdminServiceImpl implements AdminService {
        @Override
        public String retrySafe() {
            return "ok";
        }
    }
}
