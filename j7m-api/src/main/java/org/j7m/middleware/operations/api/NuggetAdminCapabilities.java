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

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Public capability result for the Phase 7.8 admin contract.
 *
 * <p>Capability presence means only that a nugget or provider can evaluate the
 * operation family. It is not admission, authorization, or a guarantee that a
 * particular caller may execute a mutating operation. Implementations should
 * report {@link #available()} as {@code false} when the selected provider is an
 * older implementation that cannot evaluate the public contract.</p>
 *
 * <p>Instances are immutable and thread-safe.</p>
 *
 * @param available whether the provider can evaluate this public contract
 * @param protocolVersion supported public protocol version
 * @param jsonFormatName supported JSON format name
 * @param jsonFormatVersion supported JSON format version
 * @param capabilities supported operation capabilities in stable public order
 * @param operationKinds supported operation families in stable public order
 * @param maxPageLimit maximum accepted page limit
 * @param maxMetadataEntries maximum retained safe metadata entries
 * @param collectedAt collection timestamp
 * @param metadata safe metadata in schema-visible order
 */
public record NuggetAdminCapabilities(
        boolean available,
        int protocolVersion,
        String jsonFormatName,
        int jsonFormatVersion,
        List<NuggetCapability> capabilities,
        List<NuggetAdminOperationKind> operationKinds,
        int maxPageLimit,
        int maxMetadataEntries,
        Instant collectedAt,
        Map<String, String> metadata) {
    /**
     * Stable payload type id for capability result envelopes.
     */
    public static final String PAYLOAD_TYPE = "nugget-admin-capabilities";
    /**
     * Maximum supported capability entries.
     */
    public static final int MAX_CAPABILITIES = 64;
    /**
     * Maximum supported operation family entries.
     */
    public static final int MAX_OPERATION_KINDS = 32;

    /**
     * Creates immutable capability metadata.
     */
    public NuggetAdminCapabilities {
        if (protocolVersion != 0) {
            NuggetAdminContract.requireSupportedProtocol(protocolVersion);
        }
        if (protocolVersion != 0 && !NuggetAdminContract.JSON_FORMAT_NAME.equals(jsonFormatName)) {
            throw new IllegalArgumentException("unsupported nugget admin JSON format name");
        }
        if (protocolVersion != 0 && jsonFormatVersion != NuggetAdminContract.JSON_FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported nugget admin JSON format version");
        }
        jsonFormatName = protocolVersion == 0
                ? AdminContractSupport.optionalToken(jsonFormatName)
                : NuggetAdminContract.JSON_FORMAT_NAME;
        capabilities = boundedList(capabilities, MAX_CAPABILITIES, "capabilities");
        operationKinds = boundedList(operationKinds, MAX_OPERATION_KINDS, "operationKinds");
        if (maxPageLimit < 0 || maxPageLimit > NuggetAdminContract.MAX_PAGE_LIMIT) {
            throw new IllegalArgumentException("maxPageLimit outside nugget admin bounds");
        }
        if (maxMetadataEntries < 0 || maxMetadataEntries > NuggetAdminContract.MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException("maxMetadataEntries outside nugget admin bounds");
        }
        collectedAt = collectedAt == null ? Instant.EPOCH : collectedAt;
        metadata = AdminContractSupport.orderedStringMap(metadata, NuggetAdminContract.MAX_METADATA_ENTRIES);
    }

    /**
     * Returns an unavailable capability result for older providers.
     *
     * @return unavailable capability result
     */
    public static NuggetAdminCapabilities unavailable() {
        return new NuggetAdminCapabilities(
                false,
                0,
                "",
                0,
                List.of(),
                List.of(),
                0,
                0,
                Instant.EPOCH,
                Map.of("reason", "provider-unavailable"));
    }

    private static <E> List<E> boundedList(final List<E> source, final int maximum, final String name) {
        if (source == null) {
            return List.of();
        }
        final java.util.ArrayList<E> copy = new java.util.ArrayList<>(maximum);
        try {
            for (E value : source) {
                if (copy.size() >= maximum) {
                    throw new IllegalArgumentException(name + " outside nugget admin capability bounds");
                }
                if (value == null) {
                    throw new IllegalArgumentException(name + " must not contain null values");
                }
                copy.add(value);
            }
        } catch (UnsupportedOperationException | java.util.ConcurrentModificationException | IllegalStateException exception) {
            return List.of();
        }
        return copy.isEmpty() ? List.of() : List.copyOf(copy);
    }
}
