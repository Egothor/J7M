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

import java.util.Set;

/**
 * Set of required and optional J7M semantics for one adapter service family.
 *
 * <p>The record is immutable and thread-safe. Required capabilities must pass
 * TCK scenarios for the adapter to be accepted for the profile. Optional
 * capabilities may be unsupported, but limitations must be recorded
 * truthfully.</p>
 *
 * @param name stable profile name
 * @param type J7M service family
 * @param requiredCapabilities immutable required capability set
 * @param optionalCapabilities immutable optional capability set
 */
public record AdapterSemanticProfile(
        String name,
        AdapterType type,
        Set<AdapterCapability> requiredCapabilities,
        Set<AdapterCapability> optionalCapabilities) {
    /**
     * Creates a semantic profile.
     *
     * @throws IllegalArgumentException if profile name is blank or a capability is both required and optional
     */
    public AdapterSemanticProfile {
        name = AdapterValues.requireId(name, "semantic profile name");
        requiredCapabilities = AdapterValues.immutableEnumSet(requiredCapabilities, "required capabilities");
        optionalCapabilities = AdapterValues.immutableEnumSet(optionalCapabilities, "optional capabilities");
        for (AdapterCapability capability : requiredCapabilities) {
            if (optionalCapabilities.contains(capability)) {
                throw new IllegalArgumentException("capability cannot be both required and optional: " + capability);
            }
        }
    }

    /**
     * Returns the durable log semantic profile.
     *
     * @return durable log profile
     */
    public static AdapterSemanticProfile durableLog() {
        return new AdapterSemanticProfile(
                "durable-log",
                AdapterType.DURABLE_LOG,
                Set.of(
                        AdapterCapability.LOG_APPEND_READ,
                        AdapterCapability.LOG_MONOTONIC_OFFSETS,
                        AdapterCapability.LOG_MULTI_RECORD_ATOMICITY,
                        AdapterCapability.LOG_CORRUPTION_DETECTION,
                        AdapterCapability.LOG_RECOVERY_REPLAY,
                        AdapterCapability.REDACTION,
                        AdapterCapability.NORMALIZED_ERRORS),
                Set.of(AdapterCapability.BACKPRESSURE));
    }

    /**
     * Returns the durable queue, outbox, and inbox semantic profile.
     *
     * @return messaging profile
     */
    public static AdapterSemanticProfile messaging() {
        return new AdapterSemanticProfile(
                "messaging",
                AdapterType.MESSAGING,
                Set.of(
                        AdapterCapability.QUEUE_LEASE_ACK,
                        AdapterCapability.QUEUE_VISIBILITY_TIMEOUT,
                        AdapterCapability.RETRY_DEAD_LETTER,
                        AdapterCapability.IDEMPOTENCY_DEDUPE,
                        AdapterCapability.OUTBOX_CRASH_WINDOW,
                        AdapterCapability.INBOX_DUPLICATE_SUPPRESSION,
                        AdapterCapability.BACKPRESSURE,
                        AdapterCapability.REDACTION,
                        AdapterCapability.NORMALIZED_ERRORS),
                Set.of(AdapterCapability.DELAYED_DELIVERY, AdapterCapability.PRIORITY_ORDERING));
    }

    /**
     * Returns the coordination semantic profile.
     *
     * @return coordination profile
     */
    public static AdapterSemanticProfile coordination() {
        return new AdapterSemanticProfile(
                "coordination",
                AdapterType.COORDINATION,
                Set.of(
                        AdapterCapability.MEMBERSHIP_HEARTBEAT,
                        AdapterCapability.LEASES_FENCING,
                        AdapterCapability.COMPARE_AND_SET,
                        AdapterCapability.STALE_OWNER_REJECTION,
                        AdapterCapability.REDACTION,
                        AdapterCapability.NORMALIZED_ERRORS),
                Set.of(AdapterCapability.WATCH_NOTIFY));
    }

    /**
     * Returns the consensus semantic profile.
     *
     * @return consensus profile
     */
    public static AdapterSemanticProfile consensus() {
        return new AdapterSemanticProfile(
                "consensus",
                AdapterType.CONSENSUS,
                Set.of(
                        AdapterCapability.LEADER_ELECTION,
                        AdapterCapability.CONSENSUS_LOG_REPLICATION,
                        AdapterCapability.MAJORITY_SAFETY,
                        AdapterCapability.STALE_LEADER_REJECTION,
                        AdapterCapability.SAFETY_REPORT,
                        AdapterCapability.REDACTION,
                        AdapterCapability.NORMALIZED_ERRORS),
                Set.of());
    }

    /**
     * Returns the repository replication semantic profile.
     *
     * @return repository replication profile
     */
    public static AdapterSemanticProfile repositoryReplication() {
        return new AdapterSemanticProfile(
                "repository-replication",
                AdapterType.REPOSITORY_REPLICATION,
                Set.of(
                        AdapterCapability.ARTIFACT_DIGEST_PRESERVATION,
                        AdapterCapability.EXACT_BYTE_PRESERVATION,
                        AdapterCapability.SIGNATURE_SBOM_PROVENANCE,
                        AdapterCapability.INDEX_SNAPSHOT_DELTA,
                        AdapterCapability.PROMOTION_ROLLBACK_GATES,
                        AdapterCapability.CONFLICT_DETECTION,
                        AdapterCapability.REPAIR_RECONCILIATION,
                        AdapterCapability.REDACTION,
                        AdapterCapability.NORMALIZED_ERRORS),
                Set.of());
    }

    /**
     * Returns the saga semantic profile.
     *
     * @return saga profile
     */
    public static AdapterSemanticProfile saga() {
        return new AdapterSemanticProfile(
                "saga",
                AdapterType.SAGA,
                Set.of(
                        AdapterCapability.SAGA_DEFINITION,
                        AdapterCapability.ORDERED_STEPS,
                        AdapterCapability.REVERSE_COMPENSATION,
                        AdapterCapability.SAGA_OUTBOX_INTEGRATION,
                        AdapterCapability.IDEMPOTENCY_DEDUPE,
                        AdapterCapability.TIMEOUT_CANCELLATION,
                        AdapterCapability.RETRY_POLICY,
                        AdapterCapability.RETRY_DEAD_LETTER,
                        AdapterCapability.REDACTION,
                        AdapterCapability.NORMALIZED_ERRORS),
                Set.of());
    }

    /**
     * Returns the isolation semantic profile.
     *
     * @return isolation profile
     */
    public static AdapterSemanticProfile isolation() {
        return new AdapterSemanticProfile(
                "isolation",
                AdapterType.ISOLATION,
                Set.of(
                        AdapterCapability.ISOLATION_CAPABILITY_DECLARATION,
                        AdapterCapability.FILESYSTEM_POLICY,
                        AdapterCapability.NETWORK_POLICY,
                        AdapterCapability.ENVIRONMENT_POLICY,
                        AdapterCapability.OUTPUT_REDACTION,
                        AdapterCapability.REDACTION,
                        AdapterCapability.NORMALIZED_ERRORS),
                Set.of(AdapterCapability.PROCESS_LIFECYCLE));
    }
}
