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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.j7m.middleware.api.consensus.ConsensusLogEntry;
import org.j7m.middleware.api.consensus.ConsensusNodeId;
import org.j7m.middleware.api.consensus.ConsensusRole;
import org.j7m.middleware.api.consensus.ConsensusSafetyReport;

/**
 * Checks Raft-like safety invariants over deterministic simulation state.
 */
public final class SafetyInvariantChecker {
    /**
     * Creates a checker.
     */
    public SafetyInvariantChecker() {
    }

    /**
     * Checks all supported invariants.
     *
     * @param nodes nodes
     * @param staleLeaderEvidence safe stale-leader rejection evidence records
     * @return safety report
     */
    public ConsensusSafetyReport check(final List<ConsensusNode> nodes, final List<String> staleLeaderEvidence) {
        return this.check(nodes, staleLeaderEvidence, List.of());
    }

    /**
     * Checks all supported invariants using current node state and bounded
     * historical transition evidence.
     *
     * @param nodes nodes
     * @param staleLeaderEvidence safe stale-leader rejection evidence records
     * @param history bounded transition history
     * @return safety report
     */
    public ConsensusSafetyReport check(
            final List<ConsensusNode> nodes,
            final List<String> staleLeaderEvidence,
            final List<ConsensusHistoryEvent> history) {
        List<String> violations = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        boolean historyComplete = this.checkHistoryCompleteness(history, violations);
        Map<Long, CommittedBaselineEntry> committedBaseline = this.committedBaseline(nodes, history, violations);
        boolean electionSafety = this.checkElectionSafety(nodes, history, violations);
        boolean leaderAppendOnly = this.checkLeaderAppendOnly(nodes, violations);
        boolean logMatching = this.checkLogMatching(nodes, violations);
        boolean leaderCompleteness = this.checkLeaderCompleteness(nodes, committedBaseline, violations);
        boolean stateMachineSafety = this.checkStateMachineSafety(nodes, history, violations);
        boolean committedDurability = this.checkCommittedDurability(nodes, committedBaseline, violations);
        boolean staleLeader = staleLeaderEvidence != null && !staleLeaderEvidence.isEmpty();
        if (staleLeader) {
            evidence.addAll(staleLeaderEvidence);
        } else {
            violations.add("stale leader rejection evidence was not observed");
        }
        return new ConsensusSafetyReport(
                electionSafety,
                leaderAppendOnly,
                logMatching,
                leaderCompleteness,
                stateMachineSafety && historyComplete,
                committedDurability && historyComplete,
                staleLeader,
                evidence,
                violations);
    }

    private boolean checkHistoryCompleteness(final List<ConsensusHistoryEvent> history, final List<String> violations) {
        for (ConsensusHistoryEvent event : history) {
            if (event.type() == ConsensusHistoryEvent.Type.HISTORY_TRUNCATED) {
                violations.add("consensus safety history exceeded configured cap");
                return false;
            }
        }
        return true;
    }

    private boolean checkElectionSafety(
            final List<ConsensusNode> nodes,
            final List<ConsensusHistoryEvent> history,
            final List<String> violations) {
        Map<Long, Set<ConsensusNodeId>> leadersByTerm = new LinkedHashMap<>();
        for (ConsensusHistoryEvent event : history) {
            if (event.type() == ConsensusHistoryEvent.Type.LEADER_ELECTED && event.nodeId() != null) {
                Set<ConsensusNodeId> leaders = leadersByTerm.computeIfAbsent(event.term().value(), ignored -> new LinkedHashSet<>());
                leaders.add(event.nodeId());
            }
        }
        if (leadersByTerm.isEmpty()) {
            for (ConsensusNode node : nodes) {
                if (!node.crashed() && node.role() == ConsensusRole.LEADER) {
                    Set<ConsensusNodeId> leaders = leadersByTerm.computeIfAbsent(node.term().value(), ignored -> new LinkedHashSet<>());
                    leaders.add(node.nodeId());
                }
            }
        }
        for (Map.Entry<Long, Set<ConsensusNodeId>> entry : leadersByTerm.entrySet()) {
            if (entry.getValue().size() > 1) {
                violations.add("multiple leaders in term " + entry.getKey());
                return false;
            }
        }
        return true;
    }

    private boolean checkLeaderAppendOnly(final List<ConsensusNode> nodes, final List<String> violations) {
        for (ConsensusNode node : nodes) {
            if (!node.leaderAppendOnlyHeld()) {
                violations.add("leader append-only failed for " + node.nodeId().value());
                return false;
            }
        }
        return true;
    }

