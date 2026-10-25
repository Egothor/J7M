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

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.zip.CRC32C;

/**
 * Durable, quorum-consistent Raft backup and fresh-generation restore. Export captures a committed
 * application checkpoint, electorate/joint transition and request-identity journal under the hosting
 * node's apply lock after a fresh quorum barrier; it never copies mutable live files. Restore validates
 * the entire bundle and an independent application candidate before creating the target directory.
 * The caller must fence the old application endpoints before routing traffic to a recovery generation.
 * Backups contain sensitive application state and must remain in protected storage; checksums detect
 * corruption and do not replace storage authorization or authenticated provenance.
 */
public final class ConsensusBackup {
    private static final int FORMAT = 1;
    private static final int MAX_IMAGE = 536870912;
    private static final int MAX_STATE = 268435456;
    private static final int MAX_MANIFEST = 16384;
    private static final String MANIFEST = "manifest.bin", IMAGE = "checkpoint.bin";
    private ConsensusBackup() { }

    /**
     * Immutable verified recovery metadata; resource internals remain opaque.
     * @param schema explicit application snapshot schema identifier
     * @param sourceGroup source consensus generation identity
     * @param sourceNode exporting node identity
     * @param index captured committed/applied index
     * @param includedTerm term of captured last entry
     * @param currentTerm exporting node's current durable term
     * @param configurationEpoch committed electorate epoch
     * @param members committed voter identities
     * @param jointMembers joint-transition voter identities, empty when stable
     * @param requestIdentities retained client deduplication identities
     * @param stateBytes application state bytes
     * @param checkpointSha256 checksum binding electorate, identity journal and application state
     */
    public record Metadata(String schema, String sourceGroup, String sourceNode, long index,
            long includedTerm, long currentTerm, long configurationEpoch, Set<String> members,
            Set<String> jointMembers, int requestIdentities, int stateBytes, String checkpointSha256) {
        /**
         * Copies immutable voter sets.
         */
        public Metadata { members = Set.copyOf(members); jointMembers = Set.copyOf(jointMembers); }
    }
    private record Identity(long index, byte[] digest) { }
    private record Checkpoint(String group, long index, long term, long epoch, Set<String> members,
            Set<String> joint, SortedMap<String, Identity> requests, byte[] state) { }
    private record Bundle(Metadata metadata, Checkpoint checkpoint) { }

    /**
     * Exports an immutable backup directory, atomically published after all data and manifest fsyncs.
     * Destination must not exist. Concurrent writes may continue after the captured barrier.
     * @param node live current leader with healthy quorum
     * @param destination fresh backup directory with an existing parent
     * @param schema explicit application schema, bound into the manifest
     * @param deadline quorum-barrier deadline; expiry never rolls back already committed source work
     * @return verified published metadata
     * @throws IOException on quorum failure, corruption, unsupported filesystem or publication failure
     * @throws IllegalArgumentException for invalid schema or destination
     */
    public static Metadata export(NetworkConsensusNode node, Path destination, String schema, Instant deadline) throws IOException {
        Objects.requireNonNull(node); identifier(schema); Path target = fresh(destination);
        NetworkConsensusNode.RecoveryImage image = node.exportRecoveryImage(deadline);
        if (image.checkpoint().length > MAX_IMAGE) throw new IOException("Backup checkpoint exceeds supported limit");
        Checkpoint checkpoint = decodeCheckpoint(image.checkpoint());
        if (!checkpoint.group.equals(image.groupId()) || image.currentTerm() < checkpoint.term) throw new IOException("Inconsistent backup identity/term");
        Metadata metadata = metadata(schema, image.nodeId(), image.currentTerm(), checkpoint, hash(image.checkpoint()));
        byte[] manifest = manifest(metadata, image.checkpoint().length);
        if (manifest.length > MAX_MANIFEST) throw new IOException("Backup manifest exceeds supported limit");
        Path staging = stage(target);
        boolean published = false;
        try {
            writeForced(staging.resolve(IMAGE), image.checkpoint());
            writeForced(staging.resolve(MANIFEST), manifest);
            forceDirectory(staging);
            publish(staging, target); published = true;
            return inspect(target, schema);
        } finally { if (!published) discard(staging); }
    }

    /**
     * Verifies manifest, checksum, bounded schema and checkpoint recovery metadata without mutation.
     * @param backup published backup directory, never a symlink
     * @param expectedSchema required exact application schema
     * @return verified metadata
     * @throws IOException for corrupt, unsupported or incompatible backup
     * @throws IllegalArgumentException for invalid schema
     */
    public static Metadata inspect(Path backup, String expectedSchema) throws IOException {
        identifier(expectedSchema); return read(backup, expectedSchema).metadata;
    }

