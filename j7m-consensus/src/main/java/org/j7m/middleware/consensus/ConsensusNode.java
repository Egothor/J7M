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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.j7m.middleware.api.consensus.AppendEntriesRequest;
import org.j7m.middleware.api.consensus.AppendEntriesResponse;
import org.j7m.middleware.api.consensus.CommitIndex;
import org.j7m.middleware.api.consensus.ConsensusCommand;
import org.j7m.middleware.api.consensus.ConsensusCommandResult;
import org.j7m.middleware.api.consensus.ConsensusConfiguration;
import org.j7m.middleware.api.consensus.ConsensusLogEntry;
import org.j7m.middleware.api.consensus.ConsensusLogEntryStatus;
import org.j7m.middleware.api.consensus.ConsensusNodeId;
import org.j7m.middleware.api.consensus.ConsensusRole;
import org.j7m.middleware.api.consensus.ConsensusState;
import org.j7m.middleware.api.consensus.ConsensusTerm;
import org.j7m.middleware.api.consensus.LogIndex;
import org.j7m.middleware.api.consensus.RequestVoteRequest;
import org.j7m.middleware.api.consensus.RequestVoteResponse;

/**
 * Single deterministic Raft-like node.
 */
public final class ConsensusNode implements AutoCloseable {
    private final ConsensusConfiguration configuration;
    private final ConsensusNodeId nodeId;
    private final Path storageRoot;
    private final DurableConsensusLogStore store;
    private final ConsensusMetrics metrics;
    private final List<ConsensusLogEntry> logEntries;
    private final List<ConsensusLogEntry> appliedEntries;
    private final Map<ConsensusNodeId, Long> matchIndex;
    private final Map<ConsensusNodeId, Long> nextIndex;
    private final Set<ConsensusNodeId> votesReceived;
    private final Set<ConsensusNodeId> expectedVoteResponders;
    private ConsensusRole role;
    private ConsensusTerm currentTerm;
    private ConsensusNodeId votedFor;
    private ConsensusNodeId leaderId;
    private long commitIndex;
    private long lastApplied;
    private boolean crashed;
    private long lastLeaderLogSize;

    /**
     * Creates and recovers a node.
     *
     * @param configuration group configuration
     * @param nodeId node id
     * @param storageRoot storage root
     * @param metrics shared metrics
     */
    public ConsensusNode(
            final ConsensusConfiguration configuration,
            final ConsensusNodeId nodeId,
            final Path storageRoot,
            final ConsensusMetrics metrics) {
        this.configuration = configuration;
        this.nodeId = nodeId;
        this.storageRoot = storageRoot;
        this.metrics = metrics;
        this.store = new DurableConsensusLogStore(nodeId, storageRoot.resolve(nodeId.value()));
        NodePersistentState state = this.store.load();
        this.currentTerm = state.term();
        this.votedFor = state.votedFor();
        this.logEntries = new ArrayList<>(state.entries());
        this.commitIndex = state.commitIndex().value();
        this.lastApplied = 0L;
        this.role = ConsensusRole.FOLLOWER;
        this.leaderId = null;
        this.appliedEntries = new ArrayList<>();
        this.matchIndex = new LinkedHashMap<>();
        this.nextIndex = new LinkedHashMap<>();
        this.votesReceived = new LinkedHashSet<>();
        this.expectedVoteResponders = new LinkedHashSet<>();
        this.metrics.recoveries++;
        this.applyCommitted();
    }

    /**
     * Returns node id.
     *
     * @return node id
     */
    public ConsensusNodeId nodeId() {
        return this.nodeId;
    }

    /**
     * Returns whether this node is crashed.
     *
     * @return true when crashed
     */
    public boolean crashed() {
        return this.crashed;
    }

    /**
     * Marks this node crashed.
     */
    public void crash() {
        this.crashed = true;
        this.role = ConsensusRole.FOLLOWER;
        this.leaderId = null;
    }

    /**
     * Restores this node process after durable recovery by replacing volatile state.
     *
     * @return recovered node
     */
    public ConsensusNode restart() {
        this.close();
        return new ConsensusNode(this.configuration, this.nodeId, this.storageRoot, this.metrics);
    }

