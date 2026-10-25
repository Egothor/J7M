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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.j7m.middleware.api.consensus.AppendEntriesResponse;
import org.j7m.middleware.api.consensus.ConsensusCommand;
import org.j7m.middleware.api.consensus.ConsensusCommandResult;
import org.j7m.middleware.api.consensus.ConsensusConfiguration;
import org.j7m.middleware.api.consensus.ConsensusLogEntry;
import org.j7m.middleware.api.consensus.ConsensusMember;
import org.j7m.middleware.api.consensus.ConsensusMemberSet;
import org.j7m.middleware.api.consensus.ConsensusMetricsSnapshot;
import org.j7m.middleware.api.consensus.ConsensusNodeId;
import org.j7m.middleware.api.consensus.ConsensusRole;
import org.j7m.middleware.api.consensus.ConsensusSafetyReport;
import org.j7m.middleware.api.consensus.ConsensusSimulationReport;
import org.j7m.middleware.api.consensus.ConsensusState;
import org.j7m.middleware.api.consensus.ConsensusStatus;
import org.j7m.middleware.api.consensus.ConsensusTerm;
import org.j7m.middleware.api.consensus.LogIndex;
import org.j7m.middleware.api.consensus.RequestVoteResponse;

/**
 * Deterministic local consensus simulation harness.
 */
public final class ConsensusSimulation implements AutoCloseable {
    private static final int TRACE_LIMIT = 1_024;
    private static final int EVIDENCE_LIMIT = 256;
    private static final int HISTORY_LIMIT = 4_096;
    private final ConsensusConfig config;
    private final long seed;
    private final ConsensusMetrics metrics;
    private final InMemoryConsensusNetwork network;
    private final List<String> trace;
    private final List<String> staleLeaderEvidence;
    private final List<ConsensusHistoryEvent> history;
    private final Map<ConsensusNodeId, ConsensusNode> nodes;
    private long crashes;
    private long restarts;
    private boolean historyTruncated;

    /**
     * Creates a simulation.
     *
     * @param config configuration
     * @param seed deterministic seed
     */
    public ConsensusSimulation(final ConsensusConfig config, final long seed) {
        this.config = config;
        this.seed = seed;
        this.metrics = new ConsensusMetrics();
        this.network = new InMemoryConsensusNetwork();
        this.trace = new ArrayList<>();
        this.staleLeaderEvidence = new ArrayList<>();
        this.history = new ArrayList<>();
        this.nodes = new LinkedHashMap<>();
        for (ConsensusMember member : config.configuration().members().members()) {
            this.nodes.put(member.nodeId(), new ConsensusNode(config.configuration(), member.nodeId(), config.storageRoot(), this.metrics));
        }
        this.recordClusterState();
    }

    /**
     * Creates a three-node simulation.
     *
     * @param root storage root
     * @param seed deterministic seed
     * @return simulation
     */
    public static ConsensusSimulation threeNode(final Path root, final long seed) {
        ConsensusConfiguration configuration = new ConsensusConfiguration(
                new org.j7m.middleware.api.consensus.ConsensusGroupId("demo-consensus"),
                new ConsensusMemberSet(List.of(
                        new ConsensusMember(new ConsensusNodeId("n1")),
                        new ConsensusMember(new ConsensusNodeId("n2")),
                        new ConsensusMember(new ConsensusNodeId("n3")))),
                java.time.Duration.ofMillis(50L),
                java.time.Duration.ofMillis(250L));
        return new ConsensusSimulation(new ConsensusConfig(configuration, root), seed);
    }

    /**
     * Starts an election.
     *
     * @param nodeId candidate
     * @return candidate state after delivery
     */
    public ConsensusState elect(final ConsensusNodeId nodeId) {
        ConsensusNode node = this.node(nodeId);
        this.recordTrace("election:" + nodeId.value());
        this.network.sendAll(node.startElection());
        this.deliverAll();
        return node.state();
    }

    /**
     * Submits a command to the current leader.
     *
     * @param command command
     * @return result
     */
    public ConsensusCommandResult submit(final ConsensusCommand command) {
        ConsensusNode leader = this.currentLeader();
        if (leader == null) {
            return new ConsensusCommandResult(command.commandId(), false, false, new LogIndex(0L), null, "NO_LEADER");
        }
        return this.submitTo(leader.nodeId(), command);
    }

    /**
     * Submits a command to a specific node.
     *
     * @param nodeId node id
     * @param command command
     * @return result
     */
    public ConsensusCommandResult submitTo(final ConsensusNodeId nodeId, final ConsensusCommand command) {
        ConsensusNode leader = this.node(nodeId);
        ConsensusLogEntry entry = leader.appendLeaderCommand(command);
        if (entry == null) {
            this.recordStaleLeaderEvidence("STALE_LEADER_REJECTED:not-leader:" + nodeId.value());
            return new ConsensusCommandResult(command.commandId(), false, false, new LogIndex(0L), this.currentLeaderId(), "NOT_LEADER");
        }
        this.recordTrace("command:" + command.commandId() + "@" + nodeId.value());
        this.network.sendAll(leader.replicateMessages());
        this.deliverAll();
        boolean committed = leader.commitIndexValue() >= entry.index().value();
        if (!committed) {
            this.recordStaleLeaderEvidence("MINORITY_COMMIT_REJECTED:" + nodeId.value() + ":index-" + entry.index().value());
        }
        this.network.sendAll(leader.replicateMessages());
        this.deliverAll();
        return new ConsensusCommandResult(command.commandId(), true, committed, entry.index(), nodeId, committed ? "COMMITTED" : "NO_MAJORITY");
    }

