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
 * Shared public envelope for one typed admin operation result.
 *
 * <p>The payload is a typed public DTO such as an introspection collection,
 * lifecycle result, control result, discovery result, or capability result.
 * Callers must use {@link #header()} for common outcome, error, count,
 * warning, timing, partial, and truncation semantics, then interpret
 * {@link #payload()} according to the typed operation family. The operation
 * family binding is owned by {@link NuggetAdminOperationKind}; construction
 * validates the exact public DTO result family, the null-payload policy,
 * common outcome flags, mutation-only header state on read-only operations, and
 * the stable payload type token.</p>
 *
 * <p>Instances are immutable and thread-safe.</p>
 *
 * @param target authoritative result target, or {@code null} only for result
 *        payloads that do not repeat target identity
 * @param header common result header
 * @param payload typed result payload, or {@code null}
 * @param payloadType stable public payload type id for schema diagnostics
 * @param <T> typed payload family
 */
public record NuggetAdminCommandResult<T>(
        NuggetAdminTarget target,
        NuggetAdminResultHeader header,
        T payload,
        String payloadType) {
    /**
     * Creates an immutable command result for payloads that do not repeat the
     * authoritative target identity.
     *
     * @param header common result header
     * @param payload typed result payload, or {@code null}
     * @param payloadType stable public payload type id
     */
    public NuggetAdminCommandResult(
            final NuggetAdminResultHeader header,
            final T payload,
            final String payloadType) {
        this(null, header, payload, payloadType);
    }

    /**
     * Creates an immutable command result.
     */
    public NuggetAdminCommandResult {
        if (header == null) {
            throw new IllegalArgumentException("header must not be null");
        }
        payloadType = AdminContractSupport.optionalToken(payloadType);
        NuggetAdminOperationKind.ValidatedPayload validated =
                header.operationKind().validateAndNormalizeResultPayload(header, payload, payloadType);
        AdminContractSupport.validateResultTargetConsistency(target, validated.payload());
        AdminContractSupport.validateResultOperationIdConsistency(header, validated.payload());
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
        return "NuggetAdminCommandResult[target=" + this.target + ", header=" + this.header
                + ", payload=<typed-redacted>, payloadType=" + this.payloadType + "]";
    }
}
