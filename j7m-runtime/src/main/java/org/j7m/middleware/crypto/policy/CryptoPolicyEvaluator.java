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

package org.j7m.middleware.crypto.policy;

import java.security.Security;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates the current JVM cryptographic posture against a configured policy.
 */
public final class CryptoPolicyEvaluator {
    /**
     * Creates a policy evaluator.
     */
    public CryptoPolicyEvaluator() {
    }

    /**
     * Builds a safe posture report.
     *
     * @param policy policy
     * @param provider key provider
     * @return posture report
     */
    public CryptoPostureReport report(final CryptoPolicy policy, final KeyManagementProvider provider) {
        CryptoPolicy safePolicy = policy == null
                ? new CryptoPolicy(null, null, null)
                : policy;
        List<String> limitations = new ArrayList<>();
        boolean fipsRequired = safePolicy.fipsModePolicy().required();
        boolean fipsSatisfied = !fipsRequired || containsProvider(safePolicy.fipsModePolicy().providerName());
        if (!fipsSatisfied) {
            limitations.add("Required FIPS provider is not active in the current JVM.");
        }
        if (safePolicy.providerPolicy().strict() && safePolicy.providerPolicy().allowedAlgorithms().isEmpty()) {
            limitations.add("Strict crypto policy has no allowed algorithms configured.");
        }
        String keyStatus = provider == null ? "no KMS/HSM provider configured" : provider.id() + ":" + provider.status();
        if (provider == null) {
            limitations.add("External KMS/HSM provider is not configured.");
        }
        return new CryptoPostureReport(Instant.now(), safePolicy.providerPolicy().strict(), fipsRequired, fipsSatisfied,
                Security.getProviders().length + " JVM providers available", keyStatus, limitations);
    }

    /**
     * Validates whether an algorithm/provider pair is allowed.
     *
     * @param policy policy
     * @param algorithm algorithm
     * @param provider provider
     */
    public void validate(final CryptoPolicy policy, final String algorithm, final String provider) {
        CryptoPolicy safePolicy = policy == null ? new CryptoPolicy(null, null, null) : policy;
        if (!safePolicy.providerPolicy().strict()) {
            return;
        }
        boolean algorithmAllowed = safePolicy.providerPolicy().allowedAlgorithms().contains(algorithm);
        boolean providerAllowed = safePolicy.providerPolicy().allowedProviders().isEmpty()
                || safePolicy.providerPolicy().allowedProviders().contains(provider);
        if (!algorithmAllowed || !providerAllowed) {
            throw new IllegalArgumentException("crypto algorithm or provider is not allowed by strict policy");
        }
    }

    private static boolean containsProvider(final String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return Security.getProvider(name) != null;
    }
}
