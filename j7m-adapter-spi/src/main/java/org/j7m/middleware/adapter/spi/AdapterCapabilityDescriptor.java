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
import java.util.Map;
import java.util.Set;

/**
 * Immutable capability declaration for an optional adapter provider.
 *
 * <p>The descriptor is thread-safe and safe to publish. It is an input to TCK
 * evaluation, not proof by itself. The TCK compares declared capabilities with
 * executed scenarios and rejects adapters that claim stronger semantics than
 * they prove. Descriptors must not contain credential values, raw endpoints, or
 * vendor-specific public API shapes.</p>
 *
 * @param adapterId stable adapter identifier
 * @param adapterVersion adapter implementation version
 * @param type J7M service family
 * @param supportedProfiles semantic profiles the adapter claims to support
 * @param capabilities declared capability levels
 * @param unsupportedSemantics explicitly unsupported semantics
 * @param securityProfile declared security behavior
 * @param resourceProfile safe resource profile summary
 * @param failureModes failure modes the adapter can surface safely
 * @param knownLimitations safe limitation descriptions
 * @param tckVersion TCK version used by the latest known report
 * @param compatibilityLevel latest declared compatibility level
 */
public record AdapterCapabilityDescriptor(
        AdapterProviderId adapterId,
        String adapterVersion,
        AdapterType type,
        Set<AdapterSemanticProfile> supportedProfiles,
        Map<AdapterCapability, AdapterCapabilityLevel> capabilities,
        Set<AdapterCapability> unsupportedSemantics,
        AdapterSecurityProfile securityProfile,
        AdapterResourceProfile resourceProfile,
        Set<AdapterFailureMode> failureModes,
        List<String> knownLimitations,
        String tckVersion,
        AdapterCompatibilityLevel compatibilityLevel) {
    /**
     * Creates an adapter capability descriptor.
     */
    public AdapterCapabilityDescriptor {
        adapterVersion = AdapterValues.requireText(adapterVersion, "adapter version");
        supportedProfiles = AdapterValues.immutableSet(supportedProfiles, "supported profiles");
        capabilities = AdapterValues.immutableMap(capabilities, "capabilities");
        unsupportedSemantics = AdapterValues.immutableEnumSet(unsupportedSemantics, "unsupported semantics");
        failureModes = AdapterValues.immutableEnumSet(failureModes, "failure modes");
        knownLimitations = AdapterValues.safeTextList(knownLimitations, "known limitations");
        tckVersion = AdapterValues.requireText(tckVersion, "tck version");
    }

    /**
     * Returns the declared level for a capability.
     *
     * @param capability capability to inspect
     * @return declared level, or {@link AdapterCapabilityLevel#UNSUPPORTED} when absent
     */
    public AdapterCapabilityLevel levelFor(final AdapterCapability capability) {
        return this.capabilities.getOrDefault(capability, AdapterCapabilityLevel.UNSUPPORTED);
    }

    /**
     * Returns whether the descriptor claims the capability must work.
     *
     * @param capability capability to inspect
     * @return {@code true} when the capability is required or claimed
     */
    public boolean claims(final AdapterCapability capability) {
        final AdapterCapabilityLevel level = levelFor(capability);
        return level == AdapterCapabilityLevel.REQUIRED || level == AdapterCapabilityLevel.CLAIMED;
    }

    /**
     * Returns whether all required semantics for a profile are declared as supported.
     *
     * @param profile semantic profile to check
     * @return {@code true} when every required capability is claimed or required
     */
    public boolean supportsRequired(final AdapterSemanticProfile profile) {
        for (AdapterCapability capability : profile.requiredCapabilities()) {
            if (!claims(capability)) {
                return false;
            }
        }
        return true;
    }
}
