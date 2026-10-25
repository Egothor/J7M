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
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Serialized ownership fence around one complete transferable resource partition.
 * Every application mutation must traverse this wrapper; a raw delegate endpoint defeats ownership.
 * Freeze, installation and activation are durable consensus commands, never local assignments.
 * Logical resource positions and leadership epochs are independent of destination Raft positions:
 * each mutation advances its resource index and uses max(transferred effective term, committed local
 * term). Both values and original suffix contexts survive transfer and replay, so a destination with
 * a lower Raft term cannot invalidate transferred leases. Ownership fencing separately prevents the
 * old group from accepting mutations. This class is thread-safe; snapshots retain recovery metadata.
 */
public final class PartitionStateMachine implements org.j7m.middleware.consensus.ReplicatedStateMachine {
    static final int MAX_IMAGE = 128 * 1024 * 1024;
    static final int MAX_COMMAND = 128 * 1024;
    private static final int MAGIC = 0x50303831;
    private final TransferableResource resource;
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> hostChanges = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final String partition;
    private final String group;
    private final byte[] authorityKey;
    private long epoch;
    private long logicalIndex;
    private long logicalTerm;
    private long appliedIndex;
    private String phase;
    private String handoff = "";
    private String imageHash = "";
    private byte[] pinnedImage = new byte[0];
    private byte[] upload = new byte[0];
    private String uploadId = "";
    private String uploadHash = "";
    private int uploadTotal;
    private final ArrayDeque<TailEntry> tail = new ArrayDeque<>();
    private int tailBytes;
    private final LinkedHashMap<Long, String> receipts = new LinkedHashMap<>();

    /**
     * Creates a host for one partition; inactive targets start with epoch zero.
     * @param partition stable partition identity
     * @param group local consensus group identity
     * @param initialEpoch positive active epoch, or zero for an empty inactive destination
     * @param resource complete transferable resource, not shared with another host
     * @param authorityKey at least 32 secret bytes used solely by trusted topology authority
     * @throws IllegalArgumentException for invalid identities, epoch or key
     */
    public PartitionStateMachine(String partition, String group, long initialEpoch,
            TransferableResource resource, byte[] authorityKey) {
        identity(partition); identity(group);
        if (initialEpoch < 0 || authorityKey.length < 32) throw new IllegalArgumentException("Invalid epoch/key");
        this.partition = partition; this.group = group; this.epoch = initialEpoch;
        this.resource = Objects.requireNonNull(resource); this.authorityKey = authorityKey.clone();
        phase = initialEpoch == 0 ? "EMPTY" : "ACTIVE";
        if (initialEpoch > 0) resource.ownershipChanged(partition, initialEpoch);
    }

    /**
     * Returns durable ownership state.
     * @return immutable partition status
     */
    public synchronized Status status() {
        return new Status(partition, group, epoch, phase, handoff, imageHash, logicalIndex);
    }

    /**
     * Ownership and migration state.
     * @param partition partition identity
     * @param group hosting group
     * @param epoch accepted ownership epoch
     * @param phase EMPTY, ACTIVE, FROZEN or STAGED
     * @param handoff durable handoff identity
     * @param imageHash SHA-256 of transferred image
     * @param logicalIndex resource position independent of destination consensus index
     */
    public record Status(String partition, String group, long epoch, String phase, String handoff,
            String imageHash, long logicalIndex) { }

