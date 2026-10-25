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
import java.util.*;
import org.junit.jupiter.api.Test;

class EventJournalTest {
    @Test void deliveryAckResumeSnapshotsAndScopeFilteringPreserveExactIdentities() {
        var journal = new EventJournal(10, 262144);
        var beginning = journal.latest("p");
        journal.subscribe("sub", "a", "WRITE", beginning, 1000, 0);
        byte[] payload = {1}; var first = journal.append("p", 1, "a", "WRITE", payload); payload[0] = 8;
        journal.append("p", 1, "b", "WRITE", new byte[]{2});
        var last = journal.append("p", 2, "a", "DELETE", new byte[]{3});
        var page = journal.poll("sub", "a", 10, 10);
        assertEquals(List.of(first), page.events().stream().map(EventJournal.Event::cursor).toList());
        assertEquals(last, page.through()); assertEquals(1, page.events().getFirst().payload()[0]);
        assertEquals(page.events().getFirst().cursor(), journal.poll("sub", "a", 10, 10).events().getFirst().cursor());
        var restored = EventJournal.restore(journal.snapshot());
        assertEquals(first, restored.poll("sub", "a", 10, 10).events().getFirst().cursor());
        restored.ack("sub", "a", page.through(), 1000, 10);
        assertTrue(restored.poll("sub", "a", 10, 10).events().isEmpty());
        assertEquals(last, restored.resume("sub", "a", 1000, 20));
        assertThrows(IllegalArgumentException.class, () -> restored.ack("sub", "a", first, 1000, 30));
        assertThrows(IllegalStateException.class, () -> restored.poll("sub", "b", 10, 10));
        assertThrows(IllegalStateException.class, () -> restored.poll("sub", "a", 10, 1020));
        assertThrows(IllegalArgumentException.class, () -> journal.append("p", 1, "a", "WRITE", new byte[0]));
    }
    @Test void boundedSlowConsumerGetsExplicitExpiredCursorAndFreshSnapshotIsRequired() {
        var journal = new EventJournal(2, 262144); var start = journal.latest("p");
        journal.subscribe("slow", "scope", "", start, 10000, 0);
        for (int i = 0; i < 100000; i++) journal.append("p", 1, "scope", "WRITE", new byte[1]);
        assertEquals(2, journal.retainedEvents()); assertEquals(2, journal.retainedBytes()); assertEquals(1, journal.subscriptions());
        assertEquals("CURSOR_EXPIRED", assertThrows(IllegalStateException.class, () -> journal.poll("slow", "scope", 2, 1)).getMessage());
        var restored = EventJournal.restore(journal.snapshot());
        assertEquals("CURSOR_EXPIRED", assertThrows(IllegalStateException.class, () -> restored.resume("slow", "scope", 100, 1)).getMessage());
        restored.subscribe("fresh", "scope", "", restored.latest("p"), 100, 1);
        assertTrue(restored.poll("fresh", "scope", 2, 1).events().isEmpty());
    }
    @Test void copyCorruptSnapshotAndBoundariesAreSafe() {
        var journal = new EventJournal(2, 262144); journal.append("p", 0, "s", "W", new byte[200000]);
        var copy = journal.copy(); copy.append("p", 0, "s", "W", new byte[200000]);
        assertEquals(1, copy.retainedEvents()); assertEquals(1, journal.retainedEvents());
        assertEquals(200000, copy.retainedBytes());
        assertThrows(IllegalArgumentException.class, () -> journal.read("s", "", new EventJournal.Cursor("p", 0, 2), 1));
        assertThrows(IllegalArgumentException.class, () -> journal.read("s", "", journal.latest("p"), 0));
        assertThrows(IllegalArgumentException.class, () -> journal.subscribe("s", "s", "", journal.latest("p"), 0, 0));
        byte[] snapshot = journal.snapshot(); assertThrows(IllegalArgumentException.class, () -> EventJournal.restore(Arrays.copyOf(snapshot, snapshot.length - 1)));
        byte[] extra = Arrays.copyOf(snapshot, snapshot.length + 1); assertThrows(IllegalArgumentException.class, () -> EventJournal.restore(extra));
        assertThrows(IllegalArgumentException.class, () -> new EventJournal(0, 262144));
        journal.subscribe("early", "s", "", journal.latest("p"), 10, 0); journal.bootstrapTime(1000);
        assertTrue(journal.poll("early", "s", 1, 1001).events().isEmpty());
    }
}