    /**
     * Starts an election and returns vote request messages.
     *
     * @return outgoing messages
     */
    public List<ConsensusMessage> startElection() {
        if (this.crashed) {
            return List.of();
        }
        this.role = ConsensusRole.CANDIDATE;
        this.leaderId = null;
        this.currentTerm = new ConsensusTerm(this.currentTerm.value() + 1L);
        this.votedFor = this.nodeId;
        this.votesReceived.clear();
        this.votesReceived.add(this.nodeId);
        this.expectedVoteResponders.clear();
        this.store.saveTerm(this.currentTerm, this.votedFor);
        this.metrics.elections++;
        List<ConsensusMessage> messages = new ArrayList<>();
        for (org.j7m.middleware.api.consensus.ConsensusMember member : this.configuration.members().members()) {
            if (!member.nodeId().equals(this.nodeId)) {
                this.expectedVoteResponders.add(member.nodeId());
                RequestVoteRequest request = new RequestVoteRequest(
                        this.configuration.groupId(),
                        this.currentTerm,
                        this.nodeId,
                        new LogIndex(this.lastLogIndex()),
                        new ConsensusTerm(this.lastLogTerm()));
                messages.add(new ConsensusMessage.VoteRequest(this.nodeId, member.nodeId(), request));
            }
        }
        this.becomeLeaderIfMajority(messages);
        return messages;
    }

    /**
     * Handles a vote request.
     *
     * @param request request
     * @return response
     */
    public RequestVoteResponse onRequestVote(final RequestVoteRequest request) {
        if (this.crashed) {
            return null;
        }
        if (!this.validVoteRequest(request)) {
            this.metrics.voteRejections++;
            return new RequestVoteResponse(this.configuration.groupId(), this.currentTerm, this.nodeId, false, "UNKNOWN_CANDIDATE");
        }
        this.metrics.voteRequests++;
        if (request.term().value() > this.currentTerm.value()) {
            this.stepDown(request.term(), null);
        }
        boolean upToDate = request.lastLogTerm().value() > this.lastLogTerm()
                || (request.lastLogTerm().value() == this.lastLogTerm() && request.lastLogIndex().value() >= this.lastLogIndex());
        boolean canVote = request.term().value() == this.currentTerm.value()
                && (this.votedFor == null || this.votedFor.equals(request.candidateId()))
                && upToDate;
        if (canVote) {
            this.votedFor = request.candidateId();
            this.store.saveVote(this.votedFor);
            return new RequestVoteResponse(this.configuration.groupId(), this.currentTerm, this.nodeId, true, "GRANTED");
        }
        this.metrics.voteRejections++;
        return new RequestVoteResponse(this.configuration.groupId(), this.currentTerm, this.nodeId, false, "REJECTED");
    }

    /**
     * Handles a vote response and returns any leader heartbeats when leadership is won.
     *
     * @param response response
     * @return outgoing messages
     */
    public List<ConsensusMessage> onRequestVoteResponse(final RequestVoteResponse response) {
        if (this.crashed) {
            return List.of();
        }
        if (!this.validVoteResponse(response)) {
            return List.of();
        }
        if (this.role != ConsensusRole.CANDIDATE) {
            return List.of();
        }
        if (response.term().value() > this.currentTerm.value()) {
            this.stepDown(response.term(), null);
            return List.of();
        }
        if (!response.term().equals(this.currentTerm)
                || !this.expectedVoteResponders.remove(response.nodeId())) {
            return List.of();
        }
        if (response.voteGranted()) {
            this.votesReceived.add(response.nodeId());
            List<ConsensusMessage> messages = new ArrayList<>();
            this.becomeLeaderIfMajority(messages);
            return messages;
        }
        return List.of();
    }