    /**
     * {@inheritDoc}
     */
    @Override public synchronized void apply(long index, byte[] command) { apply(index, 1, command); }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void apply(long index, long term, byte[] command) {
        try {
            DataInputStream in = input(command);
            int operation = in.readUnsignedByte();
            String outcome;
            if (operation == 1) {
                long suppliedEpoch = in.readLong(); byte[] nested = bytes(in, MAX_COMMAND); end(in);
                outcome = suppliedEpoch != epoch ? "MOVED" : !Set.of("ACTIVE", "COPYING").contains(phase) ? "FROZEN"
                        : logicalIndex == Long.MAX_VALUE ? "OVERLOADED" : resource.identityConflict(nested) ? "CONFLICT" : "OK";
                if (outcome.equals("OK")) {
                    resource.apply(++logicalIndex, logicalTerm = Math.max(logicalTerm, term), nested);
                    resource.appliedThrough(logicalIndex);
                    tail.addLast(new TailEntry(logicalIndex, logicalTerm, nested.clone())); tailBytes += nested.length + 24;
                    while (tailBytes > 16 * 1024 * 1024 && !tail.isEmpty()) tailBytes -= tail.removeFirst().command.length + 24;
                }
            } else if (operation == 2) {
                long expectedEpoch = in.readLong(); String id = in.readUTF(); end(in); identity(id);
                outcome = epoch != expectedEpoch ? "MOVED" : handoff.equals(id) && Set.of("COPYING", "FROZEN").contains(phase) ? "OK"
                        : !Set.of("ACTIVE", "COPYING").contains(phase) ? "FROZEN" : !resource.transferReady() ? "PREPARED" : "OK";
                if (outcome.equals("OK") && !handoff.equals(id)) {
                    byte[] candidate = transferImage(epoch, id);
                    if (candidate.length > MAX_IMAGE) outcome = "OVERLOADED";
                    else { phase = "COPYING"; handoff = id; pinnedImage = candidate; }
                }
            } else if (operation == 3) {
                String id = in.readUTF(); long nextEpoch = in.readLong(); byte[] image = bytes(in, MAX_IMAGE);
                String hash = in.readUTF(); end(in); identity(id);
                if (!sha256(image).equals(hash)) throw new IllegalArgumentException("Image digest mismatch");
                outcome = phase.equals("STAGED") && handoff.equals(id) && imageHash.equals(hash) ? "OK"
                        : !(phase.equals("EMPTY") || phase.equals("FROZEN") && nextEpoch > epoch + 1 && !handoff.equals(id)) ? "CONFLICT" : "OK";
                if (outcome.equals("OK") && !phase.equals("STAGED")) {
                    FrozenImage decoded = decodeImage(image);
                    if (!decoded.partition.equals(partition) || decoded.epoch + 1 != nextEpoch
                            || !decoded.handoff.equals(id)) throw new IllegalArgumentException("Image identity mismatch");
                    resource.restore(decoded.resource); logicalIndex = decoded.logicalIndex;
                    logicalTerm = decoded.logicalTerm; epoch = nextEpoch; handoff = id; imageHash = hash; phase = "STAGED";
                }
            } else if (operation == 4) {
                long nextEpoch = in.readLong(); String id = in.readUTF(); String hash = in.readUTF(); byte[] proof = bytes(in, 64); end(in);
                boolean authentic = MessageDigest.isEqual(proof, proof(authorityKey, partition, group, nextEpoch, id, hash));
                outcome = !authentic ? "UNAUTHORIZED" : epoch != nextEpoch || !handoff.equals(id)
                        || !imageHash.equals(hash) ? "CONFLICT" : phase.equals("ACTIVE") ? "OK"
                        : !phase.equals("STAGED") ? "CONFLICT" : "OK";
                if (outcome.equals("OK")) { resource.ownershipChanged(partition, epoch); phase = "ACTIVE"; }
            } else if (operation == 5) {
                String id = in.readUTF(); int count = in.readInt();
                if (count < 0 || count > 10000) throw new IllegalArgumentException("Invalid suffix count");
                List<TailEntry> entries = new ArrayList<>();
                for (int n = 0; n < count; n++) entries.add(new TailEntry(in.readLong(), in.readLong(), bytes(in, MAX_COMMAND)));
                end(in);
                outcome = !phase.equals("STAGED") || !handoff.equals(id) ? "CONFLICT" : "OK";
                if (outcome.equals("OK")) {
                    long checked = logicalIndex;
                    for (TailEntry entry : entries) { if (entry.index <= checked) continue;
                        if (entry.index != ++checked) throw new IllegalArgumentException("Suffix gap"); }
                    TransferableResource candidate = resource.copyForTransfer();
                    long nextIndex = logicalIndex, nextTerm = logicalTerm;
                    for (TailEntry entry : entries) { if (entry.index <= logicalIndex) continue;
                        candidate.apply(entry.index, entry.term, entry.command); candidate.appliedThrough(entry.index);
                        nextIndex = entry.index; nextTerm = entry.term; }
                    byte[] candidateImage = candidate.snapshot(); resource.validateSnapshot(candidateImage); resource.restore(candidateImage);
                    logicalIndex = nextIndex; logicalTerm = nextTerm;
                    imageHash = sha256(transferImage(epoch - 1));
                }
            } else if (operation == 6) {
                long expected = in.readLong(); String id = in.readUTF(); end(in);
                outcome = epoch != expected ? "MOVED" : !handoff.equals(id) ? "CONFLICT"
                        : phase.equals("FROZEN") ? "OK" : !phase.equals("COPYING") ? "CONFLICT"
                        : !resource.transferReady() ? "PREPARED" : "OK";
                if (outcome.equals("OK")) phase = "FROZEN";
            } else if (operation == 7) {
                String id = in.readUTF(); long nextEpoch = in.readLong(); String hash = in.readUTF();
                int total = in.readInt(); int offset = in.readInt(); byte[] chunk = bytes(in, 128 * 1024); end(in);
                if (total < 1 || total > MAX_IMAGE || offset < 0 || chunk.length > total - offset
                        || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid transfer chunk");
                outcome = phase.equals("STAGED") && handoff.equals(id) && imageHash.equals(hash) ? "OK"
                        : !(phase.equals("EMPTY") || phase.equals("FROZEN") && nextEpoch > epoch + 1 && !handoff.equals(id)) ? "CONFLICT" : "OK";
                if (outcome.equals("OK") && !phase.equals("STAGED")) {
                    if (!uploadId.equals(id) || !uploadHash.equals(hash)) {
                        if (offset != 0) throw new IllegalArgumentException("Upload gap");
                        upload = new byte[0]; uploadId = id; uploadHash = hash; uploadTotal = total;
                    }
                    if (total != uploadTotal || offset > upload.length) throw new IllegalArgumentException("Upload gap");
                    if (offset < upload.length) {
                        if (chunk.length > upload.length - offset || !Arrays.equals(chunk, Arrays.copyOfRange(upload, offset, offset + chunk.length)))
                            throw new IllegalArgumentException("Conflicting repeated chunk");
                    } else { byte[] appended = Arrays.copyOf(upload, upload.length + chunk.length); System.arraycopy(chunk, 0, appended, upload.length, chunk.length); upload = appended; }
                    if (upload.length == total) {
                        byte[] complete = upload;
                        apply(index, term, stage(id, nextEpoch, complete, hash));
                        upload = new byte[0]; uploadId = ""; uploadHash = ""; uploadTotal = 0; return;
                    }
                }
            } else if (operation == 8) {
                String id = in.readUTF(); end(in);
                outcome = !phase.equals("EMPTY") && !handoff.equals(id) && !uploadId.equals(id) ? "CONFLICT"
                        : !Set.of("COPYING", "FROZEN", "STAGED", "EMPTY").contains(phase) ? "CONFLICT" : "OK";
                if (outcome.equals("OK")) {
                    if (phase.equals("COPYING") || phase.equals("FROZEN")) pinnedImage = transferImage(epoch);
                    else { phase = "EMPTY"; epoch = 0; handoff = ""; imageHash = "";
                        upload = new byte[0]; uploadId = ""; uploadHash = ""; uploadTotal = 0; }
                }
            } else throw new IllegalArgumentException("Unknown partition operation");
            appliedIndex = index; receipts.put(index, outcome);
            while (receipts.size() > 4096) receipts.remove(receipts.keySet().iterator().next());
            signalHostChanges();
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid partition command", failure); }
    }

    /**
     * {@inheritDoc}
     */
    @Override public synchronized void appliedThrough(long index) { appliedIndex = index; }

    /**
     * Obtains the outcome of a recently committed command; absence is an unknown outcome.
     * @param index committed consensus index
     * @return status or UNKNOWN when its receipt fell out of the bounded response window
     */
    public synchronized String receipt(long index) { return receipts.getOrDefault(index, "UNKNOWN"); }

    /**
     * Queries the resource only for its active ownership epoch after a fresh host barrier.
     * @param expectedEpoch router's ownership epoch
     * @param query bounded resource query
     * @param barrier applied host consensus index
     * @return independent resource result
     * @throws IllegalStateException for stale ownership, frozen host or unapplied barrier
     */
    public synchronized byte[] query(long expectedEpoch, byte[] query, long barrier) {
        if (barrier > appliedIndex) throw new IllegalStateException("Unapplied barrier");
        if (expectedEpoch != epoch || !Set.of("ACTIVE", "COPYING").contains(phase)) throw new IllegalStateException("MOVED");
        if (query.length > MAX_COMMAND) throw new IllegalArgumentException("Query too large");
        return resource.query(query).clone();
    }

    /**
     * Captures the exact frozen barrier, including the resource's complete journal and cursors.
     * @param id committed handoff identity
     * @return verified transfer image; writes remain fenced so its suffix is empty
     * @throws IllegalStateException unless this exact handoff froze the source
     */
    public synchronized byte[] snapshotAt(String id) {
        if (!Set.of("COPYING", "FROZEN").contains(phase) || !handoff.equals(id)) throw new IllegalStateException("Source not frozen");
        return pinnedImage.clone();
    }
    private byte[] transferImage(long imageEpoch) { return transferImage(imageEpoch, handoff); }
    private byte[] transferImage(long imageEpoch, String imageHandoff) {
        return encode(out -> { out.writeInt(MAGIC); out.writeUTF(partition); out.writeLong(imageEpoch);
            out.writeUTF(imageHandoff); out.writeLong(logicalIndex); out.writeLong(logicalTerm); writeBytes(out, resource.snapshot()); });
    }

    /**
     * {@inheritDoc}
     */
    @Override public synchronized byte[] snapshot() {
        return encode(out -> { out.writeInt(MAGIC); out.writeUTF(partition); out.writeUTF(group); out.writeLong(epoch);
            out.writeUTF(phase); out.writeUTF(handoff); out.writeUTF(imageHash); out.writeLong(logicalIndex);
            out.writeLong(logicalTerm); out.writeLong(appliedIndex); writeBytes(out, resource.snapshot()); out.writeInt(tail.size());
            for (TailEntry entry : tail) { out.writeLong(entry.index); out.writeLong(entry.term); writeBytes(out, entry.command); }
            writeBytes(out, pinnedImage); writeBytes(out, upload); out.writeUTF(uploadId); out.writeUTF(uploadHash); out.writeInt(uploadTotal); });
    }

    /**
     * {@inheritDoc}
     */
    @Override public synchronized void restore(byte[] image) {
        try {
            DataInputStream in = input(image);
            if (in.readInt() != MAGIC || !in.readUTF().equals(partition) || !in.readUTF().equals(group))
                throw new IllegalArgumentException("Snapshot identity/version mismatch");
            long newEpoch = in.readLong(); String newPhase = in.readUTF(); String newHandoff = in.readUTF();
            String newHash = in.readUTF(); long newLogical = in.readLong(); long newTerm = in.readLong();
            long newApplied = in.readLong(); byte[] nested = bytes(in, MAX_IMAGE);
            int tailCount = in.readInt(); if (tailCount < 0 || tailCount > 1000000) throw new IOException("Invalid tail count");
            ArrayDeque<TailEntry> newTail = new ArrayDeque<>(); int newTailBytes = 0;
            for (int n = 0; n < tailCount; n++) { TailEntry entry = new TailEntry(in.readLong(), in.readLong(), bytes(in, MAX_COMMAND));
                newTail.add(entry); newTailBytes += entry.command.length + 24; }
            if (newTailBytes > 16 * 1024 * 1024) throw new IOException("Tail too large");
            byte[] newPinned = bytes(in, MAX_IMAGE); byte[] newUpload = bytes(in, MAX_IMAGE);
            String newUploadId = in.readUTF(); String newUploadHash = in.readUTF(); int newTotal = in.readInt(); end(in);
            if (newTotal < 0 || newTotal > MAX_IMAGE || newUpload.length > newTotal) throw new IOException("Invalid upload");
            if (newEpoch < 0 || newLogical < 0 || newTerm < 0 || newApplied < 0
                    || !Set.of("EMPTY", "ACTIVE", "COPYING", "FROZEN", "STAGED").contains(newPhase))
                throw new IllegalArgumentException("Invalid snapshot metadata");
            resource.restore(nested); epoch = newEpoch; phase = newPhase; handoff = newHandoff;
            imageHash = newHash; logicalIndex = newLogical; logicalTerm = newTerm; appliedIndex = newApplied; receipts.clear(); tail.clear(); tail.addAll(newTail); tailBytes = newTailBytes; pinnedImage = newPinned; upload = newUpload; uploadId = newUploadId; uploadHash = newUploadHash; uploadTotal = newTotal; signalHostChanges();
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid partition snapshot", failure); }
    }

    /**
     * Returns a bounded ordered suffix after a pinned image position.
     * @param after exclusive logical resource position
     * @param limit maximum entries, between one and 10000
     * @return immutable entries with defensive command copies
     * @throws IllegalStateException with CURSOR_EXPIRED when retention has overtaken the image
     */
    public synchronized List<TailEntry> tail(long after, int limit) {
        if (limit < 1 || limit > 10000 || after < 0) throw new IllegalArgumentException("Invalid tail request");
        if (after < logicalIndex && (tail.isEmpty() || after + 1 < tail.getFirst().index)) throw new IllegalStateException("CURSOR_EXPIRED");
        List<TailEntry> result = new ArrayList<>(); int used = 0;
        for (TailEntry entry : tail) { if (entry.index <= after) continue;
            if (result.size() >= limit || used + entry.command.length + 24 > 192 * 1024) break;
            result.add(new TailEntry(entry.index, entry.term, entry.command)); used += entry.command.length + 24; }
        return List.copyOf(result);
    }
    /**
     * Immutable logical command record for cross-group catch-up.
     * @param index consecutive logical resource position
     * @param term source committed term
     * @param command complete nested deterministic command
     */
    public record TailEntry(long index, long term, byte[] command) {
        /**
         * Copies mutable input bytes.
         * @param index logical position
         * @param term committed term
         * @param command nested bytes
         */
        public TailEntry { command = command.clone(); }
        /**
         * Returns independent command bytes.
         * @return command copy
         */
        @Override public byte[] command() { return command.clone(); }
    }
    static byte[] catchup(String id, List<TailEntry> entries) {
        return encode(out -> { out.writeByte(5); out.writeUTF(id); out.writeInt(entries.size());
            for (TailEntry entry : entries) { out.writeLong(entry.index); out.writeLong(entry.term); writeBytes(out, entry.command); } });
    }
    static byte[] freezeFinal(long epoch, String id) {
        return encode(out -> { out.writeByte(6); out.writeLong(epoch); out.writeUTF(id); });
    }
    /**
     * Returns the final frozen resource image digest used to prove catch-up completeness.
     * @param id exact handoff identity
     * @return SHA-256 bound to full recovery metadata and logical position
     * @throws IllegalStateException before the final freeze
     */
    public synchronized String finalImageHash(String id) {
        if (!phase.equals("FROZEN") || !handoff.equals(id)) throw new IllegalStateException("Not frozen");
        return sha256(transferImage(epoch));
    }
    /**
     * Returns durable streamed installation progress.
     * @return received byte count
     */
    public synchronized int uploadOffset() { return upload.length; }
    static byte[] chunk(String id, long epoch, String hash, int total, int offset, byte[] chunk) {
        return encode(out -> { out.writeByte(7); out.writeUTF(id); out.writeLong(epoch); out.writeUTF(hash);
            out.writeInt(total); out.writeInt(offset); writeBytes(out, chunk); });
    }
    synchronized void validateControl(byte[] command, String scope) {
        try {
            DataInputStream in = input(command); int operation = in.readUnsignedByte();
            switch (operation) {
                case 2, 6 -> { if (in.readLong() <= 0) throw new IOException("Invalid epoch"); identity(in.readUTF()); }
                case 3 -> { String id = in.readUTF(); long next = in.readLong(); byte[] image = bytes(in, MAX_IMAGE); String hash = in.readUTF();
                    validateImage(id, next, image, hash); }
                case 4 -> { if (in.readLong() <= 0) throw new IOException("Invalid epoch"); identity(in.readUTF());
                    if (!in.readUTF().matches("[0-9a-f]{64}") || bytes(in, 64).length != 32) throw new IOException("Invalid proof"); }
                case 5 -> { identity(in.readUTF()); int count = in.readInt(); if (count < 0 || count > 10000) throw new IOException("Invalid count");
                    long expected = logicalIndex; TransferableResource candidate = resource.copyForTransfer();
                    for (int n = 0; n < count; n++) { long next = in.readLong(), term = in.readLong(); byte[] nested = bytes(in, MAX_COMMAND);
                        if (next < 1 || term < 1 || next > expected + 1) throw new IOException("Suffix gap");
                        resource.validateReplayCommand(nested);
                        if (next > expected) { candidate.apply(next, term, nested); candidate.appliedThrough(next); expected = next; } }
                    resource.validateSnapshot(candidate.snapshot()); }
                case 7 -> { String id = in.readUTF(); identity(id); long next = in.readLong(); String hash = in.readUTF();
                    int total = in.readInt(), offset = in.readInt(); byte[] chunk = bytes(in, 128 * 1024);
                    if (next <= 1 || total < 1 || total > MAX_IMAGE || offset < 0 || chunk.length < 1 || chunk.length > total - offset
                            || !hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid chunk");
                    if (phase.equals("EMPTY") || phase.equals("FROZEN") && next > epoch + 1 && !handoff.equals(id)) {
                        byte[] previous = uploadId.equals(id) && uploadHash.equals(hash) ? upload : new byte[0];
                        if (offset > previous.length) throw new IOException("Chunk gap");
                        if (offset < previous.length) {
                            if (chunk.length > previous.length - offset || !Arrays.equals(chunk, Arrays.copyOfRange(previous, offset, offset + chunk.length))) throw new IOException("Chunk conflict");
                        } else if (previous.length + chunk.length == total) {
                            byte[] full = Arrays.copyOf(previous, total); System.arraycopy(chunk, 0, full, offset, chunk.length); validateImage(id, next, full, hash);
                        }
                    } }
                case 8 -> identity(in.readUTF());
                default -> throw new IOException("Unknown control");
            }
            end(in);
        } catch (IOException invalid) { throw new IllegalArgumentException("Invalid partition control", invalid); }
    }
    private void validateImage(String id, long next, byte[] image, String hash) throws IOException {
        identity(id); if (!sha256(image).equals(hash)) throw new IOException("Image digest mismatch");
        FrozenImage decoded = decodeImage(image);
        if (!decoded.partition.equals(partition) || !decoded.handoff.equals(id) || decoded.epoch + 1 != next
                || decoded.logicalIndex < 0 || decoded.logicalTerm < 0) throw new IOException("Image identity mismatch");
        resource.validateSnapshot(decoded.resource);
    }
    static byte[] reset(String id) { return encode(out -> { out.writeByte(8); out.writeUTF(id); }); }
    synchronized void addChangeListener(Runnable listener) {
        Objects.requireNonNull(listener); if (hostChanges.size() >= 16) throw new IllegalStateException("Partition host observer capacity"); hostChanges.add(listener);
    }
    void removeChangeListener(Runnable listener) { hostChanges.remove(listener); }
    private void signalHostChanges() { for (Runnable listener : hostChanges) listener.run(); }
    ExecutionStateMachine executionResource() { return resource instanceof ExecutionStateMachine execution ? execution : null; }
    QueueStateMachine queueResource() { return resource instanceof QueueStateMachine queue ? queue : null; }
    boolean owns(TransferableResource expected) { return resource == expected; }
    synchronized <T> T guardedQuery(long expectedEpoch, long barrier, TransferableResource expected,
            java.util.function.Function<TransferableResource, T> query) {
        if (resource != expected) throw new IllegalArgumentException("Wrong delegate");
        if (barrier > appliedIndex || expectedEpoch != epoch || !Set.of("ACTIVE", "COPYING").contains(phase))
            throw new IllegalStateException("MOVED");
        return query.apply(resource);
    }
    boolean identityConflict(byte[] command) { return resource.identityConflict(command); }
    String requestIdentity(byte[] command) { return resource.requestIdentity(command); }
    void validate(byte[] command, String scope, boolean administrative) { resource.validate(command, scope, administrative); }
    void validateQuery(byte[] query, String scope) { resource.validateQuery(query, scope); }
    boolean timeControl(byte[] command) { return resource.timeControl(command); }
    boolean timeSensitive(byte[] command) { return resource.timeSensitive(command); }
    byte[] timeCommand(long time) { return resource.timeCommand(time); }
    static byte[] mutation(long epoch, byte[] command) {
        if (epoch <= 0 || command.length > MAX_COMMAND) throw new IllegalArgumentException("Invalid routed command");
        return encode(out -> { out.writeByte(1); out.writeLong(epoch); writeBytes(out, command); });
    }
    static byte[] freeze(long epoch, String id) {
        identity(id); return encode(out -> { out.writeByte(2); out.writeLong(epoch); out.writeUTF(id); });
    }
    static byte[] stage(String id, long epoch, byte[] image, String hash) {
        if (image.length > MAX_IMAGE) throw new IllegalArgumentException("Image too large");
        identity(id); return encode(out -> { out.writeByte(3); out.writeUTF(id); out.writeLong(epoch); writeBytes(out, image); out.writeUTF(hash); });
    }
    static byte[] activate(long epoch, String id, String hash, byte[] proof) {
        return encode(out -> { out.writeByte(4); out.writeLong(epoch); out.writeUTF(id); out.writeUTF(hash); writeBytes(out, proof); });
    }
    static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static byte[] proof(byte[] key, String partition, String owner, long epoch, String handoff, String hash) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal((partition + "\n" + owner + "\n" + epoch + "\n" + handoff + "\n" + hash).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    static void identity(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid identity");
    }
    interface Encoder { void write(DataOutputStream out) throws IOException; }
    static byte[] encode(Encoder encoder) {
        try { ByteArrayOutputStream buffer = new ByteArrayOutputStream(); encoder.write(new DataOutputStream(buffer)); return buffer.toByteArray(); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    static DataInputStream input(byte[] value) { return new DataInputStream(new ByteArrayInputStream(value)); }
    static void writeBytes(DataOutputStream out, byte[] value) throws IOException { out.writeInt(value.length); out.write(value); }
    static byte[] bytes(DataInputStream in, int limit) throws IOException {
        int length = in.readInt(); if (length < 0 || length > limit || length > in.available()) throw new IOException("Invalid length");
        return in.readNBytes(length);
    }
    static void end(DataInputStream in) throws IOException { if (in.available() != 0) throw new IOException("Trailing bytes"); }
    private record FrozenImage(String partition, long epoch, String handoff, long logicalIndex, long logicalTerm, byte[] resource) { }
    private static FrozenImage decodeImage(byte[] image) throws IOException {
        DataInputStream in = input(image); if (in.readInt() != MAGIC) throw new IOException("Unknown image version");
        FrozenImage result = new FrozenImage(in.readUTF(), in.readLong(), in.readUTF(), in.readLong(), in.readLong(), bytes(in, MAX_IMAGE));
        end(in); return result;
    }
}
