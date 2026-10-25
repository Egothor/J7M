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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import org.j7m.middleware.api.consensus.ConsensusNodeId;

/**
 * Deterministic in-process network for consensus simulation.
 */
public final class InMemoryConsensusNetwork {
    private static final int DEFAULT_PENDING_MESSAGE_LIMIT = 4_096;
    private final Queue<ConsensusMessage> messages;
    private final Map<ConsensusNodeId, Integer> partitions;
    private final int pendingMessageLimit;
    private long sent;
    private long dropped;
    private long duplicated;
    private long reordered;
    private boolean dropNext;
    private boolean duplicateNext;

    /**
     * Creates a network.
     */
    public InMemoryConsensusNetwork() {
        this(DEFAULT_PENDING_MESSAGE_LIMIT);
    }

    /**
     * Creates a network with an explicit hard cap for pending messages.
     *
     * @param pendingMessageLimit maximum number of queued messages
     */
    public InMemoryConsensusNetwork(final int pendingMessageLimit) {
        if (pendingMessageLimit <= 0) {
            throw new IllegalArgumentException("pending message limit must be positive");
        }
        this.messages = new ArrayDeque<>();
        this.partitions = new HashMap<>();
        this.pendingMessageLimit = pendingMessageLimit;
    }

    /**
     * Sends a message subject to deterministic drop/duplicate/partition rules.
     *
     * @param message message
     */
    public void send(final ConsensusMessage message) {
        if (message == null) {
            return;
        }
        this.sent++;
        if (!this.connected(message.from(), message.to()) || this.dropNext) {
            this.dropped++;
            this.dropNext = false;
            return;
        }
        this.enqueue(message);
        if (this.duplicateNext) {
            this.enqueue(message);
            this.duplicated++;
            this.duplicateNext = false;
        }
    }

    /**
     * Sends all messages.
     *
     * @param outgoing outgoing messages
     */
    public void sendAll(final List<ConsensusMessage> outgoing) {
        for (ConsensusMessage message : outgoing) {
            this.send(message);
        }
    }

    /**
     * Polls next message.
     *
     * @return next message or null
     */
    public ConsensusMessage poll() {
        return this.messages.poll();
    }

    /**
     * Returns true when messages are pending.
     *
     * @return true when pending
     */
    public boolean hasMessages() {
        return !this.messages.isEmpty();
    }

    /**
     * Drops the next sent message.
     */
    public void dropNext() {
        this.dropNext = true;
    }

    /**
     * Duplicates the next sent message.
     */
    public void duplicateNext() {
        this.duplicateNext = true;
    }

    /**
     * Reorders the pending queue by moving the last message to the front.
     */
    public void reorderPending() {
        if (this.messages.size() <= 1) {
            return;
        }
        List<ConsensusMessage> copy = new ArrayList<>(this.messages);
        this.messages.clear();
        this.messages.add(copy.get(copy.size() - 1));
        for (int index = 0; index < copy.size() - 1; index++) {
            this.messages.add(copy.get(index));
        }
        this.reordered++;
    }

    /**
     * Partitions nodes into majority and minority components.
     *
     * @param majority majority ids
     * @param minority minority ids
     */
    public void partition(final Set<ConsensusNodeId> majority, final Set<ConsensusNodeId> minority) {
        this.partitions.clear();
        for (ConsensusNodeId nodeId : majority) {
            this.partitions.put(nodeId, 1);
        }
        for (ConsensusNodeId nodeId : minority) {
            this.partitions.put(nodeId, 2);
        }
    }

    /**
     * Clears all partitions.
     */
    public void heal() {
        this.partitions.clear();
    }

    /**
     * Drops messages currently queued across partitions.
     */
    public void purgeDisconnectedMessages() {
        List<ConsensusMessage> kept = new ArrayList<>();
        while (!this.messages.isEmpty()) {
            ConsensusMessage message = this.messages.poll();
            if (this.connected(message.from(), message.to())) {
                kept.add(message);
            } else {
                this.dropped++;
            }
        }
        this.messages.addAll(kept);
    }

    /**
     * Returns sent count.
     *
     * @return count
     */
    public long sent() {
        return this.sent;
    }

    /**
     * Returns dropped count.
     *
     * @return count
     */
    public long dropped() {
        return this.dropped;
    }

    /**
     * Returns duplicated count.
     *
     * @return count
     */
    public long duplicated() {
        return this.duplicated;
    }

    /**
     * Returns reordered count.
     *
     * @return count
     */
    public long reordered() {
        return this.reordered;
    }

    private boolean connected(final ConsensusNodeId left, final ConsensusNodeId right) {
        Integer leftPartition = this.partitions.get(left);
        Integer rightPartition = this.partitions.get(right);
        return leftPartition == null || rightPartition == null || leftPartition.equals(rightPartition);
    }

    /**
     * Returns nodes currently in isolated partitions.
     *
     * @return partitioned node ids
     */
    public Set<ConsensusNodeId> partitionedNodes() {
        return new HashSet<>(this.partitions.keySet());
    }

    private void enqueue(final ConsensusMessage message) {
        if (this.messages.size() >= this.pendingMessageLimit) {
            this.dropped++;
            return;
        }
        this.messages.add(message);
    }
}