    /**
     * Handles AppendEntries.
     *
     * @param request request
     * @return response
     */
    public AppendEntriesResponse onAppendEntries(final AppendEntriesRequest request) {
        if (this.crashed) {
            return null;
        }
        if (!this.validAppendRequest(request)) {
            this.metrics.appendRejections++;
            return new AppendEntriesResponse(this.configuration.groupId(), this.currentTerm, this.nodeId, false, new LogIndex(this.lastLogIndex()), "INVALID_APPEND");
        }
        this.metrics.appendRequests++;
        if (request.term().value() < this.currentTerm.value()) {
            this.metrics.appendRejections++;
            return new AppendEntriesResponse(this.configuration.groupId(), this.currentTerm, this.nodeId, false, new LogIndex(this.lastLogIndex()), "STALE_TERM");
        }
        if (request.term().value() > this.currentTerm.value() || this.role != ConsensusRole.FOLLOWER) {
            this.stepDown(request.term(), request.leaderId());
        } else {
            this.leaderId = request.leaderId();
        }
        if (request.prevLogIndex().value() > this.lastLogIndex()
                || this.termAt(request.prevLogIndex().value()) != request.prevLogTerm().value()) {
            this.metrics.appendRejections++;
            return new AppendEntriesResponse(this.configuration.groupId(), this.currentTerm, this.nodeId, false, new LogIndex(this.lastLogIndex()), "LOG_MISMATCH");
        }
        int firstNewEntry = this.firstEntryToPublish(request.entries());
        if (firstNewEntry < 0) {
            this.metrics.appendRejections++;
            return new AppendEntriesResponse(this.configuration.groupId(), this.currentTerm, this.nodeId, false, new LogIndex(this.lastLogIndex()), "COMMITTED_CONFLICT");
        }
        for (int index = firstNewEntry; index < request.entries().size(); index++) {
            ConsensusLogEntry entry = request.entries().get(index);
            while (this.logEntries.size() >= entry.index().value()) {
                this.logEntries.remove(this.logEntries.size() - 1);
            }
            this.logEntries.add(entry);
        }
        for (int index = firstNewEntry; index < request.entries().size(); index++) {
            this.store.appendEntry(request.entries().get(index));
        }
        if (request.leaderCommit().value() > this.commitIndex) {
            this.commitIndex = Math.min(request.leaderCommit().value(), this.lastLogIndex());
            this.store.saveCommitIndex(new CommitIndex(this.commitIndex));
            this.applyCommitted();
        }
        return new AppendEntriesResponse(this.configuration.groupId(), this.currentTerm, this.nodeId, true, new LogIndex(this.lastLogIndex()), "APPENDED");
    }

    /**
     * Handles AppendEntries response.
     *
     * @param response response
     * @return true if the leader state changed
     */
    public boolean onAppendEntriesResponse(final AppendEntriesResponse response) {
        if (this.crashed) {
            return false;
        }
        if (!this.validAppendResponse(response) || this.role != ConsensusRole.LEADER) {
            return false;
        }
        if (response.term().value() > this.currentTerm.value()) {
            this.stepDown(response.term(), null);
            return true;
        }
        if (!response.term().equals(this.currentTerm)) {
            return false;
        }
        if (response.success()) {
            this.matchIndex.put(response.nodeId(), response.matchIndex().value());
            this.nextIndex.put(response.nodeId(), response.matchIndex().value() + 1L);
            return this.advanceCommitIndex();
        }
        long next = this.nextIndex.getOrDefault(response.nodeId(), this.lastLogIndex() + 1L);
        this.nextIndex.put(response.nodeId(), Math.max(1L, next - 1L));
        return true;
    }

    /**
     * Appends a command on the leader.
     *
     * @param command command
     * @return assigned entry or null when not leader
     */
    public ConsensusLogEntry appendLeaderCommand(final ConsensusCommand command) {
        if (this.crashed || this.role != ConsensusRole.LEADER) {
            return null;
        }
        ConsensusLogEntry entry = new ConsensusLogEntry(new LogIndex(this.lastLogIndex() + 1L), this.currentTerm, command);
        this.logEntries.add(entry);
        this.lastLeaderLogSize = this.logEntries.size();
        this.store.appendEntry(entry);
        this.matchIndex.put(this.nodeId, entry.index().value());
        return entry;
    }

    /**
     * Creates append request for one follower.
     *
     * @param follower follower id
     * @return request
     */
    public AppendEntriesRequest appendRequestFor(final ConsensusNodeId follower) {
        long next = this.nextIndex.getOrDefault(follower, this.lastLogIndex() + 1L);
        long prevIndex = Math.max(0L, next - 1L);
        List<ConsensusLogEntry> entries = new ArrayList<>();
        for (ConsensusLogEntry entry : this.logEntries) {
            if (entry.index().value() >= next) {
                entries.add(entry);
            }
        }
        return new AppendEntriesRequest(
                this.configuration.groupId(),
                this.currentTerm,
                this.nodeId,
                new LogIndex(prevIndex),
                new ConsensusTerm(this.termAt(prevIndex)),
                entries,
                new CommitIndex(this.commitIndex));
    }

