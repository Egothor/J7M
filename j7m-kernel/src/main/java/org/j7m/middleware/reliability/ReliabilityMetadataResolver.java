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

package org.j7m.middleware.reliability;

import java.util.LinkedHashMap;
import java.util.Map;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.reliability.InvocationReliabilityOptions;
import org.j7m.middleware.api.reliability.ReliabilityContext;

/**
 * Resolves typed reliability metadata from public invocation options.
 *
 * <p>The resolver is stateless and thread-safe.</p>
 */
public final class ReliabilityMetadataResolver {
    /**
     * Creates a resolver.
     */
    public ReliabilityMetadataResolver() {
    }

    /**
     * Resolves the reliability context for invocation options.
     *
     * @param options invocation options
     * @return reliability context
     */
    public ReliabilityContext resolve(final InvocationOptions options) {
        return ReliabilityContext.from(options);
    }

    /**
     * Returns safe status metadata for admin and invocation logs.
     *
     * @param options reliability options
     * @return redacted metadata
     */
    public Map<String, String> redactedMetadata(final InvocationReliabilityOptions options) {
        InvocationReliabilityOptions safe = options == null ? InvocationReliabilityOptions.defaults() : options;
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("reliability.deliveryMode", safe.deliverySemantics().mode().name());
        metadata.put("reliability.retrySafeRequested", Boolean.toString(safe.deliverySemantics().retrySafe()));
        metadata.put("reliability.effectivelyOncePattern", Boolean.toString(safe.deliverySemantics().effectivelyOncePattern()));
        metadata.put("reliability.replayPolicy", safe.replayPolicy().name());
        metadata.put("reliability.duplicateDetectionPolicy", safe.duplicateDetectionPolicy().name());
        metadata.put("reliability.backpressurePolicy", safe.backpressurePolicy().name());
        if (safe.idempotencyKey() != null) {
            metadata.put("reliability.idempotencyKey", "[redacted]");
        }
        if (safe.dedupeKey() != null) {
            metadata.put("reliability.dedupeKey", "[redacted]");
        }
        if (safe.orderingKey() != null) {
            metadata.put("reliability.orderingKey", "[redacted]");
        }
        if (safe.durableOperationId() != null) {
            metadata.put("reliability.durableOperationId", "[redacted]");
        }
        if (safe.fencingToken() != null) {
            metadata.put("reliability.fencingAuthority", safe.fencingToken().authority());
            metadata.put("reliability.fencingScope", safe.fencingToken().scope());
            metadata.put("reliability.fencingToken", "[redacted]");
        }
        if (safe.monotonicEpoch() != null) {
            metadata.put("reliability.epochAuthority", safe.monotonicEpoch().authority());
            metadata.put("reliability.epochScope", safe.monotonicEpoch().scope());
            metadata.put("reliability.epoch", "[redacted]");
        }
        if (safe.causalContext() != null) {
            metadata.put("reliability.causalTraceId", safe.causalContext().traceId());
            metadata.put("reliability.causalSpanId", safe.causalContext().spanId());
            metadata.put("reliability.causalParentSpanId", safe.causalContext().parentSpanId());
        }
        if (safe.correlationContext() != null) {
            metadata.put("reliability.correlationId", safe.correlationContext().correlationId());
            metadata.put("reliability.correlationInvocationId", safe.correlationContext().invocationId());
            metadata.put("reliability.correlationParentInvocationId", safe.correlationContext().parentInvocationId());
        }
        if (safe.maxPayloadBytes() > 0L) {
            metadata.put("reliability.maxPayloadBytes", Long.toString(safe.maxPayloadBytes()));
        }
        if (safe.maxResultBytes() > 0L) {
            metadata.put("reliability.maxResultBytes", Long.toString(safe.maxResultBytes()));
        }
        if (safe.retryAfter() != null) {
            metadata.put("reliability.retryAfterMillis", Long.toString(safe.retryAfter().toMillis()));
        }
        metadata.put("reliability.cancellationRequested", Boolean.toString(safe.cancellationRequested()));
        return Map.copyOf(metadata);
    }
}
