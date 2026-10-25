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

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.NonIdempotent;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRegistration;
import org.j7m.middleware.api.reliability.CausalContext;
import org.j7m.middleware.api.reliability.CorrelationContext;
import org.j7m.middleware.api.reliability.DeliverySemantics;
import org.j7m.middleware.api.reliability.InvocationReliabilityOptions;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.api.MethodDescriptor;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;

public final class ReliabilityRegressionTest {
    @Test
    public void existingIdempotentAndNonIdempotentAnnotationsRemainAuthoritative() throws Exception {
        try (DefaultMiddlewareRuntime runtime = runtime()) {
            ObjectRegistration<RegressionService> registration = runtime.export("regression", new RegressionServiceImpl(),
                    RegressionService.class, exportOptions());
            Method safe = RegressionService.class.getMethod("safe");
            Method unsafe = RegressionService.class.getMethod("unsafe");
            InvocationReliabilityOptions defaults = InvocationReliabilityOptions.defaults();
            InvocationOptions retryable = InvocationOptions.defaults().withReliabilityOptions(new InvocationReliabilityOptions(
                    DeliverySemantics.atLeastOnceRetryable(), null, null, null, null, null, null, null, null,
                    defaults.replayPolicy(), defaults.duplicateDetectionPolicy(), defaults.backpressurePolicy(), false, 0L, 0L, null));

            assertEquals("safe", runtime.decodeValue(runtime.dispatch(registration.ref(), MethodDescriptor.fromMethod(safe), List.of(), retryable), String.class));
            MiddlewareException exception = org.junit.jupiter.api.Assertions.assertThrows(MiddlewareException.class,
                    () -> runtime.dispatch(registration.ref(), MethodDescriptor.fromMethod(unsafe), List.of(), retryable));
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, exception.code());
        }
    }

    @Test
    public void invocationContextPreservesCausalAndCorrelationMetadata() throws Exception {
        try (DefaultMiddlewareRuntime runtime = runtime()) {
            ObjectRegistration<RegressionService> registration = runtime.export("regression", new RegressionServiceImpl(),
                    RegressionService.class, exportOptions());
            Method safe = RegressionService.class.getMethod("safe");
            InvocationReliabilityOptions defaults = InvocationReliabilityOptions.defaults();
            InvocationOptions options = InvocationOptions.defaults().withReliabilityOptions(new InvocationReliabilityOptions(
                    DeliverySemantics.atLeastOnceRetryable(), null, null, null, null, null, null,
                    new CausalContext("trace-regression", "span-regression", ""),
                    new CorrelationContext("corr-regression", "invoke-regression", ""), defaults.replayPolicy(),
                    defaults.duplicateDetectionPolicy(), defaults.backpressurePolicy(), false, 0L, 0L, null));

            runtime.dispatch(registration.ref(), MethodDescriptor.fromMethod(safe), List.of(), options);

            Map<String, String> metadata = runtime.status().recentInvocations().getLast().metadata();
            assertEquals("trace-regression", metadata.get("reliability.causalTraceId"));
            assertEquals("corr-regression", metadata.get("reliability.correlationId"));
            assertFalse(metadata.toString().contains("PRIVATE"));
        }
    }

    private static DefaultMiddlewareRuntime runtime() {
        return new MiddlewareRuntimeBuilder()
                .runtimeId("reliability-regression")
                .registry(new EmbeddedRegistryClient())
                .addTransport(new GrpcTransportProvider())
                .disableNuggetAdmin()
                .build();
    }

    private static ExportOptions exportOptions() {
        return new ExportOptions(false, "grpc", "default", List.of(), Map.of(), Duration.ofSeconds(30L), Duration.ofSeconds(5L));
    }

    public interface RegressionService {
        @Idempotent
        String safe();

        @NonIdempotent
        String unsafe();
    }

    public static final class RegressionServiceImpl implements RegressionService {
        @Override
        public String safe() {
            return "safe";
        }

        @Override
        public String unsafe() {
            return "unsafe";
        }
    }
}
