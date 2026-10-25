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

import java.util.Map;

/**
 * Safe public configuration summary for nugget administration.
 *
 * <p>The summary is immutable and contains source classifications only. It
 * never contains PINs, verifier bytes, hashes, salts, KDF output, file
 * contents, environment values, or reusable authentication material.</p>
 *
 * @param adminState administration state
 * @param adminEnabled configured administration enablement
 * @param unsafeDeveloperMode whether unsafe developer mode is active
 * @param pinSourceType configured source classification
 * @param lowerSafetyPinSource whether the configured source is lower safety
 * @param verifierFormat safe verifier format version, or empty string
 * @param metadata redacted safe metadata
 */
public record NuggetConfigurationSummary(
        NuggetAdminState adminState,
        boolean adminEnabled,
        boolean unsafeDeveloperMode,
        NuggetPinSourceType pinSourceType,
        boolean lowerSafetyPinSource,
        String verifierFormat,
        Map<String, String> metadata) {
    /**
     * Creates a defensive immutable summary.
     */
    public NuggetConfigurationSummary {
        adminState = adminState == null ? NuggetAdminState.DISABLED : adminState;
        pinSourceType = pinSourceType == null ? NuggetPinSourceType.NONE : pinSourceType;
        verifierFormat = verifierFormat == null ? "" : verifierFormat;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
