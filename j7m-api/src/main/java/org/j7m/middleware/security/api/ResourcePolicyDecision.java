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

package org.j7m.middleware.security.api;

import java.util.Map;

/**
 * Immutable decision returned by a resource policy.
 *
 * @param allowed whether the operation may proceed
 * @param overloaded whether denial should be reported as overload
 * @param policyId policy id
 * @param reason safe reason code
 * @param metadata safe public metadata
 */
public record ResourcePolicyDecision(
        boolean allowed,
        boolean overloaded,
        String policyId,
        String reason,
        Map<String, String> metadata) {
    /**
     * Creates immutable metadata.
     */
    public ResourcePolicyDecision {
        policyId = policyId == null || policyId.isBlank() ? "resource-policy" : policyId;
        reason = reason == null ? "" : reason;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Returns an allow decision.
     *
     * @param policyId policy id
     * @return allow decision
     */
    public static ResourcePolicyDecision allow(final String policyId) {
        return new ResourcePolicyDecision(true, false, policyId, "allowed", Map.of());
    }

    /**
     * Returns a permission denial.
     *
     * @param policyId policy id
     * @param reason safe reason
     * @return deny decision
     */
    public static ResourcePolicyDecision deny(final String policyId, final String reason) {
        return new ResourcePolicyDecision(false, false, policyId, reason, Map.of());
    }

    /**
     * Returns an overload denial.
     *
     * @param policyId policy id
     * @param reason safe reason
     * @return overload decision
     */
    public static ResourcePolicyDecision overload(final String policyId, final String reason) {
        return new ResourcePolicyDecision(false, true, policyId, reason, Map.of());
    }
}
