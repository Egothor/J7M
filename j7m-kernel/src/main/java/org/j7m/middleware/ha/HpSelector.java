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

package org.j7m.middleware.ha;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.registry.api.RegistryPeerState;
import org.j7m.middleware.registry.api.RegistryPeerStatus;

/**
 * Phase 2 high-performance candidate selector.
 *
 * <p>The selector is stateless and thread-safe. It scores candidates using
 * lease freshness, locality, peer health, latency, success rate, load, and
 * deterministic near-equal tie breaking.</p>
 */
public final class HpSelector {
    private static final double DEAD_PENALTY = 10_000.0D;
    private static final double EXPIRED_PENALTY = 5_000.0D;
    private static final double REMOTE_PENALTY = 25.0D;
    private static final double MAX_SCORE_COMPONENT = 1_000_000_000_000.0D;

    /**
     * Creates a selector with the default Phase 2 scoring weights.
     */
    public HpSelector() {
    }

    /**
     * Selects the best candidate.
     *
     * @param candidates candidate references
     * @param localRuntimeId local runtime id
     * @param peers registry peers
     * @param stats statistics view
     * @param clock scoring clock
     * @return selected candidate and scores
     */
    public HpSelection select(
            final List<ObjectRef> candidates,
            final String localRuntimeId,
            final List<RegistryPeerStatus> peers,
            final InvocationStatsView stats,
            final Clock clock) {
        if (candidates == null || candidates.isEmpty()) {
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "no service candidates");
        }
        long nowMillis = clock == null ? System.currentTimeMillis() : clock.millis();
        List<HpCandidateScore> scores = new ArrayList<>();
        for (ObjectRef ref : candidates) {
            if (this.eligible(ref, nowMillis) && this.peerEligible(ref, peers)) {
                scores.add(this.score(ref, localRuntimeId, peers, stats, nowMillis));
            }
        }
        if (scores.isEmpty()) {
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "no eligible service candidates");
        }
        scores.sort(Comparator.comparingDouble(HpCandidateScore::score)
                .thenComparing(score -> score.ref().runtimeId())
                .thenComparing(score -> score.ref().objectId()));
        return new HpSelection(scores.getFirst().ref(), scores);
    }

    private HpCandidateScore score(
            final ObjectRef ref,
            final String localRuntimeId,
            final List<RegistryPeerStatus> peers,
            final InvocationStatsView stats,
            final long nowMillis) {
        double score = 0.0D;
        Map<String, String> metadata = ref.metadata();
        if (!ref.isLeaseActive(nowMillis)) {
            score += EXPIRED_PENALTY;
        }
        if (!ref.runtimeId().equals(localRuntimeId)) {
            score += REMOTE_PENALTY;
        }
        Optional<RegistryPeerStatus> peer = peers == null ? Optional.empty() : peers.stream()
                .filter(status -> this.peerMatchesRef(status, ref))
                .findFirst();
        if (peer.isPresent()) {
            RegistryPeerStatus status = peer.get();
            if (status.state() == RegistryPeerState.DEAD || !status.alive()) {
                score += DEAD_PENALTY;
            }
            if (status.overloaded()) {
                score += 500.0D;
            }
            score += boundedNonNegative(status.loadScore()) * 100.0D;
            score += Math.max(0.0D, 1.0D - boundedRatio(status.reliabilityScore(), 1.0D)) * 1_000.0D;
            score += Math.min(1_000.0D, boundedNonNegative(status.lastLatencyNanos()) / 1_000_000.0D);
        }
        score += this.nonNegativeMetadata(metadata, "load") * 100.0D;
        score += this.nonNegativeMetadata(metadata, "latencyNanos") / 1_000_000.0D;
        score += this.nonNegativeMetadata(metadata, "benchmarkLatencyNanos") / 1_000_000.0D;
        if (Boolean.parseBoolean(metadata.getOrDefault("benchmarkFailed", "false"))) {
            score += 50.0D;
        }
        ObjectInvocationStats objectStats = stats == null ? null : stats.statsFor(ref.objectId());
        if (objectStats == null) {
            objectStats = new ObjectInvocationStats(0L, 0L, 0L, 0.0D);
        }
        score += boundedNonNegative(objectStats.loadScore()) * 100.0D;
        score += Math.max(0.0D, 1.0D - boundedRatio(objectStats.successRate(), 1.0D)) * 1_000.0D;
        score += Math.min(1_000.0D, boundedNonNegative(objectStats.averageLatencyNanos()) / 1_000_000.0D);
        return new HpCandidateScore(ref, score, Map.of(
                "runtimeId", ref.runtimeId(),
                "local", Boolean.toString(ref.runtimeId().equals(localRuntimeId)),
                "score", Double.toString(score)));
    }

    private boolean eligible(final ObjectRef ref, final long nowMillis) {
        return ref != null
                && ref.generation() > 0L
                && ref.isLeaseActive(nowMillis)
                && "trusted".equals(ref.metadata().get("trustStatus"))
                && this.metadataNumberValid(ref.metadata(), "load")
                && this.metadataNumberValid(ref.metadata(), "latencyNanos")
                && this.metadataNumberValid(ref.metadata(), "benchmarkLatencyNanos")
                && this.optionalPositiveLongMetadata(ref.metadata(), "fencingToken")
                && this.optionalPositiveLongMetadata(ref.metadata(), "topologyGeneration");
    }

    private boolean peerEligible(final ObjectRef ref, final List<RegistryPeerStatus> peers) {
        if (peers == null || peers.isEmpty()) {
            return true;
        }
        Optional<RegistryPeerStatus> peer = peers.stream()
                .filter(status -> this.peerMatchesRef(status, ref))
                .findFirst();
        return peer.isEmpty() || peer.get().alive() && peer.get().state() != RegistryPeerState.DEAD;
    }

    private boolean peerMatchesRef(final RegistryPeerStatus status, final ObjectRef ref) {
        if (status == null || ref == null) {
            return false;
        }
        if (ref.runtimeId().equals(status.peerId())) {
            return true;
        }
        String runtimeId = status.metadata().get("runtimeId");
        if (ref.runtimeId().equals(runtimeId)) {
            return true;
        }
        for (org.j7m.middleware.registry.api.RegistryEndpoint endpoint : status.endpoints()) {
            if (ref.endpoint().equals(endpoint.endpoint())) {
                return true;
            }
            if (ref.runtimeId().equals(endpoint.metadata().get("runtimeId"))) {
                return true;
            }
        }
        return false;
    }

    private boolean metadataNumberValid(final Map<String, String> metadata, final String key) {
        String value = metadata == null ? null : metadata.get(key);
        if (value == null || value.isBlank()) {
            return true;
        }
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) && parsed >= 0.0D && parsed <= MAX_SCORE_COMPONENT;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private double nonNegativeMetadata(final Map<String, String> metadata, final String key) {
        String value = metadata == null ? null : metadata.get(key);
        if (value == null || value.isBlank()) {
            return 0.0D;
        }
        double parsed = Double.parseDouble(value);
        return boundedNonNegative(parsed);
    }

    private boolean optionalPositiveLongMetadata(final Map<String, String> metadata, final String key) {
        String value = metadata == null ? null : metadata.get(key);
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return Long.parseLong(value) > 0L;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static double boundedNonNegative(final long value) {
        return value <= 0L ? 0.0D : Math.min(MAX_SCORE_COMPONENT, (double) value);
    }

    private static double boundedNonNegative(final double value) {
        return !Double.isFinite(value) || value <= 0.0D ? 0.0D : Math.min(MAX_SCORE_COMPONENT, value);
    }

    private static double boundedRatio(final double value, final double fallback) {
        return !Double.isFinite(value) || value < 0.0D || value > 1.0D ? fallback : value;
    }
}
