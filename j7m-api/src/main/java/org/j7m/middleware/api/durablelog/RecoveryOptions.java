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

package org.j7m.middleware.api.durablelog;

import java.io.Serializable;

/**
 * Defines the bounded recovery policy used when a durable log scans existing
 * segment storage and rebuilds derived in-memory state.
 *
 * <p>Recovery bounds are hard safety controls, not tuning hints. A recovery
 * implementation must stop and fail closed when it cannot complete directory
 * enumeration, segment scanning, record decoding, hash verification, or derived
 * index/tail reconstruction within these limits. Budget exhaustion is a
 * recovery failure; it must not be normalized silently, and it must not cause
 * destructive tail truncation even when {@link LogCorruptionAction#TRUNCATE_TAIL}
 * is selected for genuine corrupt or incomplete tail frames.</p>
 *
 * <p>The segment bound limits recovery discovery work. Implementations may apply
 * it to directory entries examined while finding segment files, so unrelated
 * files in the log directory can consume the budget. The record bound limits the
 * number of durable records reserved for replay and for rebuilding derived
 * offsets, stream indexes, hash tails, checkpoint-derived service state, and
 * similar recovery state. {@code RecoveryOptions} does not expose a separate
 * byte budget. Byte exposure remains bounded by durable-log configuration such
 * as maximum segment size, maximum payload size, maximum records per append, the
 * segment discovery bound, and the record scan bound.</p>
 *
 * <p>Services that recover from checkpoints, including messaging and
 * coordination services, may combine these limits with service-specific
 * checkpoint lookup and checkpoint-tail replay budgets. Histories created before
 * suitable recovery state or checkpoints exist can fail closed when replay would
 * exceed the configured bounds; operators must migrate or checkpoint such
 * histories through a bounded path before they can be accepted by a bounded
 * recovery policy.</p>
 *
 * <p>Instances are immutable, serializable value objects and are safe to share
 * between threads. They own no files, buffers, channels, or background work.</p>
 *
 * @param corruptionAction action returned by {@link #corruptionAction()} for
 *        corrupt or incomplete frames; {@code null} is normalized to
 *        {@link LogCorruptionAction#FAIL}
 * @param rebuildIndexes value returned by {@link #rebuildIndexes()} indicating
 *        whether recovery may rebuild missing derived indexes from durable
 *        records
 * @param maxSegmentsScanned positive value returned by
 *        {@link #maxSegmentsScanned()} for the maximum number of
 *        segment-discovery directory entries or segment files that one recovery
 *        pass may examine, in entries
 * @param maxRecordsScanned positive value returned by
 *        {@link #maxRecordsScanned()} for the maximum number of durable records
 *        that one recovery pass may reserve for replay, in records
 */
