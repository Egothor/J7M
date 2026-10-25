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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.consensus.CommitIndex;
import org.j7m.middleware.api.consensus.ConsensusCommand;
import org.j7m.middleware.api.consensus.ConsensusException;
import org.j7m.middleware.api.consensus.ConsensusLogEntry;
import org.j7m.middleware.api.consensus.ConsensusNodeId;
import org.j7m.middleware.api.consensus.ConsensusRecoveryReport;
import org.j7m.middleware.api.consensus.ConsensusTerm;
import org.j7m.middleware.api.consensus.LogIndex;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.CursorOptions;
import org.j7m.middleware.api.durablelog.LogCursor;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.j7m.middleware.api.durablelog.LogRecord;
import org.j7m.middleware.api.durablelog.LogRecordHeader;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.api.durablelog.RecoveryOptions;
import org.j7m.middleware.durablelog.DurableLogConfig;
import org.j7m.middleware.durablelog.FileDurableLog;

/**
 * Durable per-node consensus state store backed by the J7M durable log.
 */
public final class DurableConsensusLogStore implements AutoCloseable {
    private static final String SCHEMA_VERSION = "1";
    private final ConsensusNodeId nodeId;
    private final FileDurableLog log;
    private final LogStreamId streamId;

    /**
     * Opens a store.
     *
     * @param nodeId node id
     * @param directory durable directory
     */
    public DurableConsensusLogStore(final ConsensusNodeId nodeId, final Path directory) {
        this.nodeId = nodeId;
        try {
            Files.createDirectories(directory);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("failed to create consensus state directory", exception);
        }
        this.log = new FileDurableLog(DurableLogConfig.defaults(directory));
        try {
            this.log.recover(RecoveryOptions.truncateTail());
        } catch (RuntimeException exception) {
            throw recoveryFailed("durable-log-recover", "unknown", "unknown", "DURABLE_LOG_RECOVERY_FAILED", exception);
        }
        this.streamId = new LogStreamId("consensus-" + nodeId.value());
    }

    /**
     * Loads state.
     *
     * @return recovered state
     */
    public NodePersistentState load() {
        long term = 0L;
        ConsensusNodeId votedFor = null;
        long commitIndex = 0L;
        List<ConsensusLogEntry> entries = new ArrayList<>();
        String eventType = "unknown";
        String version = "unknown";
        try (LogCursor cursor = this.log.openCursor(this.streamId, LogOffset.beginning(), new CursorOptions(true))) {
            java.util.Optional<LogRecord> next = cursor.next();
            while (next.isPresent()) {
                Map<String, String> fields = decodeFields(next.orElseThrow().payload());
                version = required(fields, "schemaVersion", "unknown", "unknown");
                if (!SCHEMA_VERSION.equals(version)) {
                    throw recoveryFailed("decode", "unknown", version, "UNKNOWN_SCHEMA_VERSION", null);
                }
                String type = required(fields, "type", "unknown", version);
                eventType = type;
                if ("TERM".equals(type)) {
                    term = parseLong(required(fields, "term", type, version), "term", type, version);
                    String vote = fields.get("votedFor");
                    votedFor = vote == null || vote.isBlank() ? null : new ConsensusNodeId(vote);
                } else if ("VOTE".equals(type)) {
                    String vote = fields.get("votedFor");
                    votedFor = vote == null || vote.isBlank() ? null : new ConsensusNodeId(vote);
                } else if ("ENTRY".equals(type)) {
                    ConsensusLogEntry entry = decodeEntry(fields);
                    applyRecoveredEntry(entries, entry, commitIndex, type, version);
                } else if ("COMMIT".equals(type)) {
                    long recoveredCommitIndex = parseLong(required(fields, "commitIndex", type, version), "commitIndex", type, version);
                    validateRecoveredCommitIndex(recoveredCommitIndex, commitIndex, entries.size(), type, version);
                    commitIndex = recoveredCommitIndex;
                } else {
                    throw recoveryFailed("decode", type, version, "UNKNOWN_EVENT_TYPE", null);
                }
                next = cursor.next();
            }
        } catch (ConsensusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw recoveryFailed("decode", eventType, version, "MALFORMED_STATE_EVENT", exception);
        }
        return new NodePersistentState(new ConsensusTerm(term), votedFor, entries, new CommitIndex(commitIndex));
    }

