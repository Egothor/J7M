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

package org.j7m.middleware.api;

import java.time.Duration;
import java.util.Map;

/**
 * Immutable options for registry-backed lookup and list operations.
 *
 * @param registryContext registry context to query
 * @param requiredParams required metadata key/value pairs
 * @param deadline operation deadline duration
 * @param preferLocal whether local instances should be preferred
 * @param highAvailability whether lookup should return an HA proxy where possible
 */
public record LookupOptions(
        String registryContext,
        Map<String, String> requiredParams,
        Duration deadline,
        boolean preferLocal,
        boolean highAvailability) {

    /**
     * Creates immutable lookup options.
     */
    public LookupOptions {
        registryContext = registryContext == null || registryContext.isBlank() ? "default" : registryContext;
        requiredParams = requiredParams == null ? Map.of() : Map.copyOf(requiredParams);
        deadline = deadline == null ? Duration.ofSeconds(5L) : deadline;
    }

    /**
     * Returns default lookup options.
     *
     * @return default options
     */
    public static LookupOptions defaults() {
        return new LookupOptions("default", Map.of(), Duration.ofSeconds(5L), true, false);
    }

    /**
     * Returns default HA lookup options.
     *
     * @return HA lookup options
     */
    public static LookupOptions highAvailabilityOptions() {
        return new LookupOptions("default", Map.of(), Duration.ofSeconds(5L), true, true);
    }
}
