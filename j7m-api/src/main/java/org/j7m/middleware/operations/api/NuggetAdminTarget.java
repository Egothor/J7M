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
 * Public target identity for an admin request.
 *
 * <p>The authoritative target fields are {@link #nuggetId()},
 * {@link #runtimeId()}, and {@link #generation()}. Display names, endpoint
 * names, provider identifiers, and CLI prompt text are only metadata and must
 * not be used as authority for mutation, authorization, or stale-target
 * acceptance. A generation of {@code 0} means "current or unknown" only for
 * operations that explicitly document that weaker match. Mutating operations
 * should use an exact positive generation whenever the provider can supply
 * one.</p>
 *
 * <p>Instances are immutable and thread-safe.</p>
 *
 * @param nuggetId stable nugget identity
 * @param runtimeId stable runtime identity
 * @param generation target generation, or {@code 0} for explicitly documented
 *        current/unknown matching
 * @param endpointIdentity optional safe endpoint identity metadata
 * @param providerIdentity optional safe provider identity metadata
 */
public record NuggetAdminTarget(
        String nuggetId,
        String runtimeId,
        long generation,
        String endpointIdentity,
        String providerIdentity) {
    /**
     * Creates a validated target identity.
     */
    public NuggetAdminTarget {
        nuggetId = AdminContractSupport.token(nuggetId, "nuggetId");
        runtimeId = AdminContractSupport.token(runtimeId, "runtimeId");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        endpointIdentity = AdminContractSupport.optionalToken(endpointIdentity);
        providerIdentity = AdminContractSupport.optionalToken(providerIdentity);
    }

    /**
     * Creates a target for the current or unknown generation.
     *
     * @param nuggetId stable nugget identity
     * @param runtimeId stable runtime identity
     * @return target with generation {@code 0}
     */
    public static NuggetAdminTarget current(final String nuggetId, final String runtimeId) {
        return new NuggetAdminTarget(nuggetId, runtimeId, 0L, "", "");
    }
}
