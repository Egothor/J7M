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
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReplicatedResourcesJournalTest {
    private static byte[] command(String operation, String request, String... args) { return new ResourceCommand(operation, "scope", request, List.of(args)).encode(); }
    @Test void versionOneSnapshotsUpgradeWithoutDroppingStateResultsAndLegacyEvents() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1); out.writeInt(100000); out.writeLong(7); out.writeLong(1000); out.writeLong(0); out.writeBoolean(true);
        out.writeInt(1); out.writeUTF("scope\0key"); out.writeUTF("old-value"); out.writeLong(7);
        out.writeInt(0);
        out.writeInt(1); out.writeUTF("scope\0old-request"); out.writeUTF("fingerprint"); out.writeUTF("OK"); out.writeUTF("old-result"); out.writeLong(7); out.writeLong(1000000);
        out.writeInt(0); out.writeInt(0); out.writeInt(1); out.writeLong(7); out.writeUTF("scope"); out.writeUTF("BATCH"); out.flush();
        var source = new ReplicatedResources(); source.restore(bytes.toByteArray());
        assertEquals("old-value", source.queryAtReadIndex("scope", "key", 7).value()); assertEquals("old-result", source.result("scope", "old-request", 7).value()); assertEquals(1, source.events("scope", 7).size());
        var restored = new ReplicatedResources(); restored.restore(source.snapshot());
        assertEquals("old-value", restored.queryAtReadIndex("scope", "key", 7).value());
        restored.apply(8, command("BATCH", "new", "new-key", "0", "new-value")); assertEquals("new-value", restored.queryAtReadIndex("scope", "new-key", 8).value());
    }
    @Test void ownershipMigrationPreservesCursorsDedupeAndPreparedLocksBlockTransfer() {
        var source = new ReplicatedResources(); source.ownershipChanged("partition-a", 1);
        var beginning = source.snapshotAndTail("scope", 0).cursor();
        source.apply(1, command("SUBSCRIBE", "sub", "consumer", "BATCH", beginning.partition(), "0", "0", "1000"));
        byte[] write = command("BATCH", "one", "x", "0", "v"); source.apply(2, write);
        var before = source.poll("scope", "consumer", 10, 2).events().getFirst(); assertEquals(1, before.cursor().epoch());
        var target = new ReplicatedResources(); target.restore(source.snapshot()); target.ownershipChanged("partition-a", 2);
        target.apply(3, write); assertEquals(1, target.poll("scope", "consumer", 10, 3).events().size());
        target.apply(4, command("BATCH", "two", "y", "0", "v2"));
        var page = target.poll("scope", "consumer", 10, 4); assertEquals(before.cursor(), page.events().getFirst().cursor()); assertEquals(2, page.events().getLast().cursor().epoch()); assertEquals(2, page.events().getLast().cursor().sequence());
        assertThrows(IllegalArgumentException.class, () -> target.ownershipChanged("partition-a", 1));
        target.apply(5, command("LOCK_READ", "lock", "tx", "x")); assertFalse(target.transferReady());
        byte[] otherScope = new ResourceCommand("BATCH", "other", "x", List.of("k", "0", "v")).encode();
        assertThrows(SecurityException.class, () -> target.validate(otherScope, "scope", false));
        assertThrows(SecurityException.class, () -> target.validate(command("COMMIT", "commit", "tx", "1"), "scope", false));
        target.validate(write, "scope", false);
        byte[] query = new ResourceCommand("QUERY_READ", "scope", "q", List.of("x")).encode();
        target.validateQuery(query, "scope"); assertTrue(target.query(query).length > 0);
        assertThrows(SecurityException.class, () -> target.validateQuery(query, "other"));
        assertTrue(target.timeSensitive(command("RESUME", "r", "c", "1"))); assertFalse(target.timeSensitive(write));
        assertEquals("TIME", ResourceCommand.decode(target.timeCommand(1000)).operation());
    }
    @Test void sourceEventRoundTripsRejectsCorruptionAndCarriesFailedResult() {
        var event = new ResourceEvent(1, "CONFLICT", new ResourceCommand("BATCH", "scope", "id", List.of("k", "0", "v")));
        assertEquals(event, ResourceEvent.decode(event.encode())); assertTrue(event.changes().isEmpty());
        var fenced = new ResourceEvent(2, "OK", new ResourceCommand("FENCED", "scope", "id", List.of("lease", "1", "1", "key", "new")));
        assertEquals(Map.of("key", new ReplicatedResources.Value("new", 2)), fenced.changes());
        byte[] versionOne = event.encode(); java.nio.ByteBuffer.wrap(versionOne).putInt(1);
        assertEquals(event, ResourceEvent.decode(versionOne));
        var legacyCommit = new ResourceEvent(1, "COMMITTED", new ResourceCommand("COMMIT", "scope", "tx", List.of("tx", "1"))).encode(); java.nio.ByteBuffer.wrap(legacyCommit).putInt(1);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ResourceEvent.decode(legacyCommit)).getMessage().contains("SNAPSHOT_REQUIRED")); assertThrows(IllegalArgumentException.class, () -> ResourceEvent.decode(new byte[0]));
        byte[] extra = Arrays.copyOf(event.encode(), event.encode().length + 1); assertThrows(IllegalArgumentException.class, () -> ResourceEvent.decode(extra));
        assertThrows(IllegalArgumentException.class, () -> new ResourceEvent(0, "OK", event.command()));
    }
    @Test void maximumSingleEventAndPageBudgetRemainBoundedAndInvalidCursorDoesNotPoisonSource() {
        var journal = new EventJournal(10, 4194304); var start = journal.latest("p");
        journal.append("p", 0, "scope", "W", new byte[1049600]); journal.append("p", 0, "scope", "W", new byte[1]);
        var first = journal.read("scope", "", start, 256); assertEquals(1, first.events().size()); assertEquals(1, first.through().sequence());
        assertEquals(1, journal.read("scope", "", first.through(), 256).events().size());
        assertThrows(IllegalArgumentException.class, () -> journal.append("p", 0, "scope", "W", new byte[1049601])); assertEquals(2, journal.latest("p").sequence());
        var machine = new ReplicatedResources(); machine.apply(1, command("SUBSCRIBE", "bad", "s", "", "foreign", "0", "0", "100"));
        assertEquals("Foreign partition", machine.result("scope", "bad", 1).status());
        machine.apply(2, command("BATCH", "good", "x", "0", "v")); assertEquals("v", machine.queryAtReadIndex("scope", "x", 2).value());
    }
    @Test void snapshotBetweenPrepareAndCommitReconstructsCommittedWritesWithoutPreparedHistory() throws Exception {
        var source = new ReplicatedResources();
        source.apply(1, command("PREPARE", "prepare", "tx", "WRITE", "x", "secret-value"));
        var image = source.snapshotAndTail("scope", 1); assertEquals(8, image.snapshot().length);
        source.apply(2, command("SUBSCRIBE", "sub", "consumer", "", image.cursor().partition(), Long.toString(image.cursor().epoch()), Long.toString(image.cursor().sequence()), "1000"));
        source.apply(3, command("COMMIT", "commit", "tx", "1"));
        var page = source.poll("scope", "consumer", 10, 3); assertEquals(1, page.events().size());
        var event = ResourceEvent.decode(page.events().getFirst().payload());
        assertEquals("COMMIT", page.events().getFirst().operation());
        assertEquals("COMMITTED", event.status());
        var change = event.command(); assertEquals("BATCH", change.operation());
        assertEquals(List.of("x", "0", "secret-value"), change.arguments());
        assertEquals(source.queryAtReadIndex("scope", "x", 3), new ReplicatedResources.Value(change.arguments().get(2), event.index()));
        assertEquals(Map.of("x", source.queryAtReadIndex("scope", "x", 3)), event.changes());
        source.apply(4, command("COMMIT", "rejected-commit", "tx", "2"));
        var rejected = ResourceEvent.decode(source.poll("scope", "consumer", 10, 4).events().getLast().payload());
        assertEquals("CONFLICT", rejected.status()); assertTrue(rejected.changes().isEmpty());
        source.apply(5, command("COMMIT", "commit-again", "tx", "1"));
        var noOp = ResourceEvent.decode(source.poll("scope", "consumer", 10, 5).events().getLast().payload());
        assertEquals("COMMITTED", noOp.status()); assertTrue(noOp.changes().isEmpty());
    }
}
