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
 * Correlation metadata propagated across nested invocations.
 *
 * <p>The record is immutable, serializable, and thread-safe. A nested call
 * preserves the correlation id while assigning a distinct invocation id.</p>
 *
 * @param correlationId stable correlation id
 * @param invocationId current invocation id
 * @param parentInvocationId parent invocation id, or empty for a root invocation
 */
public record CorrelationContext(String correlationId, String invocationId, String parentInvocationId) implements Serializable {
    private static final int MAX_LENGTH = 128;

    /**
     * Creates a validated correlation context.
     *
     * @throws IllegalArgumentException when an identifier is invalid
     */
    public CorrelationContext {
        correlationId = ReliabilityValues.requireLogSafe("correlation id", correlationId, MAX_LENGTH);
        invocationId = ReliabilityValues.requireLogSafe("correlation invocation id", invocationId, MAX_LENGTH);
        parentInvocationId = parentInvocationId == null ? "" : parentInvocationId;
        if (!parentInvocationId.isBlank()) {
            parentInvocationId = ReliabilityValues.requireLogSafe("parent invocation id", parentInvocationId, MAX_LENGTH);
        }
    }

    /**
     * Creates a root correlation context.
     *
     * @return root context
     */
    public static CorrelationContext root() {
        return new CorrelationContext(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "");
    }

    /**
     * Creates a nested invocation context preserving the correlation id.
     *
     * @return nested context
     */
    public CorrelationContext nestedInvocation() {
        return new CorrelationContext(this.correlationId, UUID.randomUUID().toString(), this.invocationId);
    }

    /**
     * Reports whether this context has a parent invocation.
     *
     * @return {@code true} when parent metadata is present
     */
    public boolean hasParent() {
        return !this.parentInvocationId.isBlank();
    }
}
