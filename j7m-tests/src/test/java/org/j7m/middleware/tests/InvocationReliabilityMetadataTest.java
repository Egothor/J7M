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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.util.Map;
import org.j7m.middleware.api.InvocationMetadata;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.reliability.CausalContext;
import org.j7m.middleware.api.reliability.CorrelationContext;
import org.j7m.middleware.api.reliability.DedupeKey;
import org.j7m.middleware.api.reliability.DeliveryMode;
import org.j7m.middleware.api.reliability.DeliverySemantics;
import org.j7m.middleware.api.reliability.DuplicateDetectionPolicy;
import org.j7m.middleware.api.reliability.FencingToken;
import org.j7m.middleware.api.reliability.IdempotencyKey;
import org.j7m.middleware.api.reliability.InvocationReliabilityOptions;
import org.j7m.middleware.api.reliability.OrderingKey;
import org.j7m.middleware.api.reliability.ReplayPolicy;
import org.junit.jupiter.api.Test;

public final class InvocationReliabilityMetadataTest {
    @Test
    public void invocationOptionsCarryReliabilityMetadata() {
        InvocationReliabilityOptions reliability = reliabilityOptions();
        InvocationOptions options = InvocationOptions.defaults().withReliabilityOptions(reliability);

        InvocationReliabilityOptions decoded = options.reliabilityOptions();

        assertEquals(DeliveryMode.EFFECTIVELY_ONCE_PATTERN, decoded.deliverySemantics().mode());
        assertEquals("idem-1", decoded.idempotencyKey().value());
        assertEquals("dedupe-1", decoded.dedupeKey().value());
        assertEquals("order-1", decoded.orderingKey().value());
        assertEquals("scope-a", decoded.fencingToken().scope());
        assertEquals("trace-1", decoded.causalContext().traceId());
        assertEquals("corr-1", decoded.correlationContext().correlationId());
    }

    @Test
    public void invocationMetadataExposesReliabilityContext() {
        ObjectRef ref = new ObjectRef("object-1", "svc/object-1", "svc", "runtime-a", "default",
                java.util.List.of(TestService.class.getName()), "grpc", "grpc://127.0.0.1:1", 1L, 1L, Map.of());
        InvocationMetadata metadata = new InvocationMetadata("invoke-1", ref, "ok", 1L, 1L, true, null,
                reliabilityOptions().toMetadata());

        assertNotNull(metadata.reliabilityContext());
        assertEquals("corr-1", metadata.reliabilityOptions().correlationContext().correlationId());
    }

    static InvocationReliabilityOptions reliabilityOptions() {
        return new InvocationReliabilityOptions(DeliverySemantics.effectivelyOncePatternSupport(),
                new IdempotencyKey("idem-1"), new DedupeKey("dedupe-1"), new OrderingKey("order-1"), null,
                new FencingToken("authority-a", "scope-a", 3L), null, new CausalContext("trace-1", "span-1", ""),
                new CorrelationContext("corr-1", "invoke-1", ""), ReplayPolicy.WHEN_IDEMPOTENCY_KEY_PRESENT,
                DuplicateDetectionPolicy.RETURN_PREVIOUS_RESULT,
                org.j7m.middleware.api.reliability.BackpressurePolicy.CALLER_MANAGED_RETRY_AFTER,
                false, 1024L, 2048L, Duration.ofMillis(25L));
    }

    interface TestService {
        String ok();
    }
}
