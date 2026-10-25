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

import java.io.*;
import java.util.*;
import org.j7m.middleware.consensus.ReplicatedStateMachine;

/**
 * Quorum-replicated topology authority. Epoch CAS serializes handoffs; proofs are issued only for
 * durable cutovers. Trusted authority keys are unavailable to ordinary resource clients.
 * The topology is a deterministic state machine; hosting endpoints must authenticate administrative commands.
 */
public final class PartitionTopology implements ReplicatedStateMachine {
    private final byte[] authorityKey;
    private final TreeMap<String, Route> routes = new TreeMap<>();
    private final TreeMap<String, Handoff> handoffs = new TreeMap<>();
    private long applied;
    private final Map<Long, String> receipts = new LinkedHashMap<>();

    /**
     * Creates an empty topology authority.
     * @param authorityKey secret proof signing key, at least 32 bytes
     * @throws IllegalArgumentException for a short key
     */
    public PartitionTopology(byte[] authorityKey) {
        if (authorityKey.length < 32) throw new IllegalArgumentException("Short authority key");
        this.authorityKey = authorityKey.clone();
    }

    /**
     * Current ownership record.
     * @param partition partition identity
     * @param ownerGroup authoritative consensus group
     * @param epoch strictly increasing per-partition epoch
     */
    public record Route(String partition, String ownerGroup, long epoch) { }
    /**
     * Durable resumable handoff record.
     * @param id unique migration identity
     * @param partition partition identity
     * @param source old group
     * @param target new group
     * @param expectedEpoch original ownership epoch
     * @param phase COPYING, CUTOVER or COMPLETE
     * @param imageHash verified final image SHA-256
     */
    public record Handoff(String id, String partition, String source, String target, long expectedEpoch,
            String phase, String imageHash) { }

    /**
     * Returns an ownership route after the caller established a quorum barrier.
     * @param partition partition identity
     * @param barrier applied quorum read index
     * @return route, or null if unregistered
     * @throws IllegalStateException for an unapplied barrier
     */
    public synchronized Route route(String partition, long barrier) { barrier(barrier); return routes.get(partition); }
    /**
     * Returns durable migration progress after a quorum barrier.
     * @param id migration identity
     * @param barrier applied quorum read index
     * @return handoff, or null if absent
     */
    public synchronized Handoff handoff(String id, long barrier) { barrier(barrier); return handoffs.get(id); }
    /**
     * Issues a target activation proof only after a durable ownership cutover.
     * @param id committed handoff identity
     * @param barrier applied quorum read index
     * @return HMAC proof bound to target, epoch and verified image
     * @throws IllegalStateException when cutover is absent
     */
    public synchronized byte[] activationProof(String id, long barrier) {
        barrier(barrier); Handoff handoff = handoffs.get(id);
        if (handoff == null || handoff.phase.equals("COPYING")) throw new IllegalStateException("Cutover not committed");
        Route current = routes.get(handoff.partition);
        if (!current.ownerGroup.equals(handoff.target) || current.epoch != handoff.expectedEpoch + 1)
            throw new IllegalStateException("Handoff no longer owns partition");
        return PartitionStateMachine.proof(authorityKey, handoff.partition, handoff.target,
                current.epoch, id, handoff.imageHash);
    }

