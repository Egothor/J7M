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

package org.j7m.middleware.api.reliability;

import java.io.Serializable;
import java.util.UUID;

/**
 * Causal relationship metadata for root and child invocations.
 *
 * <p>The record is immutable, serializable, and thread-safe. It contains only
 * stable identifiers, not payload data.</p>
 *
 * @param traceId causal trace identifier
 * @param spanId current span identifier
 * @param parentSpanId parent span identifier, or empty for a root context
 */
public record CausalContext(String traceId, String spanId, String parentSpanId) implements Serializable {
    private static final int MAX_LENGTH = 128;

    /**
     * Creates a validated causal context.
     *
     * @throws IllegalArgumentException when an identifier is invalid
     */
    public CausalContext {
        traceId = ReliabilityValues.requireLogSafe("causal trace id", traceId, MAX_LENGTH);
        spanId = ReliabilityValues.requireLogSafe("causal span id", spanId, MAX_LENGTH);
        parentSpanId = parentSpanId == null ? "" : parentSpanId;
        if (!parentSpanId.isBlank()) {
            parentSpanId = ReliabilityValues.requireLogSafe("causal parent span id", parentSpanId, MAX_LENGTH);
        }
    }

    /**
     * Creates a root causal context.
     *
     * @return root context
     */
    public static CausalContext root() {
        return new CausalContext(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "");
    }

    /**
     * Creates a child causal context under this context.
     *
     * @return child context
     */
    public CausalContext child() {
        return new CausalContext(this.traceId, UUID.randomUUID().toString(), this.spanId);
    }

    /**
     * Reports whether this context has a parent span.
     *
     * @return {@code true} when parent metadata is present
     */
    public boolean hasParent() {
        return !this.parentSpanId.isBlank();
    }
}
