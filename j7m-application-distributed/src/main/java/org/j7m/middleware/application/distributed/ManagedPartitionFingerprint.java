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

package org.j7m.middleware.application.distributed;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.j7m.middleware.application.api.ManagedPartitionDefinition;
import org.j7m.middleware.application.components.ManagedHostedResources;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.consensus.NetworkConsensusNode;
import org.j7m.middleware.distributed.PartitionStateMachine;

/**
 * Exact immutable-program fingerprint for native group/WAL binding, not Byzantine attestation.
 * The host supplies the digest of its verified whole application artifact and immutable semantic
 * configuration. Peer rosters, local node IDs, directories and TLS secrets do not belong in that
 * semantic configuration. Backend code must remain immutable for the process lifetime. Different
 * program/schema/configuration is rejected; mixed-code reducer rolling upgrades are unsupported.
 */
public final class ManagedPartitionFingerprint {
    private ManagedPartitionFingerprint() { }
    private static final class Backend {
        private static final String DIGEST = safeBackend();
    }

    /**
     * Hashes a trusted program's data contract and semantic profile without invoking its reducer.
     * Backend module code is hashed once from local immutable deployment files, at most64MiB per
     * code location and4096 class files for an exploded module. Metadata is capped at4MiB.
     * @param bundle verified whole-artifact digest and semantic configuration
     * @param definition validated generated reducer definition
     * @param schemas validated A01 data allowlist
     * @param resourceScope fixed authenticated scope for this isolated encoded state
     * @param maximumRequests explicit bounded durable receipt capacity
     * @return lowercase SHA-256 program fingerprint, independent of local paths and peer membership
     * @throws IllegalArgumentException for invalid metadata, schema or deployment bounds
     */
    public static String compute(final ManagedHostedResources.Bundle bundle, final ManagedPartitionDefinition<?> definition,
            final SchemaRegistry schemas, final String resourceScope, final int maximumRequests) {
        if (resourceScope == null || resourceScope.isBlank() || resourceScope.length() > 128 || resourceScope.chars().anyMatch(Character::isISOControl) || maximumRequests < 1 || maximumRequests > 10000) throw invalid();
        try {
            if (Backend.DIGEST.isEmpty()) throw invalid();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            var out = new DataOutputStream(new DigestOutputStream(new Limit(4 * 1024 * 1024), digest));
            out.writeInt(1); text(out, Backend.DIGEST); text(out, Runtime.version().toString()); text(out, bundle.artifactDigest());
            text(out, definition.type().getName()); text(out, definition.partition().kind()); text(out, definition.partition().id());
            out.writeInt(definition.partition().version()); text(out, resourceScope); out.writeInt(maximumRequests);
            out.writeInt(ManagedPartitionResource.MAX_COMMAND_BYTES); out.writeInt(ManagedPartitionResource.MAX_STATE_BYTES);
            out.writeInt(ManagedPartitionResource.MAX_SNAPSHOT_BYTES);
            out.writeInt(bundle.semanticConfiguration().size());
            for (var entry : new TreeMap<>(bundle.semanticConfiguration()).entrySet()) { text(out, entry.getKey()); text(out, entry.getValue()); }
            out.writeInt(definition.partition().methods().size());
            for (var method : definition.partition().methods().stream().sorted(java.util.Comparator.comparing(Object::toString)).toList()) {
                text(out, method.declaringType()); text(out, method.name()); out.writeInt(method.parameterTypes().size());
                for (String parameter : method.parameterTypes()) text(out, parameter);
                text(out, method.returnType());
            }
            out.writeInt(definition.partition().attributes().size());
            for (var entry : new TreeMap<>(definition.partition().attributes()).entrySet()) { text(out, entry.getKey()); text(out, entry.getValue()); }
            var visited = new HashSet<Class<?>>();
            for (Class<?> type : List.of(definition.stateType(), definition.commandType(), definition.queryType(), definition.viewType())) {
                schema(out, type, schemas, visited);
            }
            out.flush(); return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) { throw invalid(); }
    }

    private static String safeBackend() {
        try { return backend(); }
        catch (IllegalArgumentException failure) { return ""; }
    }