    /**
     * Returns all follower append messages.
     *
     * @return append messages
     */
    public List<ConsensusMessage> replicateMessages() {
        if (this.crashed || this.role != ConsensusRole.LEADER) {
            return List.of();
        }
        List<ConsensusMessage> messages = new ArrayList<>();
        for (org.j7m.middleware.api.consensus.ConsensusMember member : this.configuration.members().members()) {
            if (!member.nodeId().equals(this.nodeId)) {
                messages.add(new ConsensusMessage.AppendRequest(this.nodeId, member.nodeId(), this.appendRequestFor(member.nodeId())));
            }
        }
        return messages;
    }

    /**
     * Returns whether leader append-only has held for this node.
     *
     * @return true when no leader truncation was observed
     */
    public boolean leaderAppendOnlyHeld() {
        return this.role != ConsensusRole.LEADER || this.logEntries.size() >= this.lastLeaderLogSize;
    }

    /**
     * Returns state snapshot.
     *
     * @return state
     */
    public ConsensusState state() {
        List<ConsensusLogEntryStatus> redactedEntries = new ArrayList<>();
        for (ConsensusLogEntry entry : this.logEntries) {
            redactedEntries.add(new ConsensusLogEntryStatus(
                    entry.index(),
                    entry.term(),
                    entry.command().commandId(),
                    entry.command().payload().length,
                    entry.index().value() <= this.commitIndex,
                    entry.index().value() <= this.lastApplied));
        }
        return new ConsensusState(
                this.nodeId,
                this.role,
                this.currentTerm,
                this.votedFor,
                this.leaderId,
                new CommitIndex(this.commitIndex),
                new LogIndex(this.lastApplied),
                new LogIndex(this.lastLogIndex()),
                redactedEntries);
    }

    /**
     * Returns applied entries.
     *
     * @return applied entries
     */
    public List<ConsensusLogEntry> appliedEntries() {
        return List.copyOf(this.appliedEntries);
    }

    /**
     * Returns current role.
     *
     * @return role
     */
    public ConsensusRole role() {
        return this.role;
    }

    /**
     * Returns current term.
     *
     * @return term
     */
    public ConsensusTerm term() {
        return this.currentTerm;
    }

    /**
     * Returns current commit index.
     *
     * @return commit index
     */
    public long commitIndexValue() {
        return this.commitIndex;
    }

    /**
     * Returns log entries.
     *
     * @return log entries
     */
    public List<ConsensusLogEntry> logEntries() {
        return List.copyOf(this.logEntries);
    }

    void appendUncommittedDiagnosticEntry(final ConsensusLogEntry entry) {
        if (entry.index().value() <= this.commitIndex) {
            throw new IllegalArgumentException("diagnostic entry must not replace committed entries");
        }
        while (this.logEntries.size() >= entry.index().value()) {
            this.logEntries.remove(this.logEntries.size() - 1);
        }
        this.logEntries.add(entry);
        this.store.appendEntry(entry);
    }

    private void becomeLeaderIfMajority(final List<ConsensusMessage> messages) {
        if (this.role == ConsensusRole.CANDIDATE && this.votesReceived.size() >= this.configuration.members().majority()) {
            this.role = ConsensusRole.LEADER;
            this.leaderId = this.nodeId;
            this.expectedVoteResponders.clear();
            this.metrics.leadersElected++;
            this.matchIndex.clear();
            this.nextIndex.clear();
            for (org.j7m.middleware.api.consensus.ConsensusMember member : this.configuration.members().members()) {
                this.nextIndex.put(member.nodeId(), this.lastLogIndex() + 1L);
                this.matchIndex.put(member.nodeId(), member.nodeId().equals(this.nodeId) ? this.lastLogIndex() : 0L);
            }
            messages.addAll(this.replicateMessages());
        }
    }

