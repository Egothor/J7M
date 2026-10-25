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

import java.util.ArrayList;
import java.util.List;

/**
 * Compatibility matrix evaluator for adapter descriptors and semantic profiles.
 *
 * <p>The class is immutable and thread-safe because it has no mutable state.
 * It evaluates only declared and tested J7M semantics; it never grants native
 * equivalence for vendor-specific claims or unsupported required behavior.</p>
 */
public final class AdapterSemanticMatrix {
    /**
     * Creates a semantic matrix evaluator.
     */
    public AdapterSemanticMatrix() {
    }

    /**
     * Calculates the descriptor compatibility level for a semantic profile.
     *
     * @param descriptor adapter capability descriptor
     * @param profile profile under evaluation
     * @return compatibility level based on declared required and optional capabilities
     */
    public AdapterCompatibilityLevel evaluate(
            final AdapterCapabilityDescriptor descriptor,
            final AdapterSemanticProfile profile) {
        boolean optionalMissing = false;
        for (AdapterCapability capability : profile.requiredCapabilities()) {
            if (!descriptor.claims(capability)) {
                return AdapterCompatibilityLevel.REJECTED;
            }
        }
        for (AdapterCapability capability : profile.optionalCapabilities()) {
            if (descriptor.levelFor(capability) == AdapterCapabilityLevel.UNSUPPORTED) {
                optionalMissing = true;
            }
        }
        return optionalMissing
                ? AdapterCompatibilityLevel.COMPATIBLE_WITH_LIMITATIONS
                : AdapterCompatibilityLevel.NATIVE_EQUIVALENT;
    }

    /**
     * Lists safe limitations for missing profile capabilities.
     *
     * @param descriptor adapter capability descriptor
     * @param profile profile under evaluation
     * @return immutable safe limitation descriptions
     */
    public List<String> limitationsFor(
            final AdapterCapabilityDescriptor descriptor,
            final AdapterSemanticProfile profile) {
        final List<String> limitations = new ArrayList<>();
        for (AdapterCapability capability : profile.requiredCapabilities()) {
            if (!descriptor.claims(capability)) {
                limitations.add("required semantic unsupported: " + capability.name());
            }
        }
        for (AdapterCapability capability : profile.optionalCapabilities()) {
            if (descriptor.levelFor(capability) == AdapterCapabilityLevel.UNSUPPORTED) {
                limitations.add("optional semantic unsupported: " + capability.name());
            }
        }
        limitations.addAll(descriptor.knownLimitations());
        return List.copyOf(limitations);
    }
}
