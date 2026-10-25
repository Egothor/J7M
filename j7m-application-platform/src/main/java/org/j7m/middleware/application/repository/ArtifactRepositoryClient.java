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
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.PublicKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.*;
import org.j7m.middleware.application.api.*;

/**
 * Authenticated resumable client with a private persistent transfer cache. Chunk acknowledgements,
 * verified downloaded chunks and signed metadata survive process restart. A deadline or lost response
 * may leave an UNKNOWN server outcome; identical chunk and manifest retries are safe. This client
 * never activates or loads artifact code and does not depend on runtime discovery.
 */
public final class ArtifactRepositoryClient implements AutoCloseable {
    private final List<InetSocketAddress> endpoints;
    private final String principal;
    private final byte[] key;
    private final Map<String, PublicKey> trust;
    private final Path cache;
    private final SSLContext tls;
    private final long cacheQuota;
    private final int maximumArtifacts;
    private final ArtifactFileOwnership ownership;
    private final AtomicLong uploads = new AtomicLong(), downloads = new AtomicLong();
    /**
     * Creates a persistent client; use one owner per private transfer-cache directory.
     * @param endpoints explicit server endpoints for one logical repository
     * @param principal authenticated configured identity
     * @param key principal-specific authentication key
     * @param trust immutable manifest signing trust anchors
     * @param cache private durable cache directory
     * @param tls mutual TLS context, required outside loopback
     * @throws IOException for cache or ownership failure
     * @throws IllegalArgumentException for unsafe endpoints or credentials
     */
    public ArtifactRepositoryClient(List<InetSocketAddress> endpoints, String principal, byte[] key,
            Map<String, PublicKey> trust, Path cache, SSLContext tls) throws IOException {
        this(endpoints, principal, key, trust, cache, tls, 268435456L, 128);
    }
    /**
     * Creates a client with explicit durable cache quotas.
     * @param endpoints explicit repository addresses
     * @param principal configured principal
     * @param key authentication key
     * @param trust signer trust anchors
     * @param cache private cache directory
     * @param tls mutual TLS context outside loopback
     * @param cacheQuota reserved logical cache bytes, including metadata/recovery headroom
     * @param maximumArtifacts maximum cached manifest identities
     * @throws IOException for cache or locking failure
     * @throws IllegalArgumentException for invalid bounds
     */
    public ArtifactRepositoryClient(List<InetSocketAddress> endpoints, String principal, byte[] key,
            Map<String, PublicKey> trust, Path cache, SSLContext tls, long cacheQuota, int maximumArtifacts) throws IOException {
        ArtifactManifest.identifier(principal);
        if (cacheQuota < 8192 || cacheQuota > 1099511627776L || maximumArtifacts < 1 || maximumArtifacts > 100000) throw new IllegalArgumentException("Artifact cache quota");
        this.cacheQuota = cacheQuota; this.maximumArtifacts = maximumArtifacts;
        if (endpoints.isEmpty() || key.length < 32 || endpoints.stream().anyMatch(address -> address.isUnresolved()
                || tls == null && !address.getAddress().isLoopbackAddress())) throw new IllegalArgumentException("Unsafe artifact client");
        this.endpoints = List.copyOf(endpoints); this.principal = principal; this.key = key.clone(); this.trust = Map.copyOf(trust); this.tls = tls;
        this.cache = cache.toAbsolutePath().normalize(); Files.createDirectories(this.cache); if (Files.isSymbolicLink(this.cache)) throw new IOException("Symlink cache");
        ownership = new ArtifactFileOwnership(this.cache.resolve("owner.lock"));
    }
    /**
     * Begins/resumes an authorized upload with signature verification at both ends.
     * @param manifest signed artifact metadata
     * @param deadline absolute deadline
     * @return remotely verified retained chunk indexes
     * @throws IOException for transport/disk/quota failure; outcome may be UNKNOWN
     */
    public synchronized Set<Integer> begin(ArtifactManifest manifest, Instant deadline) throws IOException {
        manifest.verify(trust); persistManifest(manifest);
        return indexes(exchange("BEGIN", manifest.encode(), deadline), manifest);
    }
    /**
     * Reports retained remote chunks without transferring payload bytes.
     * @param manifest signed metadata
     * @param deadline absolute deadline
     * @return verified remote indexes
     * @throws IOException on transport or malformed response
     */
    public Set<Integer> resume(ArtifactManifest manifest, Instant deadline) throws IOException {
        manifest.verify(trust); return indexes(exchange("RESUME", id(manifest.id()), deadline), manifest);
    }
    /**
     * Uploads one idempotent chunk and durably records its acknowledgement locally.
     * @param manifest signed metadata
     * @param index chunk index
     * @param chunk complete chunk bytes
     * @param deadline absolute deadline
     * @throws IOException for hash, transport or persistent-cache failure
     */
    public synchronized void uploadChunk(ArtifactManifest manifest, int index, byte[] chunk, Instant deadline) throws IOException {
        manifest.verify(trust); verifyChunk(manifest, index, chunk); persistManifest(manifest);
        exchange("PUT", ArtifactManifest.encode(out -> { out.writeUTF(manifest.id()); out.writeInt(index); ArtifactProtocol.bytes(out, chunk); }), deadline);
        ResumableArtifactStore.atomic(directory(manifest.id()).resolve("uploaded-" + index), manifest.chunkHashes().get(index).getBytes(java.nio.charset.StandardCharsets.US_ASCII)); uploads.incrementAndGet();
    }
    /**
     * Resumes all missing chunks and commits whole-artifact publication.
     * @param source immutable source file matching manifest
     * @param manifest signed metadata
     * @param deadline absolute deadline
     * @return number of payload chunks transferred by this invocation
     * @throws IOException for changed source, transport, deadline or verification failure
     */
    public synchronized int upload(Path source, ArtifactManifest manifest, Instant deadline) throws IOException {
        Set<Integer> present = begin(manifest, deadline); int transferred = 0;
        if (Files.size(source) != manifest.length()) throw new IOException("Source length changed");
        try (InputStream in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
            for (int index = 0; index < manifest.chunkHashes().size(); index++) { byte[] chunk = in.readNBytes(manifest.chunkSize()); verifyChunk(manifest, index, chunk);
                if (!present.contains(index)) { uploadChunk(manifest, index, chunk, deadline); transferred++; } }
            if (in.read() != -1) throw new IOException("Source length changed");
        }
        commitManifest(manifest.id(), deadline); return transferred;
    }
    /**
     * Atomically publishes only a whole verified artifact; incomplete uploads are rejected.
     * @param id signed manifest identity
     * @param deadline absolute deadline
     * @return verified published manifest
     * @throws IOException on incomplete or unavailable server state
     */
    public ArtifactManifest commitManifest(String id, Instant deadline) throws IOException { return verifyManifest(exchange("COMMIT", id(id), deadline), id); }
    /**
     * Fetches only a published release, rejecting staging.
     * @param id manifest identity
     * @param deadline absolute deadline
     * @return trusted published manifest
     * @throws IOException for unavailable, incomplete or corrupted release
     */
    public synchronized ArtifactManifest fetch(String id, Instant deadline) throws IOException {
        ArtifactManifest manifest = verifyManifest(exchange("FETCH", id(id), deadline), id); persistManifest(manifest); return manifest;
    }
    /**
     * Downloads and fsyncs one missing verified chunk; existing verified chunks are never retransmitted.
     * @param manifest trusted published metadata
     * @param index chunk index
     * @param deadline absolute deadline
     * @return true when network payload was transferred, false when verified cache sufficed
     * @throws IOException for corrupt local/remote chunk or unavailable server
     */
    public synchronized boolean downloadChunk(ArtifactManifest manifest, int index, Instant deadline) throws IOException {
        manifest.verify(trust); persistManifest(manifest); Path path = directory(manifest.id()).resolve("download-" + index);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { verifyChunk(manifest, index, read(path, manifest.chunkSize())); return false; }
        byte[] chunk = exchange("GET", ArtifactManifest.encode(out -> { out.writeUTF(manifest.id()); out.writeInt(index); }), deadline);
        verifyChunk(manifest, index, chunk); ResumableArtifactStore.atomic(path, chunk); downloads.incrementAndGet(); return true;
    }
    /**
     * Resumes download and atomically installs a fully verified destination file.
     * @param id manifest identity
     * @param destination trusted caller-selected local output path
     * @param deadline absolute deadline
     * @return number of chunks transferred by this invocation
     * @throws IOException on incomplete, corrupt, interrupted or conflicting output
     */
    public synchronized int download(String id, Path destination, Instant deadline) throws IOException {
        ArtifactManifest manifest = fetch(id, deadline); int transferred = 0;
        for (int n = 0; n < manifest.chunkHashes().size(); n++) if (downloadChunk(manifest, n, deadline)) transferred++;
        Path absolute = destination.toAbsolutePath().normalize(); if (Files.isSymbolicLink(absolute)) throw new IOException("Symlink destination");
        Files.createDirectories(absolute.getParent()); Path temporary = absolute.resolveSibling(absolute.getFileName() + ".artifact-pending");
        var digest = ArtifactManifest.digest(); long length = 0;
        try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            for (int n = 0; n < manifest.chunkHashes().size(); n++) { byte[] chunk = read(directory(id).resolve("download-" + n), manifest.chunkSize()); verifyChunk(manifest, n, chunk); digest.update(chunk); length += chunk.length;
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(chunk); while (buffer.hasRemaining()) output.write(buffer); } output.force(true);
        }
        if (length != manifest.length() || !HexFormat.of().formatHex(digest.digest()).equals(manifest.contentHash())) throw new IOException("Downloaded artifact digest mismatch");
        Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (FileChannel parent = FileChannel.open(absolute.getParent(), StandardOpenOption.READ)) { parent.force(true); } return transferred;
    }
    /**
     * Imports a completed artifact through the server's existing bundle verifier and repository.
     * @param id published manifest identity
     * @param deadline absolute deadline
     * @return application repository publication result
     * @throws IOException for staging, missing integration or rejected transport
     */
    public ApplicationDeploymentResult publishApplication(String id, Instant deadline) throws IOException {
        DataInputStream in = ArtifactProtocol.input(exchange("PUBLISH", id(id), deadline));
        boolean success = in.readBoolean(); ApplicationRunState state = ApplicationRunState.valueOf(in.readUTF()); String message = in.readUTF();
        int count = in.readInt(); if (count < 0 || count > 64) throw new IOException("Publication details bound"); List<String> details = new ArrayList<>(); for (int n = 0; n < count; n++) details.add(in.readUTF());
        var result = new ApplicationDeploymentResult(success, state, message, details); ArtifactProtocol.end(in); return result;
    }
    /**
     * Replicates a published application through authenticated source and target repository endpoints.
     * Uses the source's durable verified download cache and the target's resumable upload journal;
     * callers need no transport glue. Destination application publication still applies its own local
     * bundle verification policy. Retries reuse the exact signed manifest and retained chunks.
     * @param target separately authorized destination repository client
     * @param id source published signed manifest identity
     * @param deadline absolute deadline covering download, upload and application publication
     * @return destination application's publication result
     * @throws IOException for incomplete, unauthorized, corrupt or unavailable replication
     */
    public ApplicationDeploymentResult replicateTo(ArtifactRepositoryClient target, String id, Instant deadline) throws IOException {
        Objects.requireNonNull(target); Path source = directory(id).resolve("replication-source");
        download(id, source, deadline); ArtifactManifest manifest = fetch(id, deadline);
        target.upload(source, manifest, deadline); return target.publishApplication(id, deadline);
    }
    /**
     * Counts actual acknowledged upload calls in this client process.
     * @return network upload count
     */
    public long uploadedChunks() { return uploads.get(); }
    /**
     * Counts actual downloaded payloads in this client process.
     * @return network download count
     */
    public long downloadedChunks() { return downloads.get(); }
    byte[] exchange(String operation, byte[] payload, Instant deadline) throws IOException {
        IOException last = null;
        while (Instant.now().isBefore(deadline)) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Artifact cancelled; outcome UNKNOWN");
            for (InetSocketAddress address : endpoints) {
                long remaining = deadline.toEpochMilli() - System.currentTimeMillis(); if (remaining <= 0) break;
                String nonce = UUID.randomUUID().toString(); long expiry = Math.min(deadline.toEpochMilli(), System.currentTimeMillis() + 30000);
                byte[] request = ArtifactManifest.encode(out -> { out.writeInt(1); out.writeUTF(principal); out.writeUTF(nonce); out.writeLong(expiry); out.writeUTF(operation); ArtifactProtocol.bytes(out, payload); });
                try (Socket socket = tls == null ? new Socket() : tls.getSocketFactory().createSocket()) {
                    int timeout = (int) Math.min(remaining, 2000); socket.connect(address, timeout); socket.setSoTimeout(timeout); socket.setTcpNoDelay(true);
                    if (socket instanceof SSLSocket secure) { SSLParameters parameters = secure.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS"); secure.setSSLParameters(parameters); secure.startHandshake(); }
                    ArtifactProtocol.send(new DataOutputStream(socket.getOutputStream()), request, key);
                    DataInputStream input = new DataInputStream(socket.getInputStream()); byte[] body = ArtifactProtocol.receive(input); ArtifactProtocol.authenticate(input, body, key);
                    DataInputStream in = ArtifactProtocol.input(body); if (!in.readUTF().equals(nonce)) throw new IOException("Artifact reply correlation");
                    int status = in.readInt(); byte[] result = ArtifactProtocol.bytes(in, ArtifactProtocol.MAX_FRAME); ArtifactProtocol.end(in);
                    if (status == 1) throw new SecurityException("Artifact operation denied");
                    if (status == 2) throw new RepositoryRejectedException("Artifact incomplete, quota exceeded or disk unavailable");
                    if (status != 0) throw new IllegalArgumentException("Artifact request malformed"); return result;
                } catch (RepositoryRejectedException rejected) { throw rejected; }
                catch (IOException unavailable) { last = unavailable; }
            }
            java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
        }
        throw new IOException("Artifact deadline; outcome UNKNOWN", last);
    }
    private ArtifactManifest verifyManifest(byte[] bytes, String id) throws IOException { ArtifactManifest manifest = ArtifactManifest.decode(bytes); manifest.verify(trust); if (!manifest.id().equals(id)) throw new IOException("Artifact identity mismatch"); return manifest; }
    private void persistManifest(ArtifactManifest manifest) throws IOException { Path directory = directory(manifest.id()); Path path = directory.resolve("manifest");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { if (!Arrays.equals(read(path, 2097152), manifest.encode())) throw new IOException("Cached manifest conflict"); }
        else {
            long reserved = 0; int count = 0;
            try (var files = Files.list(cache)) { for (Path artifact : files.filter(item -> Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS)
                    && item.getFileName().toString().matches("[0-9a-f]{64}") && !item.equals(directory)).toList()) {
                if (Files.isSymbolicLink(artifact)) throw new IOException("Symlink cache entry"); count++;
                Path metadata = artifact.resolve("manifest"); boolean committed = Files.exists(metadata, LinkOption.NOFOLLOW_LINKS);
                if (!committed) metadata = artifact.resolve("manifest.pending");
                if (!Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) { reserved += 4096; continue; }
                try { reserved += cacheReservation(ArtifactManifest.decode(read(metadata, 2097152))); }
                catch (IllegalArgumentException invalid) { if (committed) throw new IOException("Corrupt cached manifest", invalid); reserved += read(metadata, 2097152).length + 4096L; }
            } }
            if (count >= maximumArtifacts || cacheReservation(manifest) > cacheQuota - reserved) throw new IOException("Artifact client cache quota exceeded");
            Files.createDirectories(directory); ResumableArtifactStore.atomic(path, manifest.encode());
        } }
    private static long cacheReservation(ArtifactManifest manifest) { return 3 * manifest.length() + 2L * manifest.encode().length + 4096; }
    private Path directory(String id) throws IOException { if (id == null || !id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Artifact identity"); Path directory = cache.resolve(id); if (Files.isSymbolicLink(directory)) throw new IOException("Symlink transfer cache"); return directory; }
    private static byte[] id(String id) { if (id == null || !id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Artifact identity"); return ArtifactManifest.encode(out -> out.writeUTF(id)); }
    private static byte[] read(Path path, int maximum) throws IOException { if (Files.isSymbolicLink(path) || Files.size(path) > maximum) throw new IOException("Cached artifact bound"); return Files.readAllBytes(path); }
    private static void verifyChunk(ArtifactManifest manifest, int index, byte[] chunk) throws IOException { if (index < 0 || index >= manifest.chunkHashes().size() || chunk.length != Math.min(manifest.chunkSize(), manifest.length() - (long) index * manifest.chunkSize())
            || !ArtifactManifest.hash(chunk).equals(manifest.chunkHashes().get(index))) throw new IOException("Artifact chunk digest/length mismatch"); }
    private static Set<Integer> indexes(byte[] bytes, ArtifactManifest manifest) throws IOException { DataInputStream in = ArtifactProtocol.input(bytes); int count = in.readInt(); if (count < 0 || count > manifest.chunkHashes().size()) throw new IOException("Artifact resume count"); Set<Integer> result = new TreeSet<>();
        for (int n = 0; n < count; n++) { int index = in.readInt(); if (index < 0 || index >= manifest.chunkHashes().size() || !result.add(index)) throw new IOException("Artifact resume index"); } ArtifactProtocol.end(in); return Collections.unmodifiableSet(result); }
    private static final class RepositoryRejectedException extends IOException { RepositoryRejectedException(String message) { super(message); } }
    /**
     * {@inheritDoc}
     */
    @Override public void close() throws IOException { ownership.close(); }
}
