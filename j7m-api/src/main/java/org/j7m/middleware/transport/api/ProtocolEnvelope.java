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

package org.j7m.middleware.transport.api;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;

/**
 * Bounded transport-neutral command envelope. Authentication is supplied by the established transport,
 * never inferred from the claimed node identity. Immutable and safe to share across threads.
 * @param protocolVersion supported major protocol version (currently 1)
 * @param requestId stable idempotency/correlation key
 * @param schemaId registered payload schema identifier
 * @param schemaVersion positive payload version
 * @param deadline absolute UTC execution deadline
 * @param traceContext bounded public trace metadata, containing no credentials
 * @param cancellation whether the request cancels the referenced operation
 * @param nodeId claimed sender bound to authenticated transport identity at dispatch
 * @param payload encoded payload, limited to 1 MiB and defensively copied
 */
public record ProtocolEnvelope(int protocolVersion, String requestId, String schemaId, int schemaVersion,
        Instant deadline, Map<String, String> traceContext, boolean cancellation, String nodeId, byte[] payload) {
    /**
     * Snapshots mutable input and rejects unsupported protocol versions or size limits before dispatch.
     * @throws IllegalArgumentException if any identity, version or size is invalid
     */
    public ProtocolEnvelope {
        checkText(requestId);
        checkText(schemaId);
        checkText(nodeId);
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(payload, "payload");
        traceContext = Map.copyOf(traceContext);
        long bytes = 0;
        for (Map.Entry<String, String> item : traceContext.entrySet()) {
            checkText(item.getKey());
            if (item.getValue().length() > 4096) {
                throw new IllegalArgumentException("trace value exceeds limit");
            }
            bytes += item.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    + item.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        if (protocolVersion != 1 || schemaVersion < 1 || payload.length > 1024 * 1024
                || traceContext.size() > 32 || bytes > 8192) {
            throw new IllegalArgumentException("unsupported or oversized protocol envelope");
        }
        payload = payload.clone();
    }

    /**
     * Returns an independent copy of the bounded encoded payload.
     * @return copied payload bytes
     */
    @Override
    public byte[] payload() {
        return this.payload.clone();
    }

    /**
     * Validates identity and deadline before invoking handlers or mutating state.
     * @param authenticatedNode trusted transport-authenticated peer identity
     * @param clock injected UTC clock
     * @throws MiddlewareException if identity mismatches or deadline has expired
     */
    public void validateForDispatch(final String authenticatedNode, final Clock clock) {
        if (!this.nodeId.equals(authenticatedNode)) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAUTHENTICATED, "envelope peer identity mismatch");
        }
        if (!clock.instant().isBefore(this.deadline)) {
            throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "envelope deadline expired");
        }
    }

    private static void checkText(final String text) {
        if (text == null || text.isBlank() || text.length() > 128) {
            throw new IllegalArgumentException("envelope identity must contain 1..128 characters");
        }
    }
}
