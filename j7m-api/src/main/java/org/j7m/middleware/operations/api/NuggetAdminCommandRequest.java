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

/**
 * Shared public envelope for one typed admin operation request.
 *
 * <p>The envelope references an existing typed request payload or a small
 * contract DTO. It deliberately rejects strings and maps as payloads so callers
 * cannot smuggle command-line text, free-form method names, or arbitrary JSON
 * object maps into a second dispatch model. Service implementations must still
 * evaluate the typed family directly and must not use this envelope as a
 * reflection router. The operation family binding is owned by
 * {@link NuggetAdminOperationKind}; construction validates the request protocol
 * version through {@link NuggetAdminRequestContext}, the operation kind, the
 * exact public DTO payload family, the null-payload policy, and the stable
 * payload type token before a provider can receive the request.</p>
 *
 * <p>Instances are immutable and thread-safe.</p>
 *
 * @param context shared request context
 * @param payload typed request payload, or {@code null} for capability queries
 * @param payloadType stable public payload type id for schema diagnostics
 * @param <T> typed payload family
 */
public record NuggetAdminCommandRequest<T>(
        NuggetAdminRequestContext context,
        T payload,
        String payloadType) {
    /**
     * Creates a validated immutable command request.
     */
    public NuggetAdminCommandRequest {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        payloadType = AdminContractSupport.optionalToken(payloadType);
        NuggetAdminOperationKind.ValidatedPayload validated =
                context.operationKind().validateAndNormalizeRequestPayload(payload, payloadType);
        AdminContractSupport.validateRequestTargetConsistency(context.target(), validated.payload());
        payload = castPayload(validated.payload());
        payloadType = validated.payloadType();
    }

    @SuppressWarnings("unchecked")
    private static <T> T castPayload(final Object payload) {
        return (T) payload;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "NuggetAdminCommandRequest[context=" + this.context
                + ", payload=<typed-redacted>, payloadType=" + this.payloadType + "]";
    }
}