    /**
     * Drops next sent message.
     */
    public void dropNextMessage() {
        this.network.dropNext();
    }

    /**
     * Duplicates next sent message.
     */
    public void duplicateNextMessage() {
        this.network.duplicateNext();
    }

    /**
     * Reorders pending messages.
     */
    public void reorderPendingMessages() {
        this.network.reorderPending();
    }

    /**
     * Queues current leader replication messages without delivering them.
     */
    public void queueLeaderReplication() {
        ConsensusNode leader = this.currentLeader();
        if (leader != null) {
            this.network.sendAll(leader.replicateMessages());
        }
    }

    void sendDiagnosticMessage(final ConsensusMessage message) {
        this.network.send(message);
    }

    /**
     * Partitions the network.
     *
     * @param majority majority set
     * @param minority minority set
     */
    public void partition(final Set<ConsensusNodeId> majority, final Set<ConsensusNodeId> minority) {
        this.recordTrace("partition");
        this.network.partition(majority, minority);
        this.network.purgeDisconnectedMessages();
    }

    /**
     * Heals network partition.
     */
    public void heal() {
        this.recordTrace("heal");
        this.network.heal();
        ConsensusNode leader = this.currentLeader();
        if (leader != null) {
            this.network.sendAll(leader.replicateMessages());
            this.deliverAll();
        }
    }

    /**
     * Crashes a node.
     *
     * @param nodeId node id
     */
    public void crash(final ConsensusNodeId nodeId) {
        this.recordTrace("crash:" + nodeId.value());
        this.node(nodeId).crash();
        this.crashes++;
        this.recordClusterState();
    }

    /**
     * Restarts a node from durable state.
     *
     * @param nodeId node id
     */
    public void restart(final ConsensusNodeId nodeId) {
        this.recordTrace("restart:" + nodeId.value());
        ConsensusNode old = this.node(nodeId);
        ConsensusNode restarted = old.restart();
        this.nodes.put(nodeId, restarted);
        this.restarts++;
        this.recordClusterState();
    }

    /**
     * Delivers all pending messages.
     */
    public void deliverAll() {
        int guard = 10_000;
        while (this.network.hasMessages() && guard > 0) {
            guard--;
            ConsensusMessage message = this.network.poll();
            if (!this.validEnvelope(message)) {
                this.recordTrace("invalid-message");
                continue;
            }
            ConsensusNode recipient = this.nodes.get(message.to());
            if (recipient == null || recipient.crashed()) {
                continue;
            }
            if (message instanceof ConsensusMessage.VoteRequest voteRequest) {
                RequestVoteResponse response = recipient.onRequestVote(voteRequest.request());
                if (response != null) {
                    this.network.send(new ConsensusMessage.VoteResponse(message.to(), message.from(), response));
                }
            } else if (message instanceof ConsensusMessage.VoteResponse voteResponse) {
                this.network.sendAll(recipient.onRequestVoteResponse(voteResponse.response()));
            } else if (message instanceof ConsensusMessage.AppendRequest appendRequest) {
                AppendEntriesResponse response = recipient.onAppendEntries(appendRequest.request());
                if (response != null) {
                    this.network.send(new ConsensusMessage.AppendResponse(message.to(), message.from(), response));
                }
            } else if (message instanceof ConsensusMessage.AppendResponse appendResponse) {
                boolean retry = recipient.onAppendEntriesResponse(appendResponse.response());
                if (retry && recipient.role() == ConsensusRole.LEADER) {
                    this.network.send(new ConsensusMessage.AppendRequest(
                            recipient.nodeId(),
                            appendResponse.response().nodeId(),
                            recipient.appendRequestFor(appendResponse.response().nodeId())));
                }
            }
            this.recordClusterState();
        }
        if (guard == 0) {
            throw new IllegalStateException("consensus simulation did not quiesce");
        }
        this.recordClusterState();
    }

    /**
     * Returns current leader.
     *
     * @return leader or null
     */
    public ConsensusNode currentLeader() {
        for (ConsensusNode node : this.nodes.values()) {
            if (!node.crashed() && node.role() == ConsensusRole.LEADER) {
                return node;
            }
        }
        return null;
    }

    /**
     * Returns current leader id.
     *
     * @return leader id or null
     */
    public ConsensusNodeId currentLeaderId() {
        ConsensusNode leader = this.currentLeader();
        return leader == null ? null : leader.nodeId();
    }

