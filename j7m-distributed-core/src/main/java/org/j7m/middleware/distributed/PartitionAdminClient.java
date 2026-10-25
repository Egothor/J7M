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
import java.time.Instant;
import java.util.*;

/**
 * Privileged topology and transfer API. The supplied client must authenticate a separately configured
 * administrative principal. Transfer data is bounded, authenticated and SHA-256 verified; all target
 * chunks and phase transitions are quorum durable before successful return.
 */
public final class PartitionAdminClient {
    private final PartitionClient client;
    /**
     * Creates an administration facade without changing its immutable principal.
     * @param client separately authenticated administrative client
     */
    public PartitionAdminClient(PartitionClient client) { this.client = Objects.requireNonNull(client); }
    /**
     * Registers an initial route idempotently.
     * @param partition stable identity
     * @param group initial owner
     * @param deadline absolute deadline
     * @throws IOException on unavailable quorum, conflicting route or unknown outcome
     */
    public void register(String partition, String group, Instant deadline) throws IOException { topology(PartitionTopology.register(partition, group), deadline); }
    /**
     * Reads the authoritative route.
     * @param partition partition identity
     * @param deadline absolute deadline
     * @return current owner and epoch
     * @throws IOException on unavailable quorum
     */
    public PartitionTopology.Route route(String partition, Instant deadline) throws IOException {
        DataInputStream in = PartitionStateMachine.input(client.exchange("ROUTE", text(partition), deadline));
        var route = new PartitionTopology.Route(in.readUTF(), in.readUTF(), in.readLong()); PartitionStateMachine.end(in); return route;
    }
    /**
     * Persists a handoff intent using an ownership epoch CAS.
     * @param id stable idempotent migration identity
     * @param partition partition identity
     * @param target destination group
     * @param expectedEpoch current topology epoch
     * @param deadline absolute deadline
     * @throws IOException on conflict or unavailable quorum
     */
    public void beginHandoff(String id, String partition, String target, long expectedEpoch, Instant deadline) throws IOException {
        topology(PartitionTopology.begin(id, partition, target, expectedEpoch), deadline);
    }
    /**
     * Reads durable handoff progress.
     * @param id handoff identity
     * @param deadline absolute deadline
     * @return handoff or null
     * @throws IOException on unavailable quorum
     */
    public PartitionTopology.Handoff handoff(String id, Instant deadline) throws IOException {
        byte[] body = client.exchange("HANDOFF", text(id), deadline); if (body.length == 0) return null;
        DataInputStream in = PartitionStateMachine.input(body);
        var handoff = new PartitionTopology.Handoff(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readLong(), in.readUTF(), in.readUTF()); PartitionStateMachine.end(in); return handoff;
    }
    /**
     * Reads local durable partition status through quorum.
     * @param deadline absolute deadline
     * @return local ownership state
     * @throws IOException on unavailable quorum
     */
    public PartitionStateMachine.Status status(Instant deadline) throws IOException { return client.status(deadline); }
    /**
     * Pins a snapshot barrier while permitting concurrent writes into the bounded tail.
     * @param epoch source ownership epoch
     * @param id handoff identity
     * @param deadline absolute deadline
     * @throws IOException for prepared transactions, stale epoch or unavailable quorum
     */
    public void snapshotAt(long epoch, String id, Instant deadline) throws IOException { control(PartitionStateMachine.freeze(epoch, id), deadline); }
    /**
     * Downloads the immutable pinned image in authenticated bounded chunks.
     * @param id handoff identity
     * @param deadline absolute deadline
     * @return SHA-256 verified complete image
     * @throws IOException for damaged/inconsistent stream or unavailable source
     */
    public byte[] image(String id, Instant deadline) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(); String digest = null; int total = -1;
        do {
            int offset = result.size(); byte[] request = PartitionStateMachine.encode(out -> { out.writeUTF(id); out.writeInt(offset); out.writeInt(128 * 1024); });
            DataInputStream in = PartitionStateMachine.input(client.exchange("IMAGE", request, deadline));
            int size = in.readInt(); String hash = in.readUTF(); byte[] chunk = PartitionStateMachine.bytes(in, 128 * 1024); PartitionStateMachine.end(in);
            if (size < 1 || size > PartitionStateMachine.MAX_IMAGE || (total != -1 && (total != size || !Objects.equals(digest, hash)))
                    || chunk.length == 0 || chunk.length > size - result.size()) throw new IOException("Invalid image stream");
            total = size; digest = hash; result.write(chunk);
        } while (result.size() < total);
        byte[] image = result.toByteArray(); if (!PartitionStateMachine.sha256(image).equals(digest)) throw new IOException("Image digest mismatch"); return image;
    }
    /**
     * Resumes a quorum-durable streamed installation at its stored byte offset.
     * @param id handoff identity
     * @param nextEpoch target ownership epoch
     * @param image verified full source image
     * @param deadline absolute deadline
     * @throws IOException on inconsistent target, stream failure or unknown outcome
     */
    public void install(String id, long nextEpoch, byte[] image, Instant deadline) throws IOException {
        String hash = PartitionStateMachine.sha256(image);
        if (status(deadline).phase().equals("STAGED")) return;
        DataInputStream in = PartitionStateMachine.input(client.exchange("UPLOAD_OFFSET", new byte[0], deadline)); int offset = in.readInt(); PartitionStateMachine.end(in);
        if (offset < 0 || offset > image.length) throw new IOException("Invalid installation offset");
        while (offset < image.length) {
            byte[] chunk = Arrays.copyOfRange(image, offset, Math.min(image.length, offset + 128 * 1024));
            control(PartitionStateMachine.chunk(id, nextEpoch, hash, image.length, offset, chunk), deadline); offset += chunk.length;
        }
    }
    /**
     * Reads an ordered bounded command suffix.
     * @param after exclusive logical resource index
     * @param deadline absolute deadline
     * @return immutable suffix with source committed terms
     * @throws IOException on unavailable source
     * @throws IllegalArgumentException with CURSOR_EXPIRED if the pinned barrier fell behind retention
     */
    public List<PartitionStateMachine.TailEntry> tail(long after, Instant deadline) throws IOException {
        byte[] request = PartitionStateMachine.encode(out -> { out.writeLong(after); out.writeInt(1000); });
        DataInputStream in = PartitionStateMachine.input(client.exchange("TAIL", request, deadline));
        if (in.readUnsignedByte() != 5) throw new IOException("Invalid tail frame"); in.readUTF(); int count = in.readInt();
        if (count < 0 || count > 1000) throw new IOException("Invalid tail size"); List<PartitionStateMachine.TailEntry> entries = new ArrayList<>();
        for (int n = 0; n < count; n++) entries.add(new PartitionStateMachine.TailEntry(in.readLong(), in.readLong(), PartitionStateMachine.bytes(in, PartitionStateMachine.MAX_COMMAND)));
        PartitionStateMachine.end(in); return List.copyOf(entries);
    }
    /**
     * Commits a verified consecutive suffix on a staged target.
     * @param id handoff identity
     * @param entries source ordered records
     * @param deadline absolute deadline
     * @throws IOException on conflict, gap or unavailable target
     */
    public void catchUp(String id, List<PartitionStateMachine.TailEntry> entries, Instant deadline) throws IOException { control(PartitionStateMachine.catchup(id, entries), deadline); }
    /**
     * Briefly freezes source writes after background catch-up; prepared work blocks this transition.
     * @param epoch source ownership epoch
     * @param id handoff identity
     * @param deadline absolute deadline
     * @throws IOException on prepared work, conflict or unavailable source
     */
    public void freeze(long epoch, String id, Instant deadline) throws IOException { control(PartitionStateMachine.freezeFinal(epoch, id), deadline); }
    /**
     * Reads the full frozen image digest for final catch-up verification.
     * @param id handoff identity
     * @param deadline absolute deadline
     * @return verified final image SHA-256
     * @throws IOException on unavailable or unfrozen source
     */
    public String finalHash(String id, Instant deadline) throws IOException { return decodeText(client.exchange("FINAL_HASH", text(id), deadline)); }
    /**
     * Atomically changes authoritative owner after caller verified the frozen source and target images.
     * @param id handoff identity
     * @param expectedEpoch source epoch CAS
     * @param hash identical source/target full image SHA-256
     * @param deadline absolute deadline
     * @throws IOException for conflicting epoch or unavailable topology
     */
    public void cutover(String id, long expectedEpoch, String hash, Instant deadline) throws IOException { topology(PartitionTopology.cutover(id, expectedEpoch, hash), deadline); }
    /**
     * Obtains a proof issued only after committed cutover.
     * @param id handoff identity
     * @param deadline absolute deadline
     * @return activation proof bound to target/epoch/image
     * @throws IOException on unavailable topology
     */
    public byte[] proof(String id, Instant deadline) throws IOException { return client.exchange("PROOF", text(id), deadline); }
    /**
     * Activates the staged target only with a valid committed topology proof.
     * @param epoch target ownership epoch
     * @param id handoff identity
     * @param hash verified staged full image SHA-256
     * @param proof topology authority proof
     * @param deadline absolute deadline
     * @throws IOException on invalid proof, conflicting metadata or unavailable target
     */
    public void activate(long epoch, String id, String hash, byte[] proof, Instant deadline) throws IOException { control(PartitionStateMachine.activate(epoch, id, hash, proof), deadline); }
    /**
     * Persists completed recovery state after target activation.
     * @param id handoff identity
     * @param deadline absolute deadline
     * @throws IOException on conflict or unavailable topology
     */
    public void complete(String id, Instant deadline) throws IOException { topology(PartitionTopology.complete(id), deadline); }
    /**
     * Resets an unactivated target or repins the source after bounded suffix retention expired.
     * Never permitted on an active target; the coordinator must still verify its topology handoff.
     * @param id current handoff identity
     * @param deadline absolute deadline
     * @throws IOException on conflicting phase or unavailable quorum
     */
    public void restartImage(String id, Instant deadline) throws IOException { control(PartitionStateMachine.reset(id), deadline); }
    private void control(byte[] command, Instant deadline) throws IOException { requireOk(client.exchange("PARTITION_COMMAND", command, deadline)); }
    private void topology(byte[] command, Instant deadline) throws IOException { requireOk(client.exchange("TOPOLOGY_COMMAND", command, deadline)); }
    private static byte[] text(String text) { return PartitionStateMachine.encode(out -> out.writeUTF(text)); }
    private static String decodeText(byte[] body) throws IOException { DataInputStream in = PartitionStateMachine.input(body); String value = in.readUTF(); PartitionStateMachine.end(in); return value; }
    private static void requireOk(byte[] response) throws IOException { String value = decodeText(response); if (!value.equals("OK")) throw new IOException("Partition transition rejected: " + value); }
}