    private boolean advanceCommitIndex() {
        boolean advanced = false;
        for (long candidate = this.lastLogIndex(); candidate > this.commitIndex; candidate--) {
            int replicated = 0;
            for (org.j7m.middleware.api.consensus.ConsensusMember member : this.configuration.members().members()) {
                if (this.matchIndex.getOrDefault(member.nodeId(), 0L) >= candidate) {
                    replicated++;
                }
            }
            if (replicated >= this.configuration.members().majority() && this.termAt(candidate) == this.currentTerm.value()) {
                this.commitIndex = candidate;
                this.store.saveCommitIndex(new CommitIndex(this.commitIndex));
                this.applyCommitted();
                this.metrics.commandsCommitted++;
                advanced = true;
                break;
            }
        }
        return advanced;
    }

    private void applyCommitted() {
        while (this.lastApplied < this.commitIndex && this.lastApplied < this.logEntries.size()) {
            ConsensusLogEntry entry = this.logEntries.get((int) this.lastApplied);
            this.appliedEntries.add(entry);
            this.lastApplied++;
        }
    }

    private boolean validVoteRequest(final RequestVoteRequest request) {
        if (!this.configuration.groupId().equals(request.groupId())) {
            return false;
        }
        if (!this.configuration.members().contains(request.candidateId())) {
            return false;
        }
        return !this.nodeId.equals(request.candidateId());
    }

    private boolean validVoteResponse(final RequestVoteResponse response) {
        if (!this.configuration.groupId().equals(response.groupId())) {
            return false;
        }
        if (!this.configuration.members().contains(response.nodeId())) {
            return false;
        }
        return !this.nodeId.equals(response.nodeId());
    }

    private boolean validAppendRequest(final AppendEntriesRequest request) {
        if (!this.configuration.groupId().equals(request.groupId())) {
            return false;
        }
        if (!this.configuration.members().contains(request.leaderId()) || this.nodeId.equals(request.leaderId())) {
            return false;
        }
        long leaderCommit = request.leaderCommit().value();
        long previousIndex = request.prevLogIndex().value();
        if (leaderCommit > previousIndex && leaderCommit - previousIndex > request.entries().size()) {
            return false;
        }
        long expectedIndex = request.prevLogIndex().value() + 1L;
        for (ConsensusLogEntry entry : request.entries()) {
            if (entry.index().value() != expectedIndex) {
                return false;
            }
            if (entry.term().value() > request.term().value()) {
                return false;
            }
            expectedIndex++;
        }
        return true;
    }

    private boolean validAppendResponse(final AppendEntriesResponse response) {
        if (!this.configuration.groupId().equals(response.groupId())) {
            return false;
        }
        if (!this.configuration.members().contains(response.nodeId())) {
            return false;
        }
        return !this.nodeId.equals(response.nodeId());
    }

    private int firstEntryToPublish(final List<ConsensusLogEntry> entries) {
        for (int offset = 0; offset < entries.size(); offset++) {
            ConsensusLogEntry incoming = entries.get(offset);
            long incomingIndex = incoming.index().value();
            if (incomingIndex <= this.lastLogIndex()) {
                ConsensusLogEntry existing = this.logEntries.get((int) incomingIndex - 1);
                if (sameLogEntry(existing, incoming)) {
                    continue;
                }
                if (incomingIndex <= this.commitIndex) {
                    return -1;
                }
                return offset;
            }
            return offset;
        }
        return entries.size();
    }

    private static boolean sameLogEntry(final ConsensusLogEntry left, final ConsensusLogEntry right) {
        return left.index().equals(right.index())
                && left.term().equals(right.term())
                && left.command().commandId().equals(right.command().commandId())
                && Arrays.equals(left.command().payload(), right.command().payload());
    }

    private void stepDown(final ConsensusTerm term, final ConsensusNodeId newLeader) {
        this.role = ConsensusRole.FOLLOWER;
        this.currentTerm = term;
        this.votedFor = null;
        this.leaderId = newLeader;
        this.votesReceived.clear();
        this.expectedVoteResponders.clear();
        this.store.saveTerm(this.currentTerm, this.votedFor);
    }

    private long lastLogIndex() {
        return this.logEntries.size();
    }

    private long lastLogTerm() {
        return this.termAt(this.lastLogIndex());
    }

    private long termAt(final long index) {
        if (index == 0L) {
            return 0L;
        }
        if (index > this.logEntries.size()) {
            return -1L;
        }
        return this.logEntries.get((int) index - 1).term().value();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        this.store.close();
    }
}
