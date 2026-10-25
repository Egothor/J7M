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

package org.j7m.middleware.reliability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.api.reliability.DedupeEntry;
import org.j7m.middleware.api.reliability.DedupeResult;
import org.j7m.middleware.api.reliability.DedupeStore;
import org.j7m.middleware.api.reliability.DedupeStoreStatus;
import org.j7m.middleware.api.reliability.DuplicateDetectionPolicy;
import org.j7m.middleware.api.reliability.ReliabilityStatus;

/**
 * Non-durable in-memory duplicate detection store.
 *
 * <p>The store is thread-safe and intended only for semantics tests and
 * non-durable development use. It is not crash-safe, not replicated, not a
 * queue, not an outbox/inbox, and not acceptable as Phase 6.2 or Phase 6.3
 * durable reliability work.</p>
 */
public final class InMemoryDedupeStore implements DedupeStore {
    private final ConcurrentMap<String, DedupeEntry> entries;
    private final AtomicLong firstSeenCount;
    private final AtomicLong duplicateCount;
    private final AtomicLong rejectedCount;
    private final AtomicLong previousResultCount;

    /**
     * Creates an empty non-durable store.
     */
    public InMemoryDedupeStore() {
        this.entries = new ConcurrentHashMap<>();
        this.firstSeenCount = new AtomicLong();
        this.duplicateCount = new AtomicLong();
        this.rejectedCount = new AtomicLong();
        this.previousResultCount = new AtomicLong();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DedupeResult checkAndRecord(final DedupeEntry entry, final DuplicateDetectionPolicy policy) {
        if (entry == null) {
            throw new IllegalArgumentException("dedupe entry must not be null");
        }
        DuplicateDetectionPolicy safePolicy = policy == null ? DuplicateDetectionPolicy.DISABLED : policy;
        if (safePolicy == DuplicateDetectionPolicy.DISABLED) {
            this.firstSeenCount.incrementAndGet();
            return DedupeResult.firstSeen();
        }
        DedupeEntry previous = this.entries.putIfAbsent(entry.key().value(), entry);
        if (previous == null) {
            this.firstSeenCount.incrementAndGet();
            return DedupeResult.firstSeen();
        }
        this.duplicateCount.incrementAndGet();
        if (safePolicy == DuplicateDetectionPolicy.REJECT_DUPLICATE) {
            this.rejectedCount.incrementAndGet();
            return new DedupeResult(ReliabilityStatus.DUPLICATE_REJECTED, false, true, previous, Map.of("decision", "rejected"));
        }
        if (safePolicy == DuplicateDetectionPolicy.RETURN_PREVIOUS_RESULT) {
            this.previousResultCount.incrementAndGet();
            return new DedupeResult(ReliabilityStatus.PREVIOUS_RESULT_AVAILABLE, true, true, previous, previous.resultMetadata());
        }
        return new DedupeResult(ReliabilityStatus.DUPLICATE_MARKED, true, true, previous, Map.of("decision", "duplicate-marked"));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DedupeStoreStatus status() {
        return new DedupeStoreStatus(this.entries.size(), this.firstSeenCount.get(), this.duplicateCount.get(),
                this.rejectedCount.get(), this.previousResultCount.get(), true,
                Map.of("durability", "non-durable", "purpose", "phase-6.1-semantics-tests"));
    }
}