    private static void applyRecoveredEntry(
            final List<ConsensusLogEntry> entries,
            final ConsensusLogEntry entry,
            final long commitIndex,
            final String eventType,
            final String version) {
        long recoveredIndex = entry.index().value();
        if (recoveredIndex <= 0L) {
            throw recoveryFailed("decode", eventType, version, "INVALID_ENTRY_INDEX", null);
        }
        if (entry.term().value() < 0L) {
            throw recoveryFailed("decode", eventType, version, "INVALID_ENTRY_TERM", null);
        }
        long nextIndex = entries.size() + 1L;
        if (recoveredIndex > nextIndex) {
            throw recoveryFailed("decode", eventType, version, "NON_MONOTONIC_ENTRY_INDEX", null);
        }
        if (recoveredIndex == nextIndex) {
            validateEntryTermOrdering(entries, entry, eventType, version);
            entries.add(entry);
            return;
        }

        ConsensusLogEntry existing = entries.get(Math.toIntExact(recoveredIndex - 1L));
        if (sameLogEntry(existing, entry)) {
            return;
        }
        if (recoveredIndex <= commitIndex) {
            throw recoveryFailed("decode", eventType, version, "COMMITTED_ENTRY_CONFLICT", null);
        }
        if (existing.term().equals(entry.term())) {
            throw recoveryFailed("decode", eventType, version, "DUPLICATE_ENTRY_CONFLICT", null);
        }
        while (entries.size() >= recoveredIndex) {
            entries.remove(entries.size() - 1);
        }
        validateEntryTermOrdering(entries, entry, eventType, version);
        entries.add(entry);
    }

    private static void validateEntryTermOrdering(
            final List<ConsensusLogEntry> entries,
            final ConsensusLogEntry entry,
            final String eventType,
            final String version) {
        if (!entries.isEmpty()) {
            ConsensusLogEntry previous = entries.get(entries.size() - 1);
            if (entry.term().value() < previous.term().value()) {
                throw recoveryFailed("decode", eventType, version, "NON_MONOTONIC_ENTRY_TERM", null);
            }
        }
    }

    private static void validateRecoveredCommitIndex(
            final long recoveredCommitIndex,
            final long previousCommitIndex,
            final int recoveredEntryCount,
            final String eventType,
            final String version) {
        if (recoveredCommitIndex < 0L) {
            throw recoveryFailed("decode", eventType, version, "INVALID_COMMIT_INDEX", null);
        }
        if (recoveredCommitIndex < previousCommitIndex) {
            throw recoveryFailed("decode", eventType, version, "NON_MONOTONIC_COMMIT_INDEX", null);
        }
        if (recoveredCommitIndex > recoveredEntryCount) {
            throw recoveryFailed("decode", eventType, version, "COMMIT_INDEX_BEYOND_LOG", null);
        }
    }

