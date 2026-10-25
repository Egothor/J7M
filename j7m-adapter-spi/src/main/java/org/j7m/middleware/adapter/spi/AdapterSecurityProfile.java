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

package org.j7m.middleware.adapter.spi;

import java.util.List;

/**
 * Security posture declared by an adapter.
 *
 * <p>The record is immutable and thread-safe. It captures whether the adapter
 * preserves J7M authorization hooks, audit hooks, redaction, object reference
 * semantics, and digest/reference non-bearer behavior. Missing required
 * security behavior causes the TCK to reject the adapter instead of silently
 * weakening native J7M semantics.</p>
 *
 * @param authorizationHooks whether adapter operations call authorization hooks
 * @param auditHooks whether adapter operations emit audit evidence
 * @param redaction whether public errors, status, and reports are redacted
 * @param objectReferenceProtection whether object references remain authorization checked
 * @param digestNotBearerToken whether digests and references are not treated as bearer authorization
 * @param credentials immutable credential declarations; values are never stored here
 */
public record AdapterSecurityProfile(
        boolean authorizationHooks,
        boolean auditHooks,
        boolean redaction,
        boolean objectReferenceProtection,
        boolean digestNotBearerToken,
        List<AdapterCredentialRequirement> credentials) {
    /**
     * Creates a security profile.
     */
    public AdapterSecurityProfile {
        credentials = AdapterValues.immutableList(credentials, "credentials");
    }

    /**
     * Returns a profile that preserves the baseline J7M security hooks.
     *
     * @return strict security profile
     */
    public static AdapterSecurityProfile strict() {
        return new AdapterSecurityProfile(true, true, true, true, true, List.of());
    }
}
