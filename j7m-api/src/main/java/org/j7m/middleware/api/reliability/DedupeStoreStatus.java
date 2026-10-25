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

package org.j7m.middleware.api.reliability;

import java.io.Serializable;
import java.util.Map;

/**
 * Read-only status for a dedupe store.
 *
 * <p>The record is immutable, serializable, and thread-safe.</p>
 *
 * @param entries current entry count
 * @param firstSeenCount first-seen decisions
 * @param duplicateCount duplicate observations
 * @param rejectedCount rejected duplicate decisions
 * @param previousResultCount previous-result decisions
 * @param nonDurable whether the store is non-durable
 * @param metadata safe implementation metadata
 */
public record DedupeStoreStatus(
        long entries,
        long firstSeenCount,
        long duplicateCount,
        long rejectedCount,
        long previousResultCount,
        boolean nonDurable,
        Map<String, String> metadata) implements Serializable {

    /**
     * Creates a validated store status.
     */
    public DedupeStoreStatus {
        if (entries < 0L || firstSeenCount < 0L || duplicateCount < 0L || rejectedCount < 0L || previousResultCount < 0L) {
            throw new IllegalArgumentException("dedupe store counters must be non-negative");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