    /**
     * Persists term and vote.
     *
     * @param term current term
     * @param votedFor voted-for node
     */
    public void saveTerm(final ConsensusTerm term, final ConsensusNodeId votedFor) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", SCHEMA_VERSION);
        fields.put("type", "TERM");
        fields.put("term", Long.toString(term.value()));
        fields.put("votedFor", votedFor == null ? "" : votedFor.value());
        this.append(fields);
    }

    /**
     * Persists vote.
     *
     * @param votedFor voted-for node
     */
    public void saveVote(final ConsensusNodeId votedFor) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", SCHEMA_VERSION);
        fields.put("type", "VOTE");
        fields.put("votedFor", votedFor == null ? "" : votedFor.value());
        this.append(fields);
    }

    /**
     * Persists one log entry.
     *
     * @param entry log entry
     */
    public void appendEntry(final ConsensusLogEntry entry) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", SCHEMA_VERSION);
        fields.put("type", "ENTRY");
        fields.put("index", Long.toString(entry.index().value()));
        fields.put("term", Long.toString(entry.term().value()));
        fields.put("commandId", entry.command().commandId());
        fields.put("payload", Base64.getEncoder().encodeToString(entry.command().payload()));
        this.append(fields);
    }

    /**
     * Persists commit index.
     *
     * @param commitIndex commit index
     */
    public void saveCommitIndex(final CommitIndex commitIndex) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", SCHEMA_VERSION);
        fields.put("type", "COMMIT");
        fields.put("commitIndex", Long.toString(commitIndex.value()));
        this.append(fields);
    }

    /**
     * Returns a safe recovery report for loaded state.
     *
     * @param state state
     * @return report
     */
    public ConsensusRecoveryReport report(final NodePersistentState state) {
        return new ConsensusRecoveryReport(this.nodeId, state.term(), state.votedFor(), state.entries().size(), state.commitIndex(), Map.of());
    }

    private void append(final Map<String, String> fields) {
        LogRecord record = new LogRecord(LogRecordHeader.data(Map.of("component", "consensus")), encodeFields(fields));
        this.log.append(this.streamId, List.of(record), AppendOptions.defaults());
    }

    private static ConsensusLogEntry decodeEntry(final Map<String, String> fields) {
        String version = fields.getOrDefault("schemaVersion", "unknown");
        String type = fields.getOrDefault("type", "ENTRY");
        return new ConsensusLogEntry(
                new LogIndex(parseLong(required(fields, "index", type, version), "index", type, version)),
                new ConsensusTerm(parseLong(required(fields, "term", type, version), "term", type, version)),
                new ConsensusCommand(
                        required(fields, "commandId", type, version),
                        decodePayload(required(fields, "payload", type, version), type, version)));
    }

    private static boolean sameLogEntry(final ConsensusLogEntry left, final ConsensusLogEntry right) {
        return left.index().equals(right.index())
                && left.term().equals(right.term())
                && left.command().commandId().equals(right.command().commandId())
                && Arrays.equals(left.command().payload(), right.command().payload());
    }

    private static String required(final Map<String, String> fields, final String key, final String eventType, final String version) {
        String value = fields.get(key);
        if (value == null) {
            throw recoveryFailed("decode", eventType, version, "MISSING_" + key, null);
        }
        return value;
    }

    private static long parseLong(final String value, final String field, final String eventType, final String version) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw recoveryFailed("decode", eventType, version, "MALFORMED_" + field, exception);
        }
    }

    private static byte[] decodePayload(final String value, final String eventType, final String version) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw recoveryFailed("decode", eventType, version, "MALFORMED_payload", exception);
        }
    }

    private static byte[] encodeFields(final Map<String, String> fields) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            builder.append(Base64.getEncoder().encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8)));
            builder.append('\t');
            builder.append(Base64.getEncoder().encodeToString(entry.getValue().getBytes(StandardCharsets.UTF_8)));
            builder.append('\n');
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> decodeFields(final byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8);
        Map<String, String> fields = new LinkedHashMap<>();
        for (String rawLine : text.split("\n")) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (!line.isEmpty()) {
                int separator = line.indexOf('\t');
                if (separator <= 0) {
                    throw new IllegalStateException("malformed consensus state event lineLength=" + line.length());
                }
                String key;
                String value;
                try {
                    key = new String(Base64.getDecoder().decode(line.substring(0, separator)), StandardCharsets.UTF_8);
                    value = new String(Base64.getDecoder().decode(line.substring(separator + 1)), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException exception) {
                    throw recoveryFailed("decode", "unknown", "unknown", "MALFORMED_BASE64_FIELD", exception);
                }
                fields.put(key, value);
            }
        }
        return fields;
    }

    static ConsensusException recoveryFailed(
            final String stage,
            final String eventType,
            final String version,
            final String reason,
            final Throwable cause) {
        Map<String, String> details = Map.of(
                "stage", stage,
                "eventType", eventType,
                "schemaVersion", version,
                "reasonCode", reason);
        return new ConsensusException(
                MiddlewareErrorCode.RECOVERY_FAILED,
                "consensus recovery failed",
                cause,
                details);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        this.log.close();
    }
}
