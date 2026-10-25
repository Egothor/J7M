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

package org.j7m.middleware.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.consensus.CommitIndex;
import org.j7m.middleware.api.consensus.ConsensusCommand;
import org.j7m.middleware.api.consensus.ConsensusConfiguration;
import org.j7m.middleware.api.consensus.ConsensusException;
import org.j7m.middleware.api.consensus.ConsensusGroupId;
import org.j7m.middleware.api.consensus.ConsensusLogEntry;
import org.j7m.middleware.api.consensus.ConsensusMember;
import org.j7m.middleware.api.consensus.ConsensusMemberSet;
import org.j7m.middleware.api.consensus.ConsensusTerm;
import org.j7m.middleware.api.consensus.LogIndex;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.LogRecord;
import org.j7m.middleware.api.durablelog.LogRecordHeader;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.durablelog.DurableLogConfig;
import org.j7m.middleware.durablelog.FileDurableLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class ConsensusRecoveryCorruptionTest {
    @TempDir
    Path directory;

    @Test
    public void validVersionedStateRecovers() {
        Path nodeDirectory = this.directory.resolve("n1");
        try (DurableConsensusLogStore store = new DurableConsensusLogStore(ConsensusTestSupport.N1, nodeDirectory)) {
            store.saveTerm(new ConsensusTerm(2L), ConsensusTestSupport.N2);
            store.saveVote(ConsensusTestSupport.N2);
            store.appendEntry(new ConsensusLogEntry(
                    new LogIndex(1L),
                    new ConsensusTerm(2L),
                    new ConsensusCommand("cmd-valid", "valid-payload".getBytes(StandardCharsets.UTF_8))));
            store.saveCommitIndex(new CommitIndex(1L));
        }

        try (DurableConsensusLogStore store = new DurableConsensusLogStore(ConsensusTestSupport.N1, nodeDirectory)) {
            NodePersistentState state = store.load();
            assertEquals(2L, state.term().value());
            assertEquals(ConsensusTestSupport.N2, state.votedFor());
            assertEquals(1, state.entries().size());
            assertEquals(1L, state.commitIndex().value());
        }
    }

    @Test
    public void unknownStateVersionFailsRecovery() {
        writeRawEvent(Map.of("schemaVersion", "999", "type", "TERM", "term", "1"));
        ConsensusException exception = assertRecoveryFailed();
        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, exception.code());
        assertEquals("UNKNOWN_SCHEMA_VERSION", exception.remoteError().details().get("reasonCode"));
        assertEquals("999", exception.remoteError().details().get("schemaVersion"));
    }

    @Test
    public void unknownStateEventTypeFailsRecovery() {
        writeRawEvent(Map.of("schemaVersion", "1", "type", "BOGUS"));
        ConsensusException exception = assertRecoveryFailed();
        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, exception.code());
        assertEquals("UNKNOWN_EVENT_TYPE", exception.remoteError().details().get("reasonCode"));
        assertEquals("BOGUS", exception.remoteError().details().get("eventType"));
    }

    @Test
    public void missingRequiredTermFieldFailsRecovery() {
        writeRawEvent(Map.of("schemaVersion", "1", "type", "TERM"));
        ConsensusException exception = assertRecoveryFailed();
        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, exception.code());
        assertEquals("MISSING_term", exception.remoteError().details().get("reasonCode"));
    }

    @Test
    public void missingVoteFieldIsTreatedAsNoVote() {
        writeRawEvent(Map.of("schemaVersion", "1", "type", "VOTE"));
        try (DurableConsensusLogStore store = new DurableConsensusLogStore(ConsensusTestSupport.N1, this.directory.resolve("n1"))) {
            NodePersistentState state = store.load();
            assertNull(state.votedFor());
        }
    }

    @Test
    public void malformedLogEntryFailsRecovery() {
        writeRawEvent(Map.of(
                "schemaVersion", "1",
                "type", "ENTRY",
                "index", "not-a-number",
                "term", "1",
                "commandId", "cmd",
                "payload", Base64.getEncoder().encodeToString("payload".getBytes(StandardCharsets.UTF_8))));
        ConsensusException exception = assertRecoveryFailed();
        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, exception.code());
        assertEquals("MALFORMED_index", exception.remoteError().details().get("reasonCode"));
    }

    @Test
    public void corruptedCommandPayloadFailsRecoveryWithoutPayloadLeak() {
        writeRawEvent(Map.of(
                "schemaVersion", "1",
                "type", "ENTRY",
                "index", "1",
                "term", "1",
                "commandId", "cmd",
                "payload", "$$$secret-consensus-payload$$$"));
        ConsensusException exception = assertRecoveryFailed();
        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, exception.code());
        Map<String, String> details = exception.remoteError().details();
        assertEquals("MALFORMED_payload", details.get("reasonCode"));
        assertFalse(details.containsKey("payload"));
        assertTrue(details.size() <= 16);
        assertThrows(UnsupportedOperationException.class, () -> details.put("mutated", "true"));
        assertFalse(exception.getMessage().contains("secret-consensus-payload"));
        assertFalse(details.toString().contains("secret-consensus-payload"));
    }

    @Test
    public void laterEntryConflictWithCommittedEntryFailsRecovery() {
        ConsensusLogEntry stable = entry(1L, 1L, "stable", "stable-payload");
        writeRawEvents(List.of(entryEvent(stable), commitEvent(1L), entryEvent(entry(1L, 2L, "conflict", "conflict-payload"))));

        ConsensusException exception = assertRecoveryFailed();

        assertEquals("COMMITTED_ENTRY_CONFLICT", exception.remoteError().details().get("reasonCode"));
    }

    @Test
    public void duplicateIdenticalCommittedEntryIsIdempotent() {
        ConsensusLogEntry stable = entry(1L, 1L, "stable", "stable-payload");
        writeRawEvents(List.of(entryEvent(stable), commitEvent(1L), entryEvent(stable)));

        try (DurableConsensusLogStore store = new DurableConsensusLogStore(ConsensusTestSupport.N1, this.directory.resolve("n1"))) {
            NodePersistentState state = store.load();
            assertEquals(1, state.entries().size());
            assertEquals("stable", state.entries().getFirst().command().commandId());
            assertEquals(1L, state.commitIndex().value());
        }
    }

    @Test
    public void duplicateIndexWithDifferentTermOrValueFailsClosed() {
        writeRawEvents(List.of(
                entryEvent(entry(1L, 1L, "stable", "stable-payload")),
                entryEvent(entry(1L, 1L, "stable", "different-payload"))));

        ConsensusException exception = assertRecoveryFailed();

        assertEquals("DUPLICATE_ENTRY_CONFLICT", exception.remoteError().details().get("reasonCode"));
    }

    @Test
    public void truncatedOrReorderedRecordsFailRecovery() {
        writeRawEvent(entryEvent(entry(2L, 1L, "gap", "gap-payload")));
        ConsensusException gap = assertRecoveryFailed();
        assertEquals("NON_MONOTONIC_ENTRY_INDEX", gap.remoteError().details().get("reasonCode"));
    }

    @Test
    public void commitBeyondRecoveredEntriesFailsRecoveryInsteadOfClamping() {
        writeRawEvents(List.of(entryEvent(entry(1L, 1L, "stable", "stable-payload")), commitEvent(2L)));

        ConsensusException exception = assertRecoveryFailed();

        assertEquals("COMMIT_INDEX_BEYOND_LOG", exception.remoteError().details().get("reasonCode"));
    }

    @Test
    public void crashRestartAfterCommittedConflictNeverPublishesPartialRecovery() {
        ConsensusLogEntry stable = entry(1L, 1L, "stable", "stable-payload");
        writeRawEvents(List.of(entryEvent(stable), commitEvent(1L), entryEvent(entry(1L, 1L, "conflict", "other-payload"))));
        ConsensusConfiguration configuration = new ConsensusConfiguration(
                new ConsensusGroupId("recovery-conflict"),
                new ConsensusMemberSet(List.of(
                        new ConsensusMember(ConsensusTestSupport.N1),
                        new ConsensusMember(ConsensusTestSupport.N2),
                        new ConsensusMember(ConsensusTestSupport.N3))),
                Duration.ofMillis(50L),
                Duration.ofMillis(250L));

        ConsensusException exception = assertThrows(ConsensusException.class,
                () -> new ConsensusNode(configuration, ConsensusTestSupport.N1, this.directory, new ConsensusMetrics()));

        assertEquals("COMMITTED_ENTRY_CONFLICT", exception.remoteError().details().get("reasonCode"));
    }

    @Test
    public void validUncommittedSuffixReplacementRecoversOnlyNewSuffix() {
        writeRawEvents(List.of(
                entryEvent(entry(1L, 1L, "committed", "committed-payload")),
                commitEvent(1L),
                entryEvent(entry(2L, 3L, "old-suffix", "old-payload")),
                entryEvent(entry(2L, 4L, "new-suffix", "new-payload"))));

        try (DurableConsensusLogStore store = new DurableConsensusLogStore(ConsensusTestSupport.N1, this.directory.resolve("n1"))) {
            NodePersistentState state = store.load();
            assertEquals(2, state.entries().size());
            assertEquals("committed", state.entries().get(0).command().commandId());
            assertEquals("new-suffix", state.entries().get(1).command().commandId());
            assertEquals(1L, state.commitIndex().value());
        }
    }

    @Test
    public void recoveredCommittedEntriesRemainUnchangedAfterUncommittedReplacement() {
        byte[] committedPayload = "committed-payload".getBytes(StandardCharsets.UTF_8);
        writeRawEvents(List.of(
                entryEvent(new ConsensusLogEntry(
                        new LogIndex(1L),
                        new ConsensusTerm(1L),
                        new ConsensusCommand("committed", committedPayload))),
                commitEvent(1L),
                entryEvent(entry(2L, 3L, "old-suffix", "old-payload")),
                entryEvent(entry(2L, 4L, "new-suffix", "new-payload"))));

        try (DurableConsensusLogStore store = new DurableConsensusLogStore(ConsensusTestSupport.N1, this.directory.resolve("n1"))) {
            NodePersistentState state = store.load();
            assertEquals("committed", state.entries().getFirst().command().commandId());
            assertTrue(Arrays.equals(committedPayload, state.entries().getFirst().command().payload()));
            assertEquals(1L, state.commitIndex().value());
        }
    }

    private ConsensusException assertRecoveryFailed() {
        return assertThrows(ConsensusException.class, () -> {
            try (DurableConsensusLogStore store = new DurableConsensusLogStore(ConsensusTestSupport.N1, this.directory.resolve("n1"))) {
                store.load();
            }
        });
    }

    private void writeRawEvent(final Map<String, String> fields) {
        writeRawEvents(List.of(fields));
    }

    private void writeRawEvents(final List<Map<String, String>> events) {
        Path nodeDirectory = this.directory.resolve("n1");
        try (FileDurableLog log = new FileDurableLog(DurableLogConfig.defaults(nodeDirectory))) {
            List<LogRecord> records = new ArrayList<>(events.size());
            for (Map<String, String> fields : events) {
                records.add(new LogRecord(
                        LogRecordHeader.data(Map.of("component", "consensus-test")),
                        encodeFields(fields)));
            }
            log.append(new LogStreamId("consensus-n1"), records, AppendOptions.defaults());
        }
    }

    private static Map<String, String> entryEvent(final ConsensusLogEntry entry) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", "1");
        fields.put("type", "ENTRY");
        fields.put("index", Long.toString(entry.index().value()));
        fields.put("term", Long.toString(entry.term().value()));
        fields.put("commandId", entry.command().commandId());
        fields.put("payload", Base64.getEncoder().encodeToString(entry.command().payload()));
        return fields;
    }

    private static Map<String, String> commitEvent(final long commitIndex) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", "1");
        fields.put("type", "COMMIT");
        fields.put("commitIndex", Long.toString(commitIndex));
        return fields;
    }

    private static ConsensusLogEntry entry(final long index, final long term, final String commandId, final String payload) {
        return new ConsensusLogEntry(
                new LogIndex(index),
                new ConsensusTerm(term),
                new ConsensusCommand(commandId, payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] encodeFields(final Map<String, String> fields) {
        StringBuilder builder = new StringBuilder();
        Map<String, String> ordered = new LinkedHashMap<>(fields);
        for (Map.Entry<String, String> entry : ordered.entrySet()) {
            builder.append(Base64.getEncoder().encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8)));
            builder.append('\t');
            builder.append(Base64.getEncoder().encodeToString(entry.getValue().getBytes(StandardCharsets.UTF_8)));
            builder.append('\n');
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }
}
