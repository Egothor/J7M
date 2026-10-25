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
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.RemoteError;

/**
 * Common public result header for admin command and query envelopes.
 *
 * <p>The header carries only safe operational state common to typed public
 * result families. It is suitable for deterministic JSON rendering and future
 * adapters, but it is not a substitute for the typed payload. Timing is
 * expressed as timestamps plus {@link Duration}; JSON artifacts render
 * duration in milliseconds. Errors must be normalized {@link RemoteError}
 * instances with safe messages and metadata. Exactly one outcome flag must be
 * {@code true}, and that flag must correspond to {@link #outcome()}; for
 * example, an {@link NuggetAdminOutcome#UNAVAILABLE} result is unavailable but
 * not completed.</p>
 *
 * <p>Instances are immutable and thread-safe.</p>
 *
 * @param protocolVersion public admin protocol version
 * @param formatName normative JSON format name
 * @param formatVersion normative JSON format version
 * @param requestId request id
 * @param correlationId correlation id
 * @param operationId stable operation id, when known
 * @param operationKind typed operation family
 * @param outcome common outcome
 * @param accepted whether a provider accepted responsibility for the operation
 * @param inProgress whether work remains in progress
 * @param completed whether the operation completed
 * @param denied whether admission, authorization, target, state, or
 *        confirmation policy denied the operation
 * @param unavailable whether the provider could not evaluate the operation
 * @param timedOut whether the operation exceeded a request or server budget
 * @param partial whether optional data is partial
 * @param outcomeUnknown whether the final outcome is not known
 * @param returnedCount returned item count for collection-like responses
 * @param totalAvailable total available item count when authorized and known
 * @param truncated whether hard response bounds truncated the response
 * @param warnings safe warnings
 * @param error normalized safe error, or {@code null}
 * @param requestedAt request timestamp
 * @param completedAt completion timestamp
 * @param elapsed elapsed server-side duration
 * @param metadata safe result metadata in schema-visible order
 */
public record NuggetAdminResultHeader(
        int protocolVersion,
        String formatName,
        int formatVersion,
        String requestId,
        String correlationId,
        String operationId,
        NuggetAdminOperationKind operationKind,
        NuggetAdminOutcome outcome,
        boolean accepted,
        boolean inProgress,
        boolean completed,
        boolean denied,
        boolean unavailable,
        boolean timedOut,
        boolean partial,
        boolean outcomeUnknown,
        int returnedCount,
        long totalAvailable,
        boolean truncated,
        List<String> warnings,
        RemoteError error,
        Instant requestedAt,
        Instant completedAt,
        Duration elapsed,
        Map<String, String> metadata) {
    /**
     * Creates a validated immutable result header.
     */
    public NuggetAdminResultHeader {
        NuggetAdminContract.requireSupportedProtocol(protocolVersion);
        if (!NuggetAdminContract.JSON_FORMAT_NAME.equals(formatName)) {
            throw new IllegalArgumentException("unsupported nugget admin JSON format name");
        }
        if (formatVersion != NuggetAdminContract.JSON_FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported nugget admin JSON format version");
        }
        requestId = AdminContractSupport.token(requestId, "requestId");
        correlationId = AdminContractSupport.token(correlationId, "correlationId");
        operationId = AdminContractSupport.optionalToken(operationId);
        if (operationKind == null) {
            throw new IllegalArgumentException("operationKind must not be null");
        }
        outcome = outcome == null ? NuggetAdminOutcome.OUTCOME_UNKNOWN : outcome;
        if (returnedCount < 0 || totalAvailable < 0L) {
            throw new IllegalArgumentException("result counts must not be negative");
        }
        if (returnedCount > NuggetAdminContract.MAX_PAGE_LIMIT) {
            throw new IllegalArgumentException("returned count outside nugget admin bounds");
        }
        if (totalAvailable < (long) returnedCount) {
            throw new IllegalArgumentException("total available count must not be less than returned count");
        }
        validateOutcomeFlags(
                outcome,
                accepted,
                inProgress,
                completed,
                denied,
                unavailable,
                timedOut,
                partial,
                outcomeUnknown);
        warnings = AdminContractSupport.safeStringList(
                warnings,
                NuggetAdminContract.MAX_WARNING_ENTRIES,
                NuggetAdminContract.MAX_WARNING_UTF8_BYTES);
        error = AdminPayloadNormalizer.remoteError(error);
        requestedAt = requestedAt == null ? Instant.EPOCH : requestedAt;
        completedAt = completedAt == null ? requestedAt : completedAt;
        elapsed = AdminContractSupport.boundedDuration(
                elapsed,
                Duration.ZERO,
                NuggetAdminContract.MAX_DURATION);
        metadata = AdminContractSupport.orderedStringMap(metadata, NuggetAdminContract.MAX_METADATA_ENTRIES);
    }

    private static void validateOutcomeFlags(
            final NuggetAdminOutcome outcome,
            final boolean accepted,
            final boolean inProgress,
            final boolean completed,
            final boolean denied,
            final boolean unavailable,
            final boolean timedOut,
            final boolean partial,
            final boolean outcomeUnknown) {
        final int matches = (accepted ? 1 : 0)
                + (inProgress ? 1 : 0)
                + (completed ? 1 : 0)
                + (denied ? 1 : 0)
                + (unavailable ? 1 : 0)
                + (timedOut ? 1 : 0)
                + (partial ? 1 : 0)
                + (outcomeUnknown ? 1 : 0);
        if (matches != 1) {
            throw new IllegalArgumentException("exactly one nugget admin outcome flag must be true");
        }
        final boolean expected = switch (outcome) {
            case ACCEPTED -> accepted;
            case IN_PROGRESS -> inProgress;
            case COMPLETED -> completed;
            case DENIED -> denied;
            case UNAVAILABLE -> unavailable;
            case TIMED_OUT -> timedOut;
            case PARTIAL -> partial;
            case OUTCOME_UNKNOWN -> outcomeUnknown;
        };
        if (!expected) {
            throw new IllegalArgumentException("nugget admin outcome flag does not match outcome");
        }
    }

    /**
     * Creates an unavailable header for an older or unimplemented provider.
     *
     * @param context request context
     * @param operationId operation id, or empty
     * @param error safe unavailable error
     * @return unavailable result header
     */
    public static NuggetAdminResultHeader unavailable(
            final NuggetAdminRequestContext context,
            final String operationId,
            final RemoteError error) {
        final Instant requestedAt = context == null ? Instant.EPOCH : context.requestedAt();
        return new NuggetAdminResultHeader(
                NuggetAdminContract.PROTOCOL_VERSION,
                NuggetAdminContract.JSON_FORMAT_NAME,
                NuggetAdminContract.JSON_FORMAT_VERSION,
                context == null ? "default-unavailable" : context.requestId(),
                context == null ? "default-unavailable" : context.correlationId(),
                operationId,
                context == null ? NuggetAdminOperationKind.CAPABILITIES : context.operationKind(),
                NuggetAdminOutcome.UNAVAILABLE,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                0,
                0L,
                false,
                List.of("provider-unavailable"),
                error,
                requestedAt,
                requestedAt,
                Duration.ZERO,
                Map.of("reason", "provider-unavailable"));
    }
}
