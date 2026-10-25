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

package org.j7m.middleware.application.repository;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.j7m.middleware.application.api.*;

/**
 * Durable bounded artifact staging and immutable publication. Staging is never returned by fetch or
 * application import. Every acknowledged chunk and manifest is fsynced before return; restarts verify
 * existing chunks before reporting them as retained. One owner process must hold this store open.
 */
public final class ResumableArtifactStore implements AutoCloseable {
    private final Path root;
    private final Map<String, PublicKey> trust;
    private final long quota;
    private final int maximumArtifacts;
    private final ApplicationRepository applications;
    private final ArtifactFileOwnership ownership;
    /**
     * Opens an exclusively owned artifact store.
     * @param root persistent private local directory
     * @param trust immutable trusted signing keys
     * @param quota maximum reserved logical bytes including chunks, assembly, manifest and recovery headroom
     * @param maximumArtifacts maximum retained staging/published manifest count
     * @param applications optional existing application repository for verified bundle import
     * @throws IOException for disk, locking or symlink failure
     * @throws IllegalArgumentException for invalid quotas
     */
    public ResumableArtifactStore(Path root, Map<String, PublicKey> trust, long quota, int maximumArtifacts,
            ApplicationRepository applications) throws IOException {
        if (quota < 8192 || quota > 1099511627776L || maximumArtifacts < 1 || maximumArtifacts > 100000) throw new IllegalArgumentException("Artifact quota");
        this.root = root.toAbsolutePath().normalize(); this.trust = Map.copyOf(trust); this.quota = quota; this.maximumArtifacts = maximumArtifacts; this.applications = applications;
        Files.createDirectories(this.root); if (Files.isSymbolicLink(this.root)) throw new IOException("Symlink store root");
        ownership = new ArtifactFileOwnership(this.root.resolve("owner.lock"));
    }
    /**
     * Persists an authorized manifest and returns its verified retained chunk indexes.
     * @param tenant server-bound authorization scope
     * @param manifest signed metadata
     * @return immutable verified chunk indexes
     * @throws IOException for quota or disk failure
     * @throws SecurityException for tenant or signature mismatch
     */
    public synchronized Set<Integer> begin(String tenant, ArtifactManifest manifest) throws IOException {
        authorize(tenant, manifest); Path directory = directory(tenant, manifest.id());
        if (!Files.exists(directory.resolve("manifest"), LinkOption.NOFOLLOW_LINKS)) {
            long reserved = 0; int count = 0;
            try (var entries = Files.walk(root, 2)) {
                for (Path artifact : entries.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        && root.relativize(path).getNameCount() == 2 && !path.equals(directory)).toList()) {
                    safe(artifact); count++; Path metadata = artifact.resolve("manifest"); boolean committed = Files.exists(metadata, LinkOption.NOFOLLOW_LINKS);
                    if (!committed) metadata = artifact.resolve("manifest.pending");
                    if (!Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) { reserved += 4096; continue; }
                    try {
                        ArtifactManifest prior = ArtifactManifest.decode(read(metadata, 2097152)); prior.verify(trust);
                        if (prior.tenant().equals(tenant) && prior.coordinate().equals(manifest.coordinate()) && !prior.id().equals(manifest.id())) throw new IOException("Immutable artifact coordinate conflict");
                        reserved = Math.addExact(reserved, reservation(prior));
                    } catch (IllegalArgumentException | SecurityException invalid) {
                        if (committed) throw new IOException("Corrupt persisted artifact manifest", invalid);
                        reserved += read(metadata, 2097152).length + 4096L;
                    }
                }
            }
            if (count >= maximumArtifacts || reservation(manifest) > quota - reserved) throw new IOException("Artifact quota exceeded");
            Files.createDirectories(directory); safe(directory); atomic(directory.resolve("manifest"), manifest.encode());
        } else {
            ArtifactManifest prior = manifest(tenant, manifest.id()); if (!Arrays.equals(prior.encode(), manifest.encode())) throw new IOException("Immutable manifest conflict");
        }
        return retained(tenant, manifest.id());
    }
    /**
     * Returns verified persisted chunks; corrupted local chunks are rejected and never resumed.
     * @param tenant bound scope
     * @param id manifest identity
     * @return immutable retained indexes
     * @throws IOException for absent or corrupt state
     */
    public synchronized Set<Integer> retained(String tenant, String id) throws IOException {
        ArtifactManifest manifest = manifest(tenant, id); Set<Integer> present = new TreeSet<>();
        for (int n = 0; n < manifest.chunkHashes().size(); n++) {
            Path path = directory(tenant, id).resolve("chunk-" + n);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] chunk = read(path, manifest.chunkSize()); verifyChunk(manifest, n, chunk); present.add(n);
            }
        }
        return Collections.unmodifiableSet(present);
    }
    /**
     * Durably stores one verified idempotent chunk.
     * @param tenant bound scope
     * @param id manifest identity
     * @param index zero-based chunk index
     * @param bytes complete chunk bytes
     * @throws IOException for absent manifest, conflicting/corrupt chunk or disk error
     */
    public synchronized void upload(String tenant, String id, int index, byte[] bytes) throws IOException {
        ArtifactManifest manifest = manifest(tenant, id); verifyChunk(manifest, index, bytes); Path path = directory(tenant, id).resolve("chunk-" + index);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { if (!Arrays.equals(read(path, manifest.chunkSize()), bytes)) throw new IOException("Immutable chunk conflict"); return; }
        atomic(path, bytes);
    }
    /**
     * Verifies the full artifact and atomically publishes its ready marker.
     * @param tenant bound scope
     * @param id manifest identity
     * @return immutable manifest of the now fetchable artifact
     * @throws IOException for incomplete/hash-invalid state or disk failure
     */
    public synchronized ArtifactManifest commitManifest(String tenant, String id) throws IOException {
        ArtifactManifest manifest = manifest(tenant, id); Path directory = directory(tenant, id);
        if (Files.exists(directory.resolve("ready"), LinkOption.NOFOLLOW_LINKS)) { verifyPublished(directory, manifest); return manifest; }
        if (Files.exists(directory.resolve("artifact"), LinkOption.NOFOLLOW_LINKS)) {
            verifyPublished(directory, manifest); atomic(directory.resolve("ready"), manifest.id().getBytes(java.nio.charset.StandardCharsets.US_ASCII)); return manifest;
        }
        Path temporary = directory.resolve("assembling"); MessageDigest digest = ArtifactManifest.digest(); long length = 0;
        try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            for (int n = 0; n < manifest.chunkHashes().size(); n++) { byte[] bytes = read(directory.resolve("chunk-" + n), manifest.chunkSize()); verifyChunk(manifest, n, bytes); digest.update(bytes); length += bytes.length;
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) output.write(buffer); }
            output.force(true);
        }
        if (length != manifest.length() || !HexFormat.of().formatHex(digest.digest()).equals(manifest.contentHash())) throw new IOException("Complete artifact hash/length mismatch");
        Files.move(temporary, directory.resolve("artifact"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); force(directory);
        atomic(directory.resolve("ready"), manifest.id().getBytes(java.nio.charset.StandardCharsets.US_ASCII)); return manifest;
    }
    /**
     * Reads signed metadata, including staging metadata; never implies readiness.
     * @param tenant bound scope
     * @param id SHA-256 manifest identity
     * @return verified manifest
     * @throws IOException for absent or tampered metadata
     */
    public synchronized ArtifactManifest manifest(String tenant, String id) throws IOException {
        ArtifactManifest manifest = ArtifactManifest.decode(read(directory(tenant, id).resolve("manifest"), 2097152));
        authorize(tenant, manifest); if (!manifest.id().equals(id)) throw new IOException("Manifest identity mismatch"); return manifest;
    }
    /**
     * Downloads one published chunk, rejecting all staging artifacts.
     * @param tenant bound scope
     * @param id manifest identity
     * @param index chunk index
     * @return verified chunk bytes
     * @throws IOException when artifact is not ready or chunk is corrupt
     */
    public synchronized byte[] download(String tenant, String id, int index) throws IOException {
        ArtifactManifest manifest = manifest(tenant, id);
        if (!Files.exists(directory(tenant, id).resolve("ready"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Artifact not published");
        byte[] bytes = read(directory(tenant, id).resolve("chunk-" + index), manifest.chunkSize()); verifyChunk(manifest, index, bytes); return bytes;
    }
    /**
     * Fetches only a fully verified immutable release.
     * @param tenant bound scope
     * @param id manifest identity
     * @return published manifest
     * @throws IOException for staging, absent or corrupted release
     */
    public synchronized ArtifactManifest fetch(String tenant, String id) throws IOException {
        ArtifactManifest manifest = manifest(tenant, id); Path directory = directory(tenant, id);
        if (!Files.exists(directory.resolve("ready"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Artifact not published");
        verifyPublished(directory, manifest); return manifest;
    }
    /**
     * Imports a published artifact through the existing repository's bundle verification and atomic publish.
     * @param tenant bound scope
     * @param id complete artifact identity
     * @param options local authorized publish policy; remote callers never select verification policy
     * @return existing repository publication result
     * @throws IOException for incomplete artifacts or unavailable integration
     */
    public synchronized ApplicationDeploymentResult publishApplication(String tenant, String id, RepositoryPublishOptions options) throws IOException {
        if (applications == null) throw new IOException("Application publication integration unavailable");
        ArtifactManifest manifest = fetch(tenant, id); ApplicationCoordinate coordinate = ApplicationCoordinate.parse(manifest.coordinate());
        try {
            ApplicationBundle existing = applications.fetch(coordinate);
            boolean verified = applications instanceof LocalApplicationRepository local
                    ? local.verifiedForActivation(coordinate, ApplicationBundleVerifier.policy(options.verificationProfile(), options.strictVerification()))
                    : applications.verify(coordinate).accepted();
            if (!existing.sha256().equals(manifest.contentHash()) || !verified) throw new IOException("Existing application publication conflicts with content or policy");
            return new ApplicationDeploymentResult(true, ApplicationRunState.DEPLOYED, "already published", List.of());
        } catch (org.j7m.middleware.api.MiddlewareException absent) {
            if (absent.code() != org.j7m.middleware.api.MiddlewareErrorCode.NOT_FOUND) throw absent;
        }
        return applications.publish(new ApplicationBundle(directory(tenant, id).resolve("artifact"), coordinate, manifest.contentHash()), options);
    }
    private static long reservation(ArtifactManifest manifest) { return 3 * manifest.length() + 2L * manifest.encode().length + 4096; }
    private void authorize(String tenant, ArtifactManifest manifest) { ArtifactManifest.identifier(tenant); if (!tenant.equals(manifest.tenant())) throw new SecurityException("Artifact tenant mismatch"); manifest.verify(trust); }
    private Path directory(String tenant, String id) throws IOException { ArtifactManifest.identifier(tenant); if (id == null || !id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid artifact identity");
        Path path = root.resolve(ArtifactManifest.hash(tenant.getBytes(java.nio.charset.StandardCharsets.UTF_8))).resolve(id); safe(path); return path; }
    private void safe(Path path) throws IOException { if (!path.normalize().startsWith(root)) throw new IOException("Artifact traversal"); Path current = root; for (Path segment : root.relativize(path)) { current = current.resolve(segment); if (Files.isSymbolicLink(current)) throw new IOException("Symlink artifact path"); } }
    private byte[] read(Path path, int maximum) throws IOException { safe(path); long size = Files.size(path); if (size > maximum) throw new IOException("Artifact file bound"); return Files.readAllBytes(path); }
    private static void verifyChunk(ArtifactManifest manifest, int index, byte[] bytes) throws IOException { if (index < 0 || index >= manifest.chunkHashes().size()) throw new IOException("Chunk index");
        long expected = Math.min(manifest.chunkSize(), manifest.length() - (long) index * manifest.chunkSize());
        if (bytes.length != expected || !ArtifactManifest.hash(bytes).equals(manifest.chunkHashes().get(index))) throw new IOException("Chunk hash/length mismatch"); }
    private void verifyPublished(Path directory, ArtifactManifest manifest) throws IOException { Path path = directory.resolve("artifact"); safe(path); if (Files.size(path) != manifest.length()) throw new IOException("Published artifact length");
        MessageDigest digest = ArtifactManifest.digest(); try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { byte[] bytes; while ((bytes = input.readNBytes(65536)).length > 0) digest.update(bytes); }
        if (!HexFormat.of().formatHex(digest.digest()).equals(manifest.contentHash())) throw new IOException("Published artifact digest"); }
    static void atomic(Path path, byte[] bytes) throws IOException { Path temporary = path.resolveSibling(path.getFileName() + ".pending");
        try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) output.write(buffer); output.force(true); }
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); force(path.getParent()); }
    private static void force(Path directory) throws IOException { try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); } }
    /**
     * {@inheritDoc}
     */
    @Override public void close() throws IOException { ownership.close(); }
}
