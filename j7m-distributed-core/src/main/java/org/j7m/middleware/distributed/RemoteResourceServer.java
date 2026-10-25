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
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;

/**
 * Bounded authenticated server for explicitly bound resource principals. Requests cannot select tenant
 * scope or elevate to the coordinator role. HMAC-only transport is limited to loopback development;
 * other binds require mutual TLS. Lifecycle is owned by the caller; closing interrupts active connections.
 */
public final class RemoteResourceServer implements AutoCloseable {
    /**
     * Immutable authenticated service binding. Supply an already scoped, permission-checked local endpoint.
     * @param endpoint authorized local resource group facade
     * @param authenticationKey principal-specific secret of at least 32 bytes
     * @param coordinator whether this service may deliver/record transaction decisions or advance measured logical time
     */
    public record Binding(ResourceEndpoint endpoint, byte[] authenticationKey, boolean coordinator) {
        /**
         * Validates and copies the secret; no request may override this binding.
         */
        public Binding {
            Objects.requireNonNull(endpoint); authenticationKey = authenticationKey.clone();
            if (authenticationKey.length < 32) throw new IllegalArgumentException("Resource key must have at least 32 bytes");
        }
        /**
         * Returns a defensive copy of the principal secret.
         * @return independent secret bytes
         */
        @Override public byte[] authenticationKey() { return authenticationKey.clone(); }
    }
    private static final Set<String> DECISION_OPERATIONS = Set.of("COMMIT", "ROLLBACK", "DECIDE", "SEAL", "TIME");
    private final ServerSocket server;
    private final Map<String, Binding> principals;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> nonces = new HashMap<>();
    private final int maximumNonces;
    private final ThreadPoolExecutor workers;
    private final Thread acceptor;
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong nonceCapacityRejected = new AtomicLong();
    private final AtomicLong dispatched = new AtomicLong();
    private volatile boolean closed;
    private final java.util.function.Consumer<String> testProbe;
    /**
     * Binds and starts a bounded server with four handlers and at most sixteen queued connections.
     * @param bind explicit listening address, port zero chooses an ephemeral port
     * @param principals configured identities and immutable scope/role bindings with distinct keys
     * @param tls TLS context, required outside loopback
     * @throws IOException if bind fails
     * @throws IllegalArgumentException for invalid identities, reused keys or an insecure nonloopback bind
     */
    public RemoteResourceServer(InetSocketAddress bind, Map<String, Binding> principals, SSLContext tls) throws IOException {
        this(bind, principals, tls, 8192, ignored -> { });
    }
    RemoteResourceServer(InetSocketAddress bind, Map<String, Binding> principals, SSLContext tls,
            java.util.function.Consumer<String> testProbe) throws IOException {
        this(bind,principals,tls,8192,testProbe);
    }
    /**
     * Binds with an explicit authenticated anti-replay admission budget.
     * The compatibility constructor retains 8192 entries; high-throughput profiles may provision a
     * larger bounded capacity. Entries live at most 30 seconds and overflow fails before dispatch.
     * @param bind explicit listener
     * @param principals immutable distinct authentication and permission bindings
     * @param tls mutual TLS context outside loopback
     * @param maximumNonces retained authenticated nonce bound, 1..65536
     * @throws IOException bind failure
     * @throws IllegalArgumentException invalid capacity or unsafe binding
     */
    public RemoteResourceServer(InetSocketAddress bind, Map<String,Binding> principals,SSLContext tls,int maximumNonces)throws IOException {
        this(bind,principals,tls,maximumNonces,ignored -> { });
    }
    private RemoteResourceServer(InetSocketAddress bind,Map<String,Binding> principals,SSLContext tls,
            int maximumNonces,java.util.function.Consumer<String> testProbe)throws IOException {
        if(maximumNonces<1||maximumNonces>65536)throw new IllegalArgumentException("Resource nonce capacity");
        this.maximumNonces=maximumNonces;
        this.testProbe = Objects.requireNonNull(testProbe);
        this.principals = Map.copyOf(principals);
        if (bind.isUnresolved() || (tls == null && !bind.getAddress().isLoopbackAddress()) || principals.isEmpty()) throw new IllegalArgumentException("Resource server requires explicit principals and secure binding");
        List<byte[]> keys = new ArrayList<>();
        for (Map.Entry<String, Binding> item : this.principals.entrySet()) {
            if (item.getKey().isBlank() || item.getKey().length() > 128 || keys.stream().anyMatch(k -> java.security.MessageDigest.isEqual(k, item.getValue().authenticationKey()))) {
                throw new IllegalArgumentException("Resource principals require distinct identities and keys");
            }
            keys.add(item.getValue().authenticationKey());
        }
        server = tls == null ? new ServerSocket() : tls.getServerSocketFactory().createServerSocket();
        if (server instanceof javax.net.ssl.SSLServerSocket secure) secure.setNeedClientAuth(true);
        server.bind(bind, 16);
        workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), runnable -> {
            Thread thread = new Thread(runnable, "resource-handler"); thread.setDaemon(true); return thread;
        });
        acceptor = new Thread(this::accept, "resource-acceptor"); acceptor.setDaemon(true); acceptor.start();
    }
    /**
     * Returns the listening address.
     * @return actual bound address, including the assigned port
     */
    public InetSocketAddress address() { return (InetSocketAddress) server.getLocalSocketAddress(); }
    /**
     * Counts dispatched authorized requests.
     * @return number of requests reaching the authorized local facade
     */
    public long dispatchedRequests() { return dispatched.get(); }
    /**
     * Counts rejected connections.
     * @return rejected malformed, unauthenticated, replayed, or overloaded connections
     */
    public long rejectedRequests() { return rejected.get(); }
    /**
     * Counts authenticated requests rejected because bounded nonce retention is full.
     * @return monotonic anti-replay admission overload count
     */
    public long nonceCapacityRejections() { return nonceCapacityRejected.get(); }
    private void accept() {
        while (!closed) {
            try {
                Socket socket = server.accept(); socket.setSoTimeout(5000); sockets.add(socket);
                try { workers.execute(() -> handle(socket)); }
                catch (RejectedExecutionException e) { rejected.incrementAndGet(); sockets.remove(socket); socket.close(); }
            } catch (IOException e) { if (!closed) rejected.incrementAndGet(); }
        }
    }
    private void handle(Socket socket) {
        try (socket) {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            byte[] body = ResourceWire.receive(input);
            try (DataInputStream decoded = new DataInputStream(new ByteArrayInputStream(body))) {
                if (decoded.readInt() != 1) throw new IOException("Unsupported resource protocol");
                String principal = ResourceWire.text(decoded);
                Binding binding = principals.get(principal);
                if (binding == null) throw new IOException("Unauthenticated resource principal");
                ResourceWire.authenticate(input, body, binding.authenticationKey());
                String nonce = ResourceWire.text(decoded);
                if (nonce.length() != 36) throw new IOException("Invalid request nonce");
                long expiry = decoded.readLong(); long now = System.currentTimeMillis();
                if (expiry <= now || expiry > now + 30000) throw new IOException("Resource deadline outside development profile");
                synchronized (nonces) {
                    nonces.values().removeIf(deadline -> deadline <= now);
                    if (nonces.size() >= maximumNonces) { nonceCapacityRejected.incrementAndGet(); throw new IOException("Resource replay capacity exceeded"); }
                    if (nonces.putIfAbsent(principal + ':' + nonce, expiry) != null) throw new IOException("Resource replay rejected");
                }
                String type = ResourceWire.text(decoded), operation = ResourceWire.text(decoded), request = ResourceWire.text(decoded);
                List<String> arguments = ResourceWire.strings(decoded);
                if (decoded.available() != 0) throw new IOException("Trailing resource request data");
                byte[] reply;
                try {
                    if (request.length() > 65536 || (!binding.coordinator() && (DECISION_OPERATIONS.contains(operation) || type.equals("ADVANCE_TIME")))) throw new SecurityException("Coordinator service identity required");
                    Instant deadline = Instant.ofEpochMilli(expiry);
                    dispatched.incrementAndGet();
                    testProbe.accept("REQUEST " + request);
                    if (type.equals("SNAPSHOT_TAIL")) {
                        var image = binding.endpoint().snapshotAndTail(deadline); byte[] imageBytes = image.snapshot();
                        DataOutputStream stream = new DataOutputStream(socket.getOutputStream());
                        ResourceWire.send(stream, ResourceWire.encode(out -> { ResourceWire.text(out, nonce); out.writeInt(0); out.writeInt(imageBytes.length); ResourceWire.cursor(out, image.cursor()); out.writeLong(image.index()); }), binding.authenticationKey());
                        for (int offset = 0; offset < imageBytes.length; offset += 65536) {
                            testProbe.accept("SNAPSHOT_CHUNK " + offset);
                            int position = offset, size = Math.min(65536, imageBytes.length - offset);
                            ResourceWire.send(stream, ResourceWire.encode(out -> { ResourceWire.text(out, nonce); out.writeInt(position); out.writeInt(size); out.write(imageBytes, position, size); }), binding.authenticationKey());
                        }
                        return;
                    }
                    ResourceWire.Writer response = dispatch(binding.endpoint(), type, operation, request, arguments, deadline);
                    reply = ResourceWire.encode(out -> { ResourceWire.text(out, nonce); out.writeInt(0); response.write(out); });
                } catch (SecurityException e) {
                    reply = error(nonce, 1, "Resource operation not authorized");
                } catch (IOException e) {
                    reply = error(nonce, 2, e.getMessage() != null && (e.getMessage().equals("CURSOR_EXPIRED") || e.getMessage().equals("SUBSCRIPTION_EXPIRED")) ? e.getMessage() : "Resource endpoint unavailable; outcome UNKNOWN");
                } catch (RuntimeException e) {
                    reply = error(nonce, 3, "Invalid resource request");
                }
                ResourceWire.send(new DataOutputStream(socket.getOutputStream()), reply, binding.authenticationKey());
            }
        } catch (IOException | RuntimeException e) { rejected.incrementAndGet(); }
        finally { sockets.remove(socket); }
    }
    private static byte[] error(String nonce, int status, String message) throws IOException {
        return ResourceWire.encode(out -> { ResourceWire.text(out, nonce); out.writeInt(status); ResourceWire.text(out, message); });
    }
    private static ResourceWire.Writer dispatch(ResourceEndpoint endpoint, String type, String operation, String request,
            List<String> arguments, Instant deadline) throws IOException {
        return switch (type) {
            case "POLL" -> {
                if (arguments.size() != 1) throw new IllegalArgumentException("Poll arguments");
                var page = endpoint.poll(request, Integer.parseInt(arguments.getFirst()), deadline); yield out -> ResourceWire.page(out, page);
            }
            case "EXECUTE" -> { var result = endpoint.execute(operation, request, arguments, deadline); yield out -> ResourceWire.result(out, result); }
            case "READ" -> { var value = endpoint.read(request, deadline); yield out -> {
                out.writeBoolean(value != null); if (value != null) { ResourceWire.text(out, value.value()); out.writeLong(value.version()); }
            }; }
            case "LEASE" -> { var lease = endpoint.lease(request, deadline); yield out -> ResourceWire.lease(out, lease); }
            case "ADVANCE_TIME" -> { var result = endpoint.advanceTime(request, deadline); yield out -> ResourceWire.result(out, result); }
            case "TRANSACTION" -> { var result = endpoint.transaction(request, deadline); yield out -> ResourceWire.result(out, result); }
            case "PREPARED" -> { var ids = endpoint.prepared(deadline); yield out -> ResourceWire.strings(out, ids); }
            case "PARTICIPANTS" -> { var ids = endpoint.participants(request, deadline); yield out -> ResourceWire.strings(out, ids); }
            default -> throw new IOException("Unknown resource operation");
        };
    }
    /**
     * Stops accepting work and closes queued/active sockets; committed commands remain committed.
     * @throws IOException if the listening socket cannot be closed
     */
    @Override public void close() throws IOException {
        closed = true; server.close(); workers.shutdownNow();
        for (Socket socket : sockets) { try { socket.close(); } catch (IOException ignored) { /* All connections are independently closed. */ } }
    }
}