    /**
     * {@inheritDoc}
     */
    @Override public synchronized void apply(long index, byte[] command) {
        try {
            DataInputStream in = PartitionStateMachine.input(command); int operation = in.readUnsignedByte(); String outcome;
            if (operation == 1) {
                String partition = in.readUTF(); String owner = in.readUTF(); PartitionStateMachine.end(in);
                PartitionStateMachine.identity(partition); PartitionStateMachine.identity(owner);
                Route existing = routes.get(partition);
                outcome = existing == null && routes.size() >= 100000 ? "OVERLOADED"
                        : existing == null || existing.equals(new Route(partition, owner, 1)) ? "OK" : "CONFLICT";
                if (existing == null && outcome.equals("OK")) routes.put(partition, new Route(partition, owner, 1));
            } else if (operation == 2) {
                String id = in.readUTF(); String partition = in.readUTF(); String target = in.readUTF(); long epoch = in.readLong(); PartitionStateMachine.end(in);
                PartitionStateMachine.identity(id); PartitionStateMachine.identity(partition); PartitionStateMachine.identity(target);
                Route route = routes.get(partition); Handoff existing = handoffs.get(id);
                outcome = existing != null ? existing.partition.equals(partition) && existing.target.equals(target)
                        && existing.expectedEpoch == epoch ? "OK" : "CONFLICT"
                        : handoffs.size() >= 100000 ? "OVERLOADED" : route == null || route.epoch != epoch || route.ownerGroup.equals(target)
                        || handoffs.values().stream().anyMatch(h -> h.partition.equals(partition) && !h.phase.equals("COMPLETE")) ? "CONFLICT" : "OK";
                if (outcome.equals("OK") && existing == null) handoffs.put(id, new Handoff(id, partition, route.ownerGroup, target, epoch, "COPYING", ""));
            } else if (operation == 3) {
                String id = in.readUTF(); long epoch = in.readLong(); String hash = in.readUTF(); PartitionStateMachine.end(in);
                if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid digest");
                Handoff h = handoffs.get(id); Route route = h == null ? null : routes.get(h.partition);
                outcome = h == null || h.expectedEpoch != epoch ? "CONFLICT" : !h.phase.equals("COPYING")
                        ? h.imageHash.equals(hash) ? "OK" : "CONFLICT"
                        : route.epoch != epoch || !route.ownerGroup.equals(h.source) ? "CONFLICT" : "OK";
                if (outcome.equals("OK") && h.phase.equals("COPYING")) {
                    routes.put(h.partition, new Route(h.partition, h.target, Math.addExact(epoch, 1)));
                    handoffs.put(id, new Handoff(id, h.partition, h.source, h.target, epoch, "CUTOVER", hash));
                }
            } else if (operation == 4) {
                String id = in.readUTF(); PartitionStateMachine.end(in); Handoff h = handoffs.get(id);
                outcome = h == null || h.phase.equals("COPYING") ? "CONFLICT" : "OK";
                if (outcome.equals("OK")) handoffs.put(id, new Handoff(id, h.partition, h.source, h.target, h.expectedEpoch, "COMPLETE", h.imageHash));
            } else throw new IllegalArgumentException("Unknown topology operation");
            applied = index; receipts.put(index, outcome); while (receipts.size() > 4096) receipts.remove(receipts.keySet().iterator().next());
        } catch (IOException invalid) { throw new IllegalArgumentException("Invalid topology command", invalid); }
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void appliedThrough(long index) { applied = index; }
    /**
     * Returns a bounded command receipt.
     * @param index committed position
     * @return OK, CONFLICT or UNKNOWN
     */
    public synchronized String receipt(long index) { return receipts.getOrDefault(index, "UNKNOWN"); }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized byte[] snapshot() {
        return PartitionStateMachine.encode(out -> { out.writeInt(1); out.writeLong(applied); out.writeInt(routes.size());
            for (Route route : routes.values()) { out.writeUTF(route.partition); out.writeUTF(route.ownerGroup); out.writeLong(route.epoch); }
            out.writeInt(handoffs.size()); for (Handoff h : handoffs.values()) { out.writeUTF(h.id); out.writeUTF(h.partition);
                out.writeUTF(h.source); out.writeUTF(h.target); out.writeLong(h.expectedEpoch); out.writeUTF(h.phase); out.writeUTF(h.imageHash); } });
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void restore(byte[] snapshot) {
        try {
            DataInputStream in = PartitionStateMachine.input(snapshot); if (in.readInt() != 1) throw new IOException("Unsupported topology snapshot");
            long restored = in.readLong(); int count = count(in); TreeMap<String, Route> newRoutes = new TreeMap<>();
            for (int n = 0; n < count; n++) { String partition = in.readUTF(); String owner = in.readUTF(); long epoch = in.readLong();
                PartitionStateMachine.identity(partition); PartitionStateMachine.identity(owner); if (epoch <= 0 || newRoutes.put(partition, new Route(partition, owner, epoch)) != null) throw new IOException("Invalid route"); }
            count = count(in); TreeMap<String, Handoff> newHandoffs = new TreeMap<>();
            for (int n = 0; n < count; n++) { Handoff h = new Handoff(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readLong(), in.readUTF(), in.readUTF());
                if (!newRoutes.containsKey(h.partition) || !Set.of("COPYING", "CUTOVER", "COMPLETE").contains(h.phase)
                        || newHandoffs.put(h.id, h) != null) throw new IOException("Invalid handoff"); }
            PartitionStateMachine.end(in); if (restored < 0) throw new IOException("Invalid position");
            routes.clear(); routes.putAll(newRoutes); handoffs.clear(); handoffs.putAll(newHandoffs); applied = restored; receipts.clear();
        } catch (IOException invalid) { throw new IllegalArgumentException("Invalid topology snapshot", invalid); }
    }
    synchronized void validateControl(byte[] command) {
        PartitionTopology validation = new PartitionTopology(authorityKey); validation.restore(snapshot()); validation.apply(applied + 1, command);
    }
    private static int count(DataInputStream in) throws IOException { int value = in.readInt(); if (value < 0 || value > 100000) throw new IOException("Invalid count"); return value; }
    private void barrier(long index) { if (index > applied) throw new IllegalStateException("Unapplied topology barrier"); }
    static byte[] register(String partition, String group) { PartitionStateMachine.identity(partition); PartitionStateMachine.identity(group);
        return PartitionStateMachine.encode(out -> { out.writeByte(1); out.writeUTF(partition); out.writeUTF(group); }); }
    static byte[] begin(String id, String partition, String target, long epoch) { PartitionStateMachine.identity(id); PartitionStateMachine.identity(partition); PartitionStateMachine.identity(target);
        return PartitionStateMachine.encode(out -> { out.writeByte(2); out.writeUTF(id); out.writeUTF(partition); out.writeUTF(target); out.writeLong(epoch); }); }
    static byte[] cutover(String id, long epoch, String hash) { return PartitionStateMachine.encode(out -> { out.writeByte(3); out.writeUTF(id); out.writeLong(epoch); out.writeUTF(hash); }); }
    static byte[] complete(String id) { return PartitionStateMachine.encode(out -> { out.writeByte(4); out.writeUTF(id); }); }
}