public record RecoveryOptions(
        LogCorruptionAction corruptionAction,
        boolean rebuildIndexes,
        int maxSegmentsScanned,
        long maxRecordsScanned) implements Serializable {
    /**
     * Default maximum segment-discovery entries examined by one recovery pass.
     *
     * <p>The value is expressed in directory entries or segment files,
     * depending on the implementation's discovery strategy.</p>
     */
    public static final int DEFAULT_MAX_SEGMENTS_SCANNED = 4096;
    /**
     * Default maximum durable records reserved by one recovery pass.
     *
     * <p>The value is expressed in durable records, not bytes.</p>
     */
    public static final long DEFAULT_MAX_RECORDS_SCANNED = 1_000_000L;

    /**
     * Creates recovery options.
     *
     * <p>The canonical constructor validates recovery bounds and normalizes a
     * {@code null} corruption action to {@link LogCorruptionAction#FAIL}.
     * Both scan bounds are inclusive upper limits for one recovery pass and
     * must be at least one.</p>
     *
     * @param corruptionAction action for corrupt or incomplete frames;
     *        {@code null} is normalized to {@link LogCorruptionAction#FAIL}
     * @param rebuildIndexes whether recovery may rebuild missing derived indexes
     *        from durable records
     * @param maxSegmentsScanned positive maximum number of segment-discovery
     *        directory entries or segment files that one recovery pass may
     *        examine, in entries
     * @param maxRecordsScanned positive maximum number of durable records that
     *        one recovery pass may reserve for replay, in records
     * @throws IllegalArgumentException if {@code maxSegmentsScanned < 1} or
     *         {@code maxRecordsScanned < 1}
     */
    public RecoveryOptions {
        corruptionAction = corruptionAction == null ? LogCorruptionAction.FAIL : corruptionAction;
        if (maxSegmentsScanned < 1 || maxRecordsScanned < 1L) {
            throw new IllegalArgumentException("recovery scan bounds must be positive");
        }
    }

    /**
     * Creates recovery options with the default bounded scan policy.
     *
     * <p>The created options use {@link #DEFAULT_MAX_SEGMENTS_SCANNED} and
     * {@link #DEFAULT_MAX_RECORDS_SCANNED}. A {@code null} corruption action is
     * normalized to {@link LogCorruptionAction#FAIL}.</p>
     *
     * @param corruptionAction action for corrupt or incomplete frames
     * @param rebuildIndexes whether recovery may rebuild missing derived indexes
     *        from durable records
     * @throws IllegalArgumentException if a default bound is not positive
     */
    public RecoveryOptions(final LogCorruptionAction corruptionAction, final boolean rebuildIndexes) {
        this(corruptionAction, rebuildIndexes, DEFAULT_MAX_SEGMENTS_SCANNED, DEFAULT_MAX_RECORDS_SCANNED);
    }

    /**
     * Returns safe default recovery options.
     *
     * <p>The default policy fails recovery on corruption or incomplete frames,
     * permits derived-index rebuild, and uses the default positive segment and
     * record scan limits.</p>
     *
     * @return immutable fail-closed recovery options using default bounds
     */
    public static RecoveryOptions defaults() {
        return new RecoveryOptions(LogCorruptionAction.FAIL, true);
    }

    /**
     * Returns tail-truncating recovery options for crash recovery.
     *
     * <p>The returned policy may truncate a genuinely corrupt or incomplete
     * tail according to durable-log recovery rules. Scan-budget exhaustion is
     * not tail corruption and must still fail closed without destructive
     * truncation.</p>
     *
     * @return immutable tail-truncating recovery options using default bounds
     */
    public static RecoveryOptions truncateTail() {
        return new RecoveryOptions(LogCorruptionAction.TRUNCATE_TAIL, true);
    }

    /**
     * Returns recovery options with explicit scan bounds.
     *
     * <p>Use this factory when a caller needs a service-specific recovery
     * budget, for example a checkpoint lookup budget plus a bounded tail replay
     * budget. The segment and record limits are related but independent:
     * segment discovery bounds how much storage is enumerated, while the record
     * limit bounds how much durable history is replayed from the discovered
     * segments.</p>
     *
     * @param corruptionAction action for corrupt or incomplete frames;
     *        {@code null} is normalized to {@link LogCorruptionAction#FAIL}
     * @param rebuildIndexes whether recovery may rebuild missing derived indexes
     *        from durable records
     * @param maxSegmentsScanned positive maximum number of segment-discovery
     *        directory entries or segment files that one recovery pass may
     *        examine, in entries
     * @param maxRecordsScanned positive maximum number of durable records that
     *        one recovery pass may reserve for replay, in records
     * @return immutable bounded recovery options
     * @throws IllegalArgumentException if {@code maxSegmentsScanned < 1} or
     *         {@code maxRecordsScanned < 1}
     */
    public static RecoveryOptions bounded(
            final LogCorruptionAction corruptionAction,
            final boolean rebuildIndexes,
            final int maxSegmentsScanned,
            final long maxRecordsScanned) {
        return new RecoveryOptions(corruptionAction, rebuildIndexes, maxSegmentsScanned, maxRecordsScanned);
    }
}