    private boolean checkLogMatching(final List<ConsensusNode> nodes, final List<String> violations) {
        for (ConsensusNode left : nodes) {
            for (ConsensusNode right : nodes) {
                long max = Math.min(left.logEntries().size(), right.logEntries().size());
                for (long index = 1L; index <= max; index++) {
                    ConsensusLogEntry leftEntry = left.logEntries().get((int) index - 1);
                    ConsensusLogEntry rightEntry = right.logEntries().get((int) index - 1);
                    if (leftEntry.term().equals(rightEntry.term())) {
                        for (long prefix = 1L; prefix <= index; prefix++) {
                            ConsensusLogEntry leftPrefix = left.logEntries().get((int) prefix - 1);
                            ConsensusLogEntry rightPrefix = right.logEntries().get((int) prefix - 1);
                            if (!leftPrefix.command().commandId().equals(rightPrefix.command().commandId())
                                    || !leftPrefix.term().equals(rightPrefix.term())) {
                                violations.add("log matching failed at index " + index);
                                return false;
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

    private boolean checkLeaderCompleteness(
            final List<ConsensusNode> nodes,
            final Map<Long, CommittedBaselineEntry> committedBaseline,
            final List<String> violations) {
        for (ConsensusNode node : nodes) {
            if (!node.crashed() && node.role() == ConsensusRole.LEADER) {
                for (Map.Entry<Long, CommittedBaselineEntry> entry : committedBaseline.entrySet()) {
                    if (node.logEntries().size() < entry.getKey()) {
                        violations.add("leader missing committed entry " + entry.getKey());
                        return false;
                    }
                    ConsensusLogEntry actual = node.logEntries().get(entry.getKey().intValue() - 1);
                    if (!sameCommittedEntry(actual, entry.getValue())) {
                        violations.add("leader completeness failed at " + entry.getKey());
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean checkStateMachineSafety(
            final List<ConsensusNode> nodes,
            final List<ConsensusHistoryEvent> history,
            final List<String> violations) {
        Map<Long, String> applied = new LinkedHashMap<>();
        for (ConsensusHistoryEvent event : history) {
            if (event.type() == ConsensusHistoryEvent.Type.ENTRY_APPLIED) {
                String previous = applied.putIfAbsent(event.index().value(), event.commandId());
                if (previous != null && !previous.equals(event.commandId())) {
                    violations.add("state machine safety failed at " + event.index().value());
                    return false;
                }
            }
        }
        for (ConsensusNode node : nodes) {
            for (ConsensusLogEntry entry : node.appliedEntries()) {
                String previous = applied.putIfAbsent(entry.index().value(), entry.command().commandId());
                if (previous != null && !previous.equals(entry.command().commandId())) {
                    violations.add("state machine safety failed at " + entry.index().value());
                    return false;
                }
            }
        }
        return true;
    }

    private boolean checkCommittedDurability(
            final List<ConsensusNode> nodes,
            final Map<Long, CommittedBaselineEntry> committedBaseline,
            final List<String> violations) {
        for (Map.Entry<Long, CommittedBaselineEntry> entry : committedBaseline.entrySet()) {
            boolean matchingLogFound = false;
            boolean committedLogFound = false;
            for (ConsensusNode node : nodes) {
                if (node.logEntries().size() >= entry.getKey()) {
                    ConsensusLogEntry actual = node.logEntries().get(entry.getKey().intValue() - 1);
                    if (sameCommittedEntry(actual, entry.getValue())) {
                        matchingLogFound = true;
                    } else if (node.commitIndexValue() >= entry.getKey()) {
                        violations.add("committed entry durability failed at " + entry.getKey());
                        return false;
                    }
                } else if (node.commitIndexValue() >= entry.getKey()) {
                    violations.add("committed entry durability failed at " + entry.getKey());
                    return false;
                }
                if (node.commitIndexValue() >= entry.getKey()) {
                    committedLogFound = true;
                }
            }
            if (!matchingLogFound) {
                violations.add("historical committed entry absent at " + entry.getKey());
                return false;
            }
            if (!committedLogFound) {
                violations.add("historical committed entry no longer committed at " + entry.getKey());
                return false;
            }
        }
        return true;
    }

    private Map<Long, CommittedBaselineEntry> committedBaseline(
            final List<ConsensusNode> nodes,
            final List<ConsensusHistoryEvent> history,
            final List<String> violations) {
        Map<Long, CommittedBaselineEntry> committed = new LinkedHashMap<>();
        for (ConsensusHistoryEvent event : history) {
            if (event.type() == ConsensusHistoryEvent.Type.ENTRY_COMMITTED) {
                CommittedBaselineEntry entry = new CommittedBaselineEntry(event.term().value(), event.commandId());
                CommittedBaselineEntry previous = committed.putIfAbsent(event.index().value(), entry);
                if (previous != null && !previous.equals(entry)) {
                    violations.add("committed entry history diverged at " + event.index().value());
                }
            }
        }
        for (ConsensusNode node : nodes) {
            for (long index = 1L; index <= node.commitIndexValue() && index <= node.logEntries().size(); index++) {
                ConsensusLogEntry actual = node.logEntries().get((int) index - 1);
                CommittedBaselineEntry entry = new CommittedBaselineEntry(actual.term().value(), actual.command().commandId());
                CommittedBaselineEntry previous = committed.putIfAbsent(index, entry);
                if (previous != null && !previous.equals(entry)) {
                    violations.add("terminal committed entry conflicts with baseline at " + index);
                }
            }
        }
        return committed;
    }

    private static boolean sameCommittedEntry(final ConsensusLogEntry entry, final CommittedBaselineEntry baseline) {
        return entry.term().value() == baseline.term()
                && entry.command().commandId().equals(baseline.commandId());
    }

    private record CommittedBaselineEntry(long term, String commandId) {
    }
}
