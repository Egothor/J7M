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

package org.j7m.middleware.api.coordination;

import java.io.Serializable;
import java.util.Map;

/**
 * Immutable public identity for a coordination member.
 *
 * @param memberId stable member id
 * @param endpoint safe endpoint descriptor
 * @param role declared member role
 * @param metadata safe metadata, never raw payload or secrets
 */
public record MemberIdentity(
        MemberId memberId,
        MemberEndpoint endpoint,
        MemberRole role,
        Map<String, String> metadata) implements Serializable {
    /**
     * Creates a validated member identity.
     *
     * @throws IllegalArgumentException when required values or metadata are invalid
     */
    public MemberIdentity {
        if (memberId == null) {
            throw new IllegalArgumentException("member id is required");
        }
        if (endpoint == null) {
            throw new IllegalArgumentException("member endpoint is required");
        }
        role = role == null ? MemberRole.WORKER : role;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            CoordinationValues.safeText("member metadata key", entry.getKey(), 128);
            CoordinationValues.safeText("member metadata value", entry.getValue(), 256);
        }
    }
}