    /**
     * Binds the portable program to immutable native bootstrap semantics before opening its WAL.
     * The original electorate is shared configuration, never the local node selection or current
     * membership. Keep this seed unchanged after reconfiguration and restart. Endpoints, keys and
     * directories are deliberately excluded. A different initial epoch denotes a different bootstrap.
     * @param base trusted operator group name,1..48 ASCII identifier characters
     * @param fingerprint locally computed portable program fingerprint
     * @param partition exact stable partition identity
     * @param initialEpoch original bootstrap epoch; zero for an initially inactive destination
     * @param originalVoters immutable original bootstrap electorate,1..64 identities
     * @param maximumFrameBytes original native frame bound
     * @param maximumSnapshotBytes original native snapshot bound
     * @return native-compatible identity of at most113 characters
     * @throws IllegalArgumentException for malformed or inconsistent bootstrap configuration
     */
    public static String groupId(final String base, final String fingerprint, final String partition,
            final long initialEpoch, final Set<String> originalVoters, final int maximumFrameBytes, final int maximumSnapshotBytes) {
        if (base == null || !base.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,47}")
                || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")
                || partition == null || partition.isBlank() || partition.length() > 128
                || partition.chars().anyMatch(Character::isISOControl) || initialEpoch < 0
                || originalVoters == null || originalVoters.isEmpty() || originalVoters.size() > 64
                || maximumFrameBytes < 4096 || maximumSnapshotBytes < maximumFrameBytes) throw invalid();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
            out.writeInt(1); text(out, fingerprint); text(out, partition); out.writeLong(initialEpoch);
            out.writeInt(maximumFrameBytes); out.writeInt(maximumSnapshotBytes); out.writeInt(originalVoters.size());
            for (String voter : new java.util.TreeSet<>(originalVoters)) {
                if (voter.isBlank() || voter.length() > 128 || voter.chars().anyMatch(Character::isISOControl)) throw invalid();
                text(out, voter);
            }
            out.flush(); return base + "." + HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) { throw invalid(); }
    }

    private static void schema(final DataOutputStream out, final Class<?> type, final SchemaRegistry schemas,
            final Set<Class<?>> visited) throws IOException {
        text(out, type.getName());
        if (!visited.add(type)) { out.writeBoolean(false); return; }
        out.writeBoolean(true); var descriptor = schemas.forType(type);
        if (descriptor == null) { out.writeBoolean(false); return; }
        out.writeBoolean(true); text(out, descriptor.schemaId()); out.writeInt(descriptor.version()); out.writeInt(descriptor.fields().size());
        for (var field : descriptor.fields().stream().sorted(java.util.Comparator.comparing(org.j7m.middleware.codec.api.SchemaField::name)).toList()) {
            text(out, field.name()); out.writeBoolean(field.required());
            Object value = field.defaultValue(); out.writeBoolean(value != null);
            if (value != null) {
                if (!List.of(String.class, Boolean.class, Character.class, Byte.class, Short.class, Integer.class,
                        Long.class, Float.class, Double.class).contains(value.getClass())) throw invalid();
                text(out, value.getClass().getName()); text(out, value.toString());
            }
            schema(out, field.type(), schemas, visited);
        }
    }

    private static String backend() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
            var visited = new HashSet<java.net.URI>();
            for (Class<?> anchor : List.of(ManagedPartitionResource.class, PartitionStateMachine.class, NetworkConsensusNode.class,
                    SchemaRegistry.class, DefaultValueCodec.class, ManagedHostedResources.class)) {
                var location = anchor.getProtectionDomain().getCodeSource().getLocation().toURI();
                if (!visited.add(location)) continue;
                if (!location.getScheme().equals("file")) throw invalid();
                text(out, anchor.getName()); Path source = Path.of(location);
                long bytes = 0;
                if (Files.isDirectory(source)) {
                    List<Path> classes;
                    try (var paths = Files.walk(source)) {
                        classes = paths.filter(path -> path.toString().endsWith(".class")).sorted().limit(4097).toList();
                    }
                    if (classes.size() > 4096 || classes.isEmpty()) throw invalid();
                    for (Path file : classes) {
                        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw invalid();
                        bytes += Files.size(file); if (bytes > 64 * 1024 * 1024) throw invalid();
                        text(out, source.relativize(file).toString().replace(java.io.File.separatorChar, '/'));
                        out.writeLong(Files.size(file)); try (var in = Files.newInputStream(file)) { in.transferTo(out); }
                    }
                } else {
                    if (!Files.isRegularFile(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw invalid();
                    long size = Files.size(source); if (size > 64 * 1024 * 1024) throw invalid();
                    out.writeLong(size); try (var in = Files.newInputStream(source)) { in.transferTo(out); }
                }
            }
            out.flush(); return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) { throw invalid(); }
    }
    private static void text(final DataOutputStream out, final String text) throws IOException {
        out.writeInt(text.length()); for (int index = 0; index < text.length(); index++) out.writeChar(text.charAt(index));
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid immutable partition program profile"); }
    private static final class Limit extends OutputStream {
        private final int maximum;
        private int count;
        Limit(final int maximum) { this.maximum = maximum; }
        @Override public void write(final int value) { if (++count > maximum) throw invalid(); }
        @Override public void write(final byte[] value, final int offset, final int length) {
            if (length < 0 || length > maximum - count) throw invalid(); count += length;
        }
    }
}
