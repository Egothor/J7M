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

package org.j7m.middleware.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.j7m.middleware.api.reliability.DedupeEntry;
import org.j7m.middleware.api.reliability.DedupeKey;
import org.j7m.middleware.api.reliability.DedupeResult;
import org.j7m.middleware.api.reliability.DuplicateDetectionPolicy;
import org.j7m.middleware.api.reliability.ReliabilityStatus;
import org.j7m.middleware.reliability.InMemoryDedupeStore;
import org.junit.jupiter.api.Test;

public final class DedupeKeyTest {
    @Test
    public void validDedupeKeyIsAccepted() {
        DedupeKey key = new DedupeKey("payment:tenant-a:42");

        assertEquals("payment:tenant-a:42", key.value());
    }

    @Test
    public void inMemoryStoreRecordsFirstSeen() {
        InMemoryDedupeStore store = new InMemoryDedupeStore();
        DedupeResult result = store.checkAndRecord(entry("a"), DuplicateDetectionPolicy.REJECT_DUPLICATE);

        assertTrue(result.accepted());
        assertFalse(result.duplicate());
        assertEquals(1L, store.status().firstSeenCount());
        assertTrue(store.status().nonDurable());
    }

    @Test
    public void duplicateCanBeRejected() {
        InMemoryDedupeStore store = new InMemoryDedupeStore();
        store.checkAndRecord(entry("a"), DuplicateDetectionPolicy.REJECT_DUPLICATE);

        DedupeResult result = store.checkAndRecord(entry("a"), DuplicateDetectionPolicy.REJECT_DUPLICATE);

        assertFalse(result.accepted());
        assertTrue(result.duplicate());
        assertEquals(ReliabilityStatus.DUPLICATE_REJECTED, result.status());
    }

    @Test
    public void duplicateCanBeMarkedAndAllowed() {
        InMemoryDedupeStore store = new InMemoryDedupeStore();
        store.checkAndRecord(entry("b"), DuplicateDetectionPolicy.ALLOW_DUPLICATE_MARKED);

        DedupeResult result = store.checkAndRecord(entry("b"), DuplicateDetectionPolicy.ALLOW_DUPLICATE_MARKED);

        assertTrue(result.accepted());
        assertTrue(result.duplicate());
        assertEquals(ReliabilityStatus.DUPLICATE_MARKED, result.status());
    }

    @Test
    public void previousResultMetadataCanBeReturned() {
        InMemoryDedupeStore store = new InMemoryDedupeStore();
        store.checkAndRecord(new DedupeEntry(new DedupeKey("c"), null, 1L, Map.of("result", "ok")),
                DuplicateDetectionPolicy.RETURN_PREVIOUS_RESULT);

        DedupeResult result = store.checkAndRecord(entry("c"), DuplicateDetectionPolicy.RETURN_PREVIOUS_RESULT);

        assertTrue(result.accepted());
        assertEquals(ReliabilityStatus.PREVIOUS_RESULT_AVAILABLE, result.status());
        assertEquals("ok", result.metadata().get("result"));
    }

    private static DedupeEntry entry(final String value) {
        return new DedupeEntry(new DedupeKey(value), null, 1L, Map.of());
    }
}
