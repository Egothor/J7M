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

package org.j7m.middleware.distributed;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class EventJournalEncodedSizeTest {
    private static void exact(EventJournal journal) { assertEquals(journal.snapshot().length, journal.encodedSize()); }
    @Test void sizeIncludesModifiedUtfPartitionsSubscriptionsFiltersAndEvictionAcrossCopyAndRestore() {
        var journal = new EventJournal(2, 262144); exact(journal);
        var beginning = journal.latest("分区🙂"); journal.subscribe("订阅🙂", "租户🙂", "写入", beginning, 1000, 0); exact(journal);
        var first = journal.append("分区🙂", 1, "租户🙂", "写入", new byte[16]); exact(journal);
        var page = journal.poll("订阅🙂", "租户🙂", 2, 1); assertEquals(first, page.through()); exact(journal);
        journal.ack("订阅🙂", "租户🙂", first, 1000, 1); exact(journal);
        EventJournal copy = journal.copy(); exact(copy); assertEquals(journal.encodedSize(), copy.encodedSize());
        copy.append("other", 1, "tenant", "WRITE", new byte[100]); exact(copy);
        copy.append("分区🙂", 2, "租户🙂", "删除", new byte[100]); exact(copy);
        copy.append("分区🙂", 2, "租户🙂", "写入", new byte[100]); exact(copy); exact(journal);
        var restored = EventJournal.restore(copy.snapshot()); exact(restored); assertEquals(copy.encodedSize(), restored.encodedSize());
        byte[] before = restored.snapshot();
        assertThrows(IllegalArgumentException.class, () -> restored.append("分区🙂", 1, "租户🙂", "写入", new byte[0]));
        assertArrayEquals(before, restored.snapshot()); exact(restored);
    }
    @Test void oversizedSingleEventEvictedByExistingByteRetentionStillCountsItsFloorMetadataExactly() {
        var journal = new EventJournal(1, 262144); journal.append("p", 1, "tenant", "WRITE", new byte[262145]);
        assertEquals(0, journal.retainedEvents()); assertEquals(0, journal.retainedBytes()); exact(journal);
        var restored = EventJournal.restore(journal.snapshot()); exact(restored);
        assertEquals(journal.encodedSize(), restored.encodedSize());
        byte[] before = restored.snapshot();
        assertThrows(IllegalArgumentException.class, () -> restored.append("p", 1, "tenant", "WRITE", new byte[1049601]));
        assertArrayEquals(before, restored.snapshot());
    }
}
