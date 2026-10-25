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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.*;
import org.j7m.middleware.application.api.RepositoryPublishOptions;

/**
 * Bounded authenticated artifact server with immutable tenant and operation bindings. HMAC-only
 * loopback is a development profile; other binds require mutual TLS. Deadlines, replay and queues
 * are bounded. Publication policy is server configured, never selected by the remote caller.
 */
public final class ArtifactRepositoryServer implements AutoCloseable {
    /**
     * Immutable authorization binding.
     * @param tenant server-bound tenant
     * @param key distinct principal HMAC key, at least 32 bytes
     * @param operations allowed READ, UPLOAD, COMMIT and PUBLISH capabilities
     */
    public record Binding(String tenant, byte[] key, Set<String> operations) {
        /**
         * Validates and copies binding inputs.
         * @param tenant immutable scope
         * @param key authentication secret
         * @param operations allowed capabilities
         */
        public Binding { ArtifactManifest.identifier(tenant); key = key.clone(); operations = Set.copyOf(operations);
            if (key.length < 32 || !Set.of("READ", "UPLOAD", "COMMIT", "PUBLISH").containsAll(operations)) throw new IllegalArgumentException("Artifact binding"); }
        /**
         * Returns independent secret bytes.
         * @return key copy
         */
        @Override public byte[] key() { return key.clone(); }
    }
    private final ResumableArtifactStore store;
    private final Map<String, Binding> principals;
    private final RepositoryPublishOptions publishOptions;
    private final ServerSocket server;
    private final ThreadPoolExecutor workers;
    private final Thread acceptor;
    private final Map<String, Long> nonces = new HashMap<>();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicLong uploaded = new AtomicLong(), downloaded = new AtomicLong(), rejected = new AtomicLong();
    private volatile boolean closed;
    /**
     * Starts an explicitly bound server with bounded workers and mutual TLS outside loopback.
     * @param bind socket address
     * @param store caller-owned durable repository
     * @param principals immutable principal-to-tenant/capability bindings
     * @param publishOptions server-owned bundle publication policy
     * @param tls TLS context, required outside loopback
     * @throws IOException for bind failure
     * @throws IllegalArgumentException for unsafe binding or reused authentication keys
     */
    public ArtifactRepositoryServer(InetSocketAddress bind, ResumableArtifactStore store, Map<String, Binding> principals,
            RepositoryPublishOptions publishOptions, SSLContext tls) throws IOException {
        if (bind.isUnresolved() || (tls == null && !bind.getAddress().isLoopbackAddress()) || principals.isEmpty()) throw new IllegalArgumentException("Unsafe artifact binding");
        this.store = Objects.requireNonNull(store); this.principals = Map.copyOf(principals); this.publishOptions = Objects.requireNonNull(publishOptions);
        List<byte[]> keys = new ArrayList<>(); for (var entry : principals.entrySet()) { ArtifactManifest.identifier(entry.getKey());
            if (keys.stream().anyMatch(key -> java.security.MessageDigest.isEqual(key, entry.getValue().key))) throw new IllegalArgumentException("Reused artifact key"); keys.add(entry.getValue().key); }
        server = tls == null ? new ServerSocket() : tls.getServerSocketFactory().createServerSocket(); if (server instanceof SSLServerSocket secure) secure.setNeedClientAuth(true); server.bind(bind, 16);
        workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), task -> { Thread thread = new Thread(task, "artifact-transfer"); thread.setDaemon(true); return thread; });
        acceptor = new Thread(this::accept, "artifact-acceptor"); acceptor.setDaemon(true); acceptor.start();
    }
    /**
     * Returns the actual listening address.
     * @return bound socket address
     */
    public InetSocketAddress address() { return (InetSocketAddress) server.getLocalSocketAddress(); }
    /**
     * Counts verified chunk upload attempts, including identical retries.
     * @return upload count
     */
    public long uploadedChunks() { return uploaded.get(); }
    /**
     * Counts delivered chunks.
     * @return download count
     */
    public long downloadedChunks() { return downloaded.get(); }
    /**
     * Counts rejected transport connections.
     * @return rejection count
     */
    public long rejectedRequests() { return rejected.get(); }
    private void accept() { while (!closed) try { Socket socket = server.accept(); socket.setTcpNoDelay(true); socket.setSoTimeout(5000); sockets.add(socket);
        try { workers.execute(() -> handle(socket)); } catch (RejectedExecutionException overloaded) { rejected.incrementAndGet(); sockets.remove(socket); socket.close(); }
    } catch (IOException failure) { if (!closed) rejected.incrementAndGet(); } }
    private void handle(Socket socket) {
        try (socket) { DataInputStream input = new DataInputStream(socket.getInputStream()); byte[] body = ArtifactProtocol.receive(input); DataInputStream in = ArtifactProtocol.input(body);
            if (in.readInt() != 1) throw new IOException("Unsupported artifact protocol"); String principal = in.readUTF(); Binding binding = principals.get(principal); if (binding == null) throw new IOException("Unknown artifact principal");
            ArtifactProtocol.authenticate(input, body, binding.key); String nonce = in.readUTF(); long expiry = in.readLong(), now = System.currentTimeMillis();
            if (nonce.length() != 36 || expiry <= now || expiry > now + 30000) throw new IOException("Artifact deadline");
            synchronized (nonces) { nonces.values().removeIf(value -> value <= now); if (nonces.size() >= 8192 || nonces.putIfAbsent(principal + ':' + nonce, expiry) != null) throw new IOException("Artifact replay or capacity"); }
            String operation = in.readUTF(); byte[] payload = ArtifactProtocol.bytes(in, ArtifactProtocol.MAX_FRAME); ArtifactProtocol.end(in);
            byte[] result; int status;
            try { if (Instant.now().toEpochMilli() >= expiry) throw new IOException("Artifact deadline before dispatch"); result = dispatch(binding, operation, payload); status = 0; }
            catch (SecurityException denied) { result = new byte[0]; status = 1; }
            catch (IOException unavailable) { result = new byte[0]; status = 2; }
            catch (RuntimeException invalid) { result = new byte[0]; status = 3; }
            byte[] responsePayload = result; int code = status;
            ArtifactProtocol.send(new DataOutputStream(socket.getOutputStream()), ArtifactManifest.encode(out -> { out.writeUTF(nonce); out.writeInt(code); ArtifactProtocol.bytes(out, responsePayload); }), binding.key);
        } catch (IOException | RuntimeException failure) { rejected.incrementAndGet(); } finally { sockets.remove(socket); }
    }
    private byte[] dispatch(Binding binding, String operation, byte[] payload) throws IOException {
        String required = switch (operation) { case "BEGIN", "PUT", "RESUME" -> "UPLOAD"; case "COMMIT" -> "COMMIT"; case "PUBLISH" -> "PUBLISH"; case "FETCH", "GET" -> "READ"; default -> throw new IllegalArgumentException("Artifact operation"); };
        if (!binding.operations.contains(required)) throw new SecurityException("Artifact capability denied"); DataInputStream in = ArtifactProtocol.input(payload);
        if (operation.equals("BEGIN")) { var manifest = ArtifactManifest.decode(payload); var retained = store.begin(binding.tenant, manifest); return indexes(retained); }
        String id = in.readUTF();
        return switch (operation) {
            case "RESUME" -> { ArtifactProtocol.end(in); yield indexes(store.retained(binding.tenant, id)); }
            case "PUT" -> { int index = in.readInt(); byte[] chunk = ArtifactProtocol.bytes(in, 262144); ArtifactProtocol.end(in); store.upload(binding.tenant, id, index, chunk); uploaded.incrementAndGet(); yield new byte[0]; }
            case "COMMIT" -> { ArtifactProtocol.end(in); yield store.commitManifest(binding.tenant, id).encode(); }
            case "FETCH" -> { ArtifactProtocol.end(in); yield store.fetch(binding.tenant, id).encode(); }
            case "GET" -> { int index = in.readInt(); ArtifactProtocol.end(in); byte[] chunk = store.download(binding.tenant, id, index); downloaded.incrementAndGet(); yield chunk; }
            case "PUBLISH" -> { ArtifactProtocol.end(in); var result = store.publishApplication(binding.tenant, id, publishOptions); yield ArtifactManifest.encode(out -> { out.writeBoolean(result.success()); out.writeUTF(result.state().name()); out.writeUTF(result.message()); var details = result.details().stream().limit(64).toList(); out.writeInt(details.size());
                for (String detail : details) out.writeUTF(detail.substring(0, Math.min(detail.length(), 1024))); }); }
            default -> throw new IllegalArgumentException("Artifact operation");
        };
    }
    private static byte[] indexes(Set<Integer> indexes) { return ArtifactManifest.encode(out -> { out.writeInt(indexes.size()); for (int index : indexes) out.writeInt(index); }); }
    /**
     * {@inheritDoc}
     */
    @Override public void close() throws IOException { closed = true; server.close(); workers.shutdownNow(); for (Socket socket : sockets) socket.close(); acceptor.interrupt(); }
}
