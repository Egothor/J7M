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

package org.j7m.middleware.adapter.tck;

import java.util.ArrayList;
import java.util.List;

import org.j7m.middleware.adapter.spi.AdapterCapability;
import org.j7m.middleware.adapter.spi.AdapterSemanticProfile;
import org.j7m.middleware.adapter.spi.AdapterTckProfile;
import org.j7m.middleware.adapter.spi.AdapterTckScenario;

/**
 * Factory for deterministic adapter TCK profiles.
 *
 * <p>The class is stateless and thread-safe. Profiles are defined entirely in
 * J7M terms and do not require external brokers, databases, cloud services,
 * workflow engines, transaction managers, or container runtimes.</p>
 */
public final class AdapterTckProfiles {
    private AdapterTckProfiles() {
    }

    /**
     * Returns all built-in Phase 6.9 TCK profiles.
     *
     * @return immutable profile list
     */
    public static List<AdapterTckProfile> all() {
        return List.of(
                durableLog(),
                messaging(),
                coordination(),
                consensus(),
                repositoryReplication(),
                saga(),
                isolation());
    }

    /**
     * Returns the durable log TCK profile.
     *
     * @return durable log profile
     */
    public static AdapterTckProfile durableLog() {
        return profile(AdapterSemanticProfile.durableLog(), "append/read, offsets, atomicity, corruption, recovery");
    }

    /**
     * Returns the messaging TCK profile.
     *
     * @return messaging profile
     */
    public static AdapterTckProfile messaging() {
        return profile(AdapterSemanticProfile.messaging(), "queue/outbox/inbox, visibility, retry, backpressure");
    }

    /**
     * Returns the coordination TCK profile.
     *
     * @return coordination profile
     */
    public static AdapterTckProfile coordination() {
        return profile(AdapterSemanticProfile.coordination(), "membership, leases, fencing, CAS, stale owner checks");
    }

    /**
     * Returns the consensus TCK profile.
     *
     * @return consensus profile
     */
    public static AdapterTckProfile consensus() {
        return profile(AdapterSemanticProfile.consensus(), "election, replication, majority safety, stale leader rejection");
    }

    /**
     * Returns the repository replication TCK profile.
     *
     * @return repository replication profile
     */
    public static AdapterTckProfile repositoryReplication() {
        return profile(AdapterSemanticProfile.repositoryReplication(), "artifact bytes, verification, index, promotion, repair");
    }

    /**
     * Returns the saga TCK profile.
     *
     * @return saga profile
     */
    public static AdapterTckProfile saga() {
        return profile(AdapterSemanticProfile.saga(), "definitions, ordered steps, compensation, outbox, recovery");
    }

    /**
     * Returns the isolation TCK profile.
     *
     * @return isolation profile
     */
    public static AdapterTckProfile isolation() {
        return profile(AdapterSemanticProfile.isolation(), "policy evaluation, output redaction, capability truthfulness");
    }

    private static AdapterTckProfile profile(final AdapterSemanticProfile semanticProfile, final String description) {
        final List<AdapterTckScenario> scenarios = new ArrayList<>();
        for (AdapterCapability capability : semanticProfile.requiredCapabilities()) {
            scenarios.add(new AdapterTckScenario(
                    semanticProfile.name() + "-" + capability.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                    semanticProfile,
                    capability,
                    true,
                    description + ": required " + capability.name()));
        }
        for (AdapterCapability capability : semanticProfile.optionalCapabilities()) {
            scenarios.add(new AdapterTckScenario(
                    semanticProfile.name() + "-" + capability.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                    semanticProfile,
                    capability,
                    false,
                    description + ": optional " + capability.name()));
        }
        return new AdapterTckProfile(semanticProfile.name() + "-tck", semanticProfile, List.copyOf(scenarios));
    }
}
