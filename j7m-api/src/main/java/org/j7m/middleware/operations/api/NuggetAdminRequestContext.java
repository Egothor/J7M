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

package org.j7m.middleware.operations.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Shared request context for public nugget administration operations.
 *
 * <p>The context carries safe client and correlation metadata for typed
 * operation families. It does not carry PIN material, admission credentials,
 * authorization decisions, HTTP state, browser session state, OAuth tokens, or
 * arbitrary provider data. The client budget and optional client deadline are
 * metadata used to propagate a bounded caller budget; they are not an
 * authoritative clock source. Providers and runtimes remain responsible for
 * server-side deadline enforcement and must preserve the existing absolute
 * deadline fields on {@link NuggetApplicationLifecycle.Request} and
 * {@link NuggetControl.Request}.</p>
 *
 * <p>The constructor rejects unsupported protocol versions before an instance
 * can be submitted to a service method. Instances are immutable and
 * thread-safe.</p>
 *
 * @param protocolVersion requested public admin protocol version
 * @param requestId stable request id for this caller attempt
 * @param correlationId safe audit correlation id
 * @param target authoritative target identity, or {@code null} for discovery
 *        and all-target commands
 * @param operationKind typed operation family
 * @param requestedAt safe request creation timestamp
 * @param clientBudget bounded caller budget metadata
 * @param clientDeadlineAt optional caller deadline metadata
 * @param metadata safe request metadata in schema-visible insertion order
 */
public record NuggetAdminRequestContext(
        int protocolVersion,
        String requestId,
        String correlationId,
        NuggetAdminTarget target,
        NuggetAdminOperationKind operationKind,
        Instant requestedAt,
        Duration clientBudget,
        Instant clientDeadlineAt,
        Map<String, String> metadata) {
    /**
     * Creates a validated immutable context.
     */
    public NuggetAdminRequestContext {
        NuggetAdminContract.requireSupportedProtocol(protocolVersion);
        requestId = AdminContractSupport.token(requestId, "requestId");
        correlationId = AdminContractSupport.token(correlationId, "correlationId");
        if (operationKind == null) {
            throw new IllegalArgumentException("operationKind must not be null");
        }
        requestedAt = requestedAt == null ? Instant.EPOCH : requestedAt;
        clientBudget = AdminContractSupport.boundedDuration(
                clientBudget,
                NuggetAdminContract.DEFAULT_CLIENT_BUDGET,
                NuggetAdminContract.MAX_CLIENT_BUDGET);
        clientDeadlineAt = clientDeadlineAt == null ? Instant.EPOCH : clientDeadlineAt;
        metadata = AdminContractSupport.orderedStringMap(metadata, NuggetAdminContract.MAX_METADATA_ENTRIES);
    }
}