    /**
     * Returns a node.
     *
     * @param nodeId node id
     * @return node
     */
    public ConsensusNode node(final ConsensusNodeId nodeId) {
        ConsensusNode node = this.nodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("unknown consensus node");
        }
        return node;
    }

    /**
     * Returns all nodes.
     *
     * @return nodes
     */
    public List<ConsensusNode> nodes() {
        return List.copyOf(this.nodes.values());
    }

    /**
     * Returns status.
     *
     * @return status
     */
    public ConsensusStatus status() {
        ConsensusMetricsSnapshot snapshot = this.metrics.snapshot();
        List<ConsensusState> states = new ArrayList<>(this.nodes.size());
        for (ConsensusNode node : this.nodes.values()) {
            states.add(node.state());
        }
        return new ConsensusStatus(this.config.configuration().groupId(), this.currentLeaderId(), states, snapshot, false, false);
    }

    /**
     * Runs safety checks.
     *
     * @return safety report
     */
    public ConsensusSafetyReport safetyReport() {
        ConsensusSafetyReport report = new SafetyInvariantChecker().check(
                this.nodes(),
                List.copyOf(this.staleLeaderEvidence),
                List.copyOf(this.history));
        if (!report.passed()) {
            this.metrics.invariantFailures++;
        }
        return report;
    }

    /**
     * Returns deterministic simulation report.
     *
     * @return simulation report
     */
    public ConsensusSimulationReport simulationReport() {
        return new ConsensusSimulationReport(
                this.seed,
                this.network.sent(),
                this.network.dropped(),
                this.network.duplicated(),
                this.network.reordered(),
                this.crashes,
                this.restarts,
                List.copyOf(this.trace),
                this.safetyReport());
    }

    /**
     * Records stale leader rejection as observed by a scenario.
     */
    public void markStaleLeaderRejectionObserved() {
        this.recordStaleLeaderEvidence("STALE_LEADER_REJECTED:explicit-observation");
    }

    /**
     * Returns fixed configuration.
     *
     * @return configuration
     */
    public ConsensusConfiguration configuration() {
        return this.config.configuration();
    }

    private void recordStaleLeaderEvidence(final String evidence) {
        if (this.staleLeaderEvidence.size() < EVIDENCE_LIMIT) {
            this.staleLeaderEvidence.add(evidence);
        }
        this.recordTrace("safety-evidence:" + evidence);
    }

    private boolean validEnvelope(final ConsensusMessage message) {
        if (message == null || message.from() == null || message.to() == null || !this.nodes.containsKey(message.to())) {
            return false;
        }
        if (message instanceof ConsensusMessage.VoteRequest voteRequest) {
            return message.from().equals(voteRequest.request().candidateId());
        }
        if (message instanceof ConsensusMessage.VoteResponse voteResponse) {
            return message.from().equals(voteResponse.response().nodeId());
        }
        if (message instanceof ConsensusMessage.AppendRequest appendRequest) {
            return message.from().equals(appendRequest.request().leaderId());
        }
        if (message instanceof ConsensusMessage.AppendResponse appendResponse) {
            return message.from().equals(appendResponse.response().nodeId());
        }
        return false;
    }

    private void recordTrace(final String event) {
        if (this.trace.size() < TRACE_LIMIT) {
            this.trace.add(event);
        } else if (!this.trace.get(this.trace.size() - 1).equals("trace-truncated")) {
            this.trace.set(this.trace.size() - 1, "trace-truncated");
        }
    }

    private void recordClusterState() {
        for (ConsensusNode node : this.nodes.values()) {
            if (!node.crashed() && node.role() == ConsensusRole.LEADER) {
                this.recordHistory(new ConsensusHistoryEvent(
                        ConsensusHistoryEvent.Type.LEADER_ELECTED,
                        node.nodeId(),
                        node.term(),
                        new LogIndex(0L),
                        ""));
            }
            for (ConsensusLogEntry entry : node.logEntries()) {
                if (entry.index().value() <= node.commitIndexValue()) {
                    this.recordHistory(new ConsensusHistoryEvent(
                            ConsensusHistoryEvent.Type.ENTRY_COMMITTED,
                            node.nodeId(),
                            entry.term(),
                            entry.index(),
                            entry.command().commandId()));
                }
            }
            for (ConsensusLogEntry entry : node.appliedEntries()) {
                this.recordHistory(new ConsensusHistoryEvent(
                        ConsensusHistoryEvent.Type.ENTRY_APPLIED,
                        node.nodeId(),
                        entry.term(),
                        entry.index(),
                        entry.command().commandId()));
            }
        }
    }

    private void recordHistory(final ConsensusHistoryEvent event) {
        if (this.history.size() < HISTORY_LIMIT) {
            this.history.add(event);
            return;
        }
        if (!this.historyTruncated) {
            this.historyTruncated = true;
            this.history.set(HISTORY_LIMIT - 1, new ConsensusHistoryEvent(
                    ConsensusHistoryEvent.Type.HISTORY_TRUNCATED,
                    null,
                    new ConsensusTerm(0L),
                    new LogIndex(0L),
                    ""));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        for (ConsensusNode node : this.nodes.values()) {
            node.close();
        }
    }
}