    /**
     * Restores a backup to a fresh, offline directory and a new consensus group generation.
     * Electorate IDs and request identities are preserved; endpoints/credentials are supplied by the
     * target configuration. The new group identity prevents old peer frames from joining recovery.
     * Application snapshot compatibility is established by an independent factory candidate; no
     * unknown resource schema is inferred. The old service/routing generation must be fenced first.
     * @param backup immutable published backup directory
     * @param target fresh node configuration; groupId must differ from the source generation
     * @param expectedSchema exact supported application snapshot schema
     * @param minimumIndex smallest acceptable committed recovery index, preventing stale-backup downgrade
     * @param applicationFactory trusted factory producing a fresh application validator, never a live resource
     * @return verified metadata of the restored source image
     * @throws IOException if validation fails, target exists or atomic durable publication fails
     * @throws IllegalArgumentException for invalid floor, factory or incompatible generation/membership
     */
    public static Metadata restore(Path backup, NetworkConsensusConfig target, String expectedSchema,
            long minimumIndex, Supplier<? extends ReplicatedStateMachine> applicationFactory) throws IOException {
        Objects.requireNonNull(target); Objects.requireNonNull(applicationFactory); identifier(expectedSchema);
        if (minimumIndex < 0) throw new IllegalArgumentException("Negative recovery floor");
        Bundle bundle = read(backup, expectedSchema); Metadata metadata = bundle.metadata; Checkpoint checkpoint = bundle.checkpoint;
        if (metadata.index < minimumIndex) throw new IOException("Backup is older than the required committed recovery floor");
        if (metadata.sourceGroup.equals(target.groupId())) throw new IllegalArgumentException("Restore requires a new isolated consensus generation");
        Set<String> required = new HashSet<>(checkpoint.members); required.addAll(checkpoint.joint);
        if (!target.peers().keySet().containsAll(required) || !required.contains(target.nodeId())
                || !target.initialMembers().equals(checkpoint.members)) throw new IllegalArgumentException("Restore electorate identity mismatch");
        if (checkpoint.state.length > target.maxSnapshotBytes()) throw new IOException("Restore exceeds target snapshot capacity");
        try {
            ReplicatedStateMachine candidate = Objects.requireNonNull(applicationFactory.get());
            candidate.restore(checkpoint.state.clone()); candidate.appliedThrough(checkpoint.index);
        } catch (RuntimeException error) { throw new IOException("Application rejected backup schema/state", error); }
        byte[] rebound = encodeCheckpoint(checkpoint, target.groupId());
        if (rebound.length > (long) target.maxSnapshotBytes() * 4) throw new IOException("Restore checkpoint exceeds target manifest capacity");
        Path destination = fresh(target.directory()), staging = stage(destination);
        boolean published = false;
        try {
            try (FileChannel wal = FileChannel.open(staging.resolve("network.wal"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                privateFile(staging.resolve("network.wal"));
                record(wal, encode(out -> { out.writeByte(6); out.writeUTF(target.groupId()); out.writeUTF(target.nodeId()); }));
                record(wal, encode(out -> { out.writeByte(1); out.writeLong(metadata.currentTerm); out.writeUTF(""); }));
                record(wal, encode(out -> { out.writeByte(5); out.write(rebound); }));
                record(wal, encode(out -> { out.writeByte(3); out.writeLong(checkpoint.index); }));
                wal.force(true);
            }
            writeForced(staging.resolve("recovery-manifest.bin"), manifest(metadata, encodeCheckpoint(checkpoint, checkpoint.group).length));
            forceDirectory(staging); publish(staging, destination); published = true; return metadata;
        } finally { if (!published) discard(staging); }
    }

    private static Bundle read(Path backup, String expectedSchema) throws IOException {
        if (Files.isSymbolicLink(backup) || !Files.isDirectory(backup, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Backup directory unavailable");
        byte[] manifest = bounded(backup.resolve(MANIFEST), MAX_MANIFEST); Metadata metadata; int imageSize;
        if (manifest.length < 32) throw new IOException("Truncated backup manifest");
        byte[] body = Arrays.copyOf(manifest, manifest.length - 32), expected = Arrays.copyOfRange(manifest, manifest.length - 32, manifest.length);
        if (!MessageDigest.isEqual(hash(body), expected)) throw new IOException("Backup manifest checksum mismatch");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            if (!in.readUTF().equals("J7M-CONSENSUS-BACKUP") || in.readInt() != FORMAT) throw new IOException("Unsupported backup manifest");
            String schema = in.readUTF(), group = in.readUTF(), node = in.readUTF(); identifier(schema); identifier(group); identifier(node);
            long index = in.readLong(), includedTerm = in.readLong(), currentTerm = in.readLong(), epoch = in.readLong();
            Set<String> members = members(in, false), joint = members(in, true); int requests = in.readInt(), stateSize = in.readInt(); imageSize = in.readInt();
            byte[] digest = in.readNBytes(32);
            if (!schema.equals(expectedSchema) || index < 1 || includedTerm < 1 || currentTerm < includedTerm || epoch < 0
                    || requests < 0 || requests > MAX_IMAGE / 42 || stateSize < 0 || stateSize > MAX_STATE || imageSize < stateSize || imageSize > MAX_IMAGE || digest.length != 32 || in.available() != 0)
                throw new IOException("Incompatible or invalid backup manifest");
            metadata = new Metadata(schema, group, node, index, includedTerm, currentTerm, epoch, members, joint, requests, stateSize, HexFormat.of().formatHex(digest));
        } catch (RuntimeException error) { throw new IOException("Malformed backup manifest", error); }
        byte[] image = bounded(backup.resolve(IMAGE), MAX_IMAGE);
        if (image.length != imageSize || !HexFormat.of().formatHex(hash(image)).equals(metadata.checkpointSha256)) throw new IOException("Backup checkpoint checksum mismatch");
        Checkpoint checkpoint = decodeCheckpoint(image);
        if (!metadata.equals(metadata(metadata.schema, metadata.sourceNode, metadata.currentTerm, checkpoint, hash(image)))) throw new IOException("Backup manifest/checkpoint metadata mismatch");
        return new Bundle(metadata, checkpoint);
    }
    private static Metadata metadata(String schema, String node, long currentTerm, Checkpoint checkpoint, byte[] digest) {
        return new Metadata(schema, checkpoint.group, node, checkpoint.index, checkpoint.term, currentTerm, checkpoint.epoch, checkpoint.members, checkpoint.joint, checkpoint.requests.size(), checkpoint.state.length, HexFormat.of().formatHex(digest));
    }
    private static byte[] manifest(Metadata metadata, int imageSize) throws IOException {
        byte[] body = encode(out -> {
            out.writeUTF("J7M-CONSENSUS-BACKUP"); out.writeInt(FORMAT); out.writeUTF(metadata.schema); out.writeUTF(metadata.sourceGroup); out.writeUTF(metadata.sourceNode);
            out.writeLong(metadata.index); out.writeLong(metadata.includedTerm); out.writeLong(metadata.currentTerm); out.writeLong(metadata.configurationEpoch);
            members(out, metadata.members); members(out, metadata.jointMembers); out.writeInt(metadata.requestIdentities); out.writeInt(metadata.stateBytes); out.writeInt(imageSize); out.write(HexFormat.of().parseHex(metadata.checkpointSha256));
        });
        return encode(out -> { out.write(body); out.write(hash(body)); });
    }
    private static Checkpoint decodeCheckpoint(byte[] bytes) throws IOException {
        if (bytes.length > MAX_IMAGE) throw new IOException("Backup checkpoint size");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 1) throw new IOException("Unsupported consensus checkpoint schema");
            String group = in.readUTF(); identifier(group); long index = in.readLong(), term = in.readLong(), epoch = in.readLong();
            Set<String> members = members(in, false), joint = in.readBoolean() ? members(in, false) : Set.of();
            int count = in.readInt(); if (count < 0 || count > bytes.length / 42) throw new IOException("Backup identity count");
            SortedMap<String, Identity> identities = new TreeMap<>();
            for (int i = 0; i < count; i++) { String id = in.readUTF(); long position = in.readLong(); byte[] digest = in.readNBytes(32); if (id.isBlank() || id.length() > 1024 || position < 1 || position > index || digest.length != 32 || identities.put(id, new Identity(position, digest)) != null) throw new IOException("Backup identity journal"); }
            int size = in.readInt(); if (size < 0 || size > MAX_STATE) throw new IOException("Backup resource size");
            byte[] expected = in.readNBytes(32), state = in.readNBytes(size);
            if (index < 1 || term < 1 || epoch < 0 || state.length != size || expected.length != 32 || !MessageDigest.isEqual(expected, hash(state)) || in.available() != 0) throw new IOException("Backup application checksum/position");
            return new Checkpoint(group, index, term, epoch, members, joint, identities, state);
        } catch (RuntimeException error) { throw new IOException("Malformed consensus backup checkpoint", error); }
    }
    private static byte[] encodeCheckpoint(Checkpoint checkpoint, String group) throws IOException {
        return encode(out -> { out.writeInt(1); out.writeUTF(group); out.writeLong(checkpoint.index); out.writeLong(checkpoint.term); out.writeLong(checkpoint.epoch); members(out, checkpoint.members); out.writeBoolean(!checkpoint.joint.isEmpty()); if (!checkpoint.joint.isEmpty()) members(out, checkpoint.joint); out.writeInt(checkpoint.requests.size()); for (var entry : checkpoint.requests.entrySet()) { out.writeUTF(entry.getKey()); out.writeLong(entry.getValue().index); out.write(entry.getValue().digest); } out.writeInt(checkpoint.state.length); out.write(hash(checkpoint.state)); out.write(checkpoint.state); });
    }
    private static Set<String> members(DataInputStream in, boolean allowEmpty) throws IOException {
        int count = in.readInt(); if (count < (allowEmpty ? 0 : 1) || count > 1024) throw new IOException("Backup electorate bound"); Set<String> members = new TreeSet<>(); for (int i = 0; i < count; i++) { String id = in.readUTF(); identifier(id); if (!members.add(id)) throw new IOException("Duplicate backup voter"); } return Set.copyOf(members);
    }
    private static void members(DataOutputStream out, Set<String> members) throws IOException { out.writeInt(members.size()); for (String id : new TreeSet<>(members)) out.writeUTF(id); }
    private static Path fresh(Path path) throws IOException {
        Path target = Objects.requireNonNull(path).toAbsolutePath().normalize(); Path parent = target.getParent();
        if (parent == null || Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Backup/restore requires a fresh target and existing nonsymlink parent"); return target;
    }
    private static Path stage(Path target) throws IOException { return Files.createTempDirectory(target.getParent(), ".j7m-recovery-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
    private static void privateFile(Path path) throws IOException { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); }
    private static void writeForced(Path file, byte[] bytes) throws IOException { try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { privateFile(file); ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true); } }
    private static void forceDirectory(Path directory) throws IOException { try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); } }
    private static void publish(Path staging, Path target) throws IOException {
        String reservationName = ".j7m-publication-" + HexFormat.of().formatHex(hash(target.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path reservation = target.getParent().resolve(reservationName);
        FileChannel ownership = FileChannel.open(reservation, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try (ownership) {
            privateFile(reservation); ownership.force(true);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Backup/restore destination already published");
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE); forceDirectory(target.getParent());
        } finally { Files.deleteIfExists(reservation); }
    }
    private static byte[] bounded(Path file, int maximum) throws IOException { if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Backup file unavailable"); long size = Files.size(file); if (size < 0 || size > maximum) throw new IOException("Backup file bound"); try (InputStream input = Files.newInputStream(file)) { byte[] bytes = input.readNBytes(maximum + 1); if (bytes.length != size || bytes.length > maximum) throw new IOException("Backup file changed/oversized"); return bytes; } }
    private static void record(FileChannel channel, byte[] bytes) throws IOException { CRC32C crc = new CRC32C(); crc.update(bytes); ByteBuffer buffer = ByteBuffer.allocate(bytes.length + 8).putInt(bytes.length).put(bytes).putInt((int) crc.getValue()); buffer.flip(); while (buffer.hasRemaining()) channel.write(buffer); }
    private static void discard(Path directory) throws IOException { if (!Files.exists(directory)) return; try (var files = Files.list(directory)) { for (Path file : files.toList()) Files.deleteIfExists(file); } Files.deleteIfExists(directory); }
    private static byte[] hash(byte[] bytes) { try { return MessageDigest.getInstance("SHA-256").digest(bytes); } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); } }
    private static void identifier(String value) { if (value == null || value.isBlank() || value.length() > 256 || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Backup identifier/schema"); }
    @FunctionalInterface private interface Encoder { void write(DataOutputStream out) throws IOException; }
    private static byte[] encode(Encoder encoder) throws IOException { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (DataOutputStream out = new DataOutputStream(bytes)) { encoder.write(out); } return bytes.toByteArray(); }
}
