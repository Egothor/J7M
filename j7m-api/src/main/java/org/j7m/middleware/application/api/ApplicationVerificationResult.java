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

package org.j7m.middleware.application.api;

import java.util.List;

/**
 * Structured verification result for manifests and bundles.
 *
 * <p>Public parser and verifier APIs return this type rather than leaking raw
 * JSON, ZIP, checksum, or signature exceptions.</p>
 *
 * @param accepted whether verification succeeded
 * @param strictMode whether strict verification was requested
 * @param coordinate verified coordinate when available
 * @param messages safe findings
 */
public record ApplicationVerificationResult(
        boolean accepted,
        boolean strictMode,
        ApplicationCoordinate coordinate,
        List<String> messages) {
    /**
     * Creates immutable verification data.
     */
    public ApplicationVerificationResult {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /**
     * Creates a successful verification result.
     *
     * @param coordinate verified coordinate
     * @param strictMode whether strict verification was used
     * @param messages safe informational messages
     * @return successful result
     */
    public static ApplicationVerificationResult accepted(final ApplicationCoordinate coordinate, final boolean strictMode, final List<String> messages) {
        return new ApplicationVerificationResult(true, strictMode, coordinate, messages);
    }

    /**
     * Creates a rejected verification result.
     *
     * @param coordinate rejected coordinate when known
     * @param strictMode whether strict verification was used
     * @param messages safe rejection messages
     * @return rejected result
     */
    public static ApplicationVerificationResult rejected(final ApplicationCoordinate coordinate, final boolean strictMode, final List<String> messages) {
        return new ApplicationVerificationResult(false, strictMode, coordinate, messages);
    }
}
