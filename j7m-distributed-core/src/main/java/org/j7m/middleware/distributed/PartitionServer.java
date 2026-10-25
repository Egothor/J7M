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
import javax.net.ssl.*;
import org.j7m.middleware.consensus.NetworkConsensusNode;

/**
 * Bounded authenticated partition and topology service. Service bindings permanently select scope
 * and permissions; application principals cannot issue handoff or topology commands. Transfer images
 * are streamed in 128 KiB authenticated chunks and staged durably through consensus.
 */
public final class PartitionServer implements AutoCloseable {
    /**
     * Immutable server-authorized principal.
     * @param scope bound tenant/principal scope
     * @param key distinct secret of at least 32 bytes
     * @param administrative permission to manage topology and handoffs
     * @param maintenance permission for resource time/maintenance commands
     */
    public record Binding(String scope, byte[] key, boolean administrative, boolean maintenance) {
        /**
         * Validates and copies authentication material.
         * @param scope server-bound scope
         * @param key authentication key
         * @param administrative topology authority permission
         * @param maintenance resource maintenance permission
         */
        public Binding { PartitionStateMachine.identity(scope); key = key.clone(); if (key.length < 32) throw new IllegalArgumentException("Short key"); }
        /**
         * Returns independent key bytes.
         * @return key copy
         */
        @Override public byte[] key() { return key.clone(); }
    }
    private final ServerSocket server;
    private final NetworkConsensusNode node;
    private final PartitionStateMachine partition;
    private final PartitionTopology topology;
    private final Map<String, Binding> principals;
    private final ReplicatedResourceClient.ClockHealth clock;
    private final Map<String, Long> replay = new HashMap<>();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers;
    private final Thread acceptor;
    private final QueuePushHub queuePush;
    private final ExecutionPushHub executionPush;
    private final java.util.concurrent.atomic.AtomicLong rejected = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean closed;

    /**
     * Hosts either a partition or the independent topology consensus group.
     * @param bind explicit listening address
     * @param node consensus host
     * @param partition partition state, or null for topology host
     * @param topology topology state, or null for partition host
     * @param principals immutable principal permissions with distinct secrets
     * @param clock measured authenticated clock provider
     * @param tls mutual TLS context, required outside loopback
     * @throws IOException on bind failure
     * @throws IllegalArgumentException for insecure binding or mismatched host
     */
    public PartitionServer(InetSocketAddress bind, NetworkConsensusNode node, PartitionStateMachine partition,
            PartitionTopology topology, Map<String, Binding> principals, ReplicatedResourceClient.ClockHealth clock,
            SSLContext tls) throws IOException {
        if ((partition == null) == (topology == null) || !node.hosts(partition == null ? topology : partition)
                || bind.isUnresolved() || (tls == null && !bind.getAddress().isLoopbackAddress()) || principals.isEmpty())
            throw new IllegalArgumentException("Invalid partition host");
        this.node = node; this.partition = partition; this.topology = topology; this.clock = Objects.requireNonNull(clock);
        this.principals = Map.copyOf(principals); List<byte[]> keys = new ArrayList<>();
        for (var entry : principals.entrySet()) { PartitionStateMachine.identity(entry.getKey());
            if (keys.stream().anyMatch(key -> java.security.MessageDigest.isEqual(key, entry.getValue().key))) throw new IllegalArgumentException("Reused principal key"); keys.add(entry.getValue().key); }
        server = tls == null ? new ServerSocket() : tls.getServerSocketFactory().createServerSocket();
        if (server instanceof SSLServerSocket secure) secure.setNeedClientAuth(true);
        server.bind(bind, 16);
        workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), runnable -> {
            Thread thread = new Thread(runnable, "partition-handler"); thread.setDaemon(true); return thread; });
        QueuePushHub preparedQueue = null; ExecutionPushHub preparedExecution = null;
        try {
            if (partition != null && partition.queueResource() != null) preparedQueue = new QueuePushHub(node, partition, clock);
            if (partition != null && partition.executionResource() != null) preparedExecution = new ExecutionPushHub(node, partition, partition.executionResource(), clock);
        } catch (RuntimeException failed) {
            if (preparedQueue != null) preparedQueue.close(); if (preparedExecution != null) preparedExecution.close();
            workers.shutdownNow(); server.close(); throw failed;
        }
        queuePush = preparedQueue; executionPush = preparedExecution;
        acceptor = new Thread(this::accept, "partition-acceptor"); acceptor.setDaemon(true); acceptor.start();
    }
    /**
     * Returns bound service address.
     * @return actual address including ephemeral port
     */
    public InetSocketAddress address() { return (InetSocketAddress) server.getLocalSocketAddress(); }
    /**
     * Counts malformed, unauthorized transport, replayed and overloaded connections.
     * @return rejected connection count
     */
    public long rejectedRequests() { return rejected.get(); }
    /**
     * Bounded aggregate queue transport metrics; no principal or message labels are exposed.
     * @param sessions currently admitted authenticated sessions
     * @param reservedBytes claimed or pending frame reservation, at most16MiB
     * @param claims actual server-originated claim attempts
     * @param queries actual dispatcher quorum query attempts
     */
    public record QueuePushStatistics(int sessions, int reservedBytes, long claims, long queries) { }
    /**
     * Reads local queue transport counters without quorum or durable mutation.
     * @return zero counters for a nonqueue host, otherwise current bounded aggregates
     */
    public QueuePushStatistics queuePushStatistics() {
        return queuePush == null ? new QueuePushStatistics(0, 0, 0, 0) : queuePush.statistics();
    }
    private void accept() {
        while (!closed) try {
            Socket socket = server.accept(); socket.setSoTimeout(5000); socket.setTcpNoDelay(true); sockets.add(socket);
            try { workers.execute(() -> handle(socket)); } catch (RejectedExecutionException overloaded) { rejected.incrementAndGet(); sockets.remove(socket); socket.close(); }
        } catch (IOException failure) { if (!closed) Thread.onSpinWait(); }
    }
    private void handle(Socket socket) {
        boolean handedOff = false;
        try {
            DataInputStream input = new DataInputStream(socket.getInputStream()); byte[] body = ResourceWire.receive(input);
            DataInputStream decoded = PartitionStateMachine.input(body); if (decoded.readInt() != 1) throw new IOException("Unsupported partition protocol");
            String principal = decoded.readUTF(); Binding binding = principals.get(principal); if (binding == null) throw new IOException("Unknown principal");
            ResourceWire.authenticate(input, body, binding.key);
            String nonce = decoded.readUTF(); long expiry = decoded.readLong(); long now = System.currentTimeMillis();
            if (nonce.length() != 36 || expiry <= now || expiry > now + 30000) throw new IOException("Invalid deadline/nonce");
            synchronized (replay) { replay.values().removeIf(value -> value <= now);
                if (replay.size() >= 8192 || replay.putIfAbsent(principal + ':' + nonce, expiry) != null) throw new IOException("Replay/overload"); }
            String operation = decoded.readUTF(); byte[] payload = PartitionStateMachine.bytes(decoded, ResourceWire.MAX_FRAME); PartitionStateMachine.end(decoded);
            if (operation.equals("EXECUTION_SESSION")) {
                if (executionPush == null) throw new IOException("Not an execution host");
                executionPush.open(socket, nonce, binding, payload, Instant.ofEpochMilli(expiry)); handedOff = true; return;
            }
            if (operation.equals("QUEUE_SESSION")) {
                if (queuePush == null) throw new IOException("Not a queue host");
                queuePush.open(socket, nonce, binding, payload, Instant.ofEpochMilli(expiry)); handedOff = true; return;
            }
            byte[] result; int code;
            try { result = dispatch(principal, binding, operation, payload, Instant.ofEpochMilli(expiry)); code = 0; }
            catch (SecurityException denied) { result = new byte[0]; code = 1; }
            catch (IOException unavailable) { result = new byte[0]; code = 2; }
            catch (RuntimeException invalid) { String detail = "CURSOR_EXPIRED".equals(invalid.getMessage()) ? "CURSOR_EXPIRED"
                    : binding.administrative ? invalid.toString() + (invalid.getCause() == null ? "" : ": " + invalid.getCause()) : "Invalid partition request";
                result = detail.getBytes(java.nio.charset.StandardCharsets.UTF_8); code = 3; }
            int responseCode = code; byte[] responsePayload = result;
            byte[] response = ResourceWire.encode(out -> { out.writeUTF(nonce); out.writeInt(responseCode); PartitionStateMachine.writeBytes(out, responsePayload); });
            ResourceWire.send(new DataOutputStream(socket.getOutputStream()), response, binding.key);
        } catch (IOException | RuntimeException failure) { rejected.incrementAndGet(); }
        finally {
            sockets.remove(socket);
            if (!handedOff) try { socket.close(); } catch (IOException alreadyClosed) { rejected.incrementAndGet(); }
        }
    }
    private byte[] dispatch(String principal, Binding binding, String operation, byte[] payload, Instant deadline) throws IOException {
        DataInputStream in = PartitionStateMachine.input(payload);
        if (Set.of("EXECUTE", "QUERY", "STATUS").contains(operation)) {
            if (partition == null) throw new IllegalArgumentException("Not a resource host");
            LocalPartitionEndpoint endpoint = new LocalPartitionEndpoint(node, partition, binding.scope, binding.maintenance, binding.administrative, clock);
            if (operation.equals("STATUS")) { PartitionStateMachine.end(in); var status = endpoint.status(deadline); return statusBytes(status); }
            long epoch = in.readLong(); PartitionReply reply;
            if (operation.equals("EXECUTE")) { String request = in.readUTF(); byte[] command = PartitionStateMachine.bytes(in, PartitionStateMachine.MAX_COMMAND);
                byte[] query = PartitionStateMachine.bytes(in, PartitionStateMachine.MAX_COMMAND); PartitionStateMachine.end(in);
                ResourceCommand nested = partition.queueResource() == null ? null : ResourceCommand.decode(command);
                if (nested != null && nested.operation().equals("Q_REDRIVE")) {
                    if (!binding.administrative) throw new SecurityException("Queue redrive authority required");
                    var arguments = new ArrayList<>(nested.arguments());
                    if (arguments.size() != 6) throw new IllegalArgumentException("Redrive arguments");
                    arguments.set(5, principal); command = new ResourceCommand(nested.operation(), nested.scope(), nested.requestId(), arguments).encode();
                    query = new ResourceCommand("Q_RESULT", binding.scope, "query", List.of(request, QueueStateMachine.fingerprint(command))).encode();
                }
                reply = endpoint.execute(epoch, request, command, query, deadline);
            } else {
                byte[] query = PartitionStateMachine.bytes(in, PartitionStateMachine.MAX_COMMAND); PartitionStateMachine.end(in);
                ResourceCommand page = partition.queueResource() == null ? null : ResourceCommand.decode(query);
                if (page != null && page.operation().equals("Q_DLQ_PAGE")) {
                    var args = page.arguments();
                    if (!page.scope().equals(binding.scope) || args.size() != 6 || !args.get(4).equals(partition.status().partition())) throw new SecurityException("Foreign queue cursor");
                    long after = Long.parseLong(args.get(1)), high = Long.parseLong(args.get(2));
                    if (after != 0 || high != 0) {
                        byte[] supplied;
                        try { supplied = Base64.getDecoder().decode(args.get(5)); } catch (IllegalArgumentException invalid) { throw new SecurityException("Invalid queue cursor"); }
                        if (!java.security.MessageDigest.isEqual(supplied, cursorMac(binding, args.get(0), after, high))) throw new SecurityException("Invalid queue cursor");
                    } else if (!args.get(5).isEmpty()) throw new IllegalArgumentException("Unexpected initial cursor");
                    query = new ResourceCommand(page.operation(), page.scope(), page.requestId(), args.subList(0, 4)).encode();
                    reply = endpoint.query(epoch, query, deadline);
                    if (reply.status().equals("OK")) {
                        String value = new String(reply.payload(), java.nio.charset.StandardCharsets.UTF_8); String[] header = value.split("\\t", 3);
                        String token = Base64.getEncoder().encodeToString(cursorMac(binding, args.get(0), Long.parseLong(header[1]), Long.parseLong(header[0])));
                        reply = new PartitionReply(reply.status(), reply.epoch(), (value + "\t!" + token).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                } else reply = endpoint.query(epoch, query, deadline);
            }
            PartitionReply completed = reply;
            return PartitionStateMachine.encode(out -> { out.writeUTF(completed.status()); out.writeLong(completed.epoch()); PartitionStateMachine.writeBytes(out, completed.payload()); });
        }
        if (operation.equals("ROUTE")) {
            if (topology == null) throw new IllegalArgumentException("Not a topology host");
            String name = in.readUTF(); PartitionStateMachine.end(in); var route = topology.route(name, node.readIndex(deadline));
            if (route == null) throw new IllegalArgumentException("Unknown partition");
            return PartitionStateMachine.encode(out -> { out.writeUTF(route.partition()); out.writeUTF(route.ownerGroup()); out.writeLong(route.epoch()); });
        }
        if (!binding.administrative) throw new SecurityException("Topology authority required");
        if (operation.equals("PARTITION_COMMAND")) {
            if (partition == null) throw new IllegalArgumentException("Not a partition");
            // Only bounded known control encodings are accepted; clients cannot submit nested writes here.
            if (payload.length == 0 || payload[0] < 2 || payload[0] > 8) throw new IllegalArgumentException("Invalid control");
            synchronized (node) {
                partition.validateControl(payload, binding.scope);
                long index = node.appendControl(payload, deadline); return text(partition.receipt(index));
            }
        }
        if (operation.equals("TOPOLOGY_COMMAND")) {
            if (topology == null || payload.length == 0 || payload[0] < 1 || payload[0] > 4) throw new IllegalArgumentException("Invalid topology control");
            synchronized (node) {
                topology.validateControl(payload);
                long index = node.appendControl(payload, deadline); return text(topology.receipt(index));
            }
        }
        node.readIndex(deadline);
        return switch (operation) {
            case "IMAGE" -> { String id = in.readUTF(); int offset = in.readInt(); int limit = in.readInt(); PartitionStateMachine.end(in);
                if (partition == null || offset < 0 || limit < 1 || limit > 128 * 1024) throw new IllegalArgumentException("Invalid image chunk");
                byte[] image = partition.snapshotAt(id); if (offset > image.length) throw new IllegalArgumentException("Image offset");
                yield PartitionStateMachine.encode(out -> { out.writeInt(image.length); out.writeUTF(PartitionStateMachine.sha256(image));
                    PartitionStateMachine.writeBytes(out, Arrays.copyOfRange(image, offset, Math.min(image.length, offset + limit))); }); }
            case "TAIL" -> { long after = in.readLong(); int limit = in.readInt(); PartitionStateMachine.end(in);
                var entries = partition.tail(after, limit); yield PartitionStateMachine.catchup("tail", entries); }
            case "FINAL_HASH" -> { String id = in.readUTF(); PartitionStateMachine.end(in); yield text(partition.finalImageHash(id)); }
            case "UPLOAD_OFFSET" -> { PartitionStateMachine.end(in); yield PartitionStateMachine.encode(out -> out.writeInt(partition.uploadOffset())); }
            case "ROUTE" -> { String name = in.readUTF(); PartitionStateMachine.end(in); var route = topology.route(name, node.readIndex(deadline));
                if (route == null) throw new IllegalArgumentException("Unknown partition"); yield PartitionStateMachine.encode(out -> { out.writeUTF(route.partition()); out.writeUTF(route.ownerGroup()); out.writeLong(route.epoch()); }); }
            case "HANDOFF" -> { String id = in.readUTF(); PartitionStateMachine.end(in); var handoff = topology.handoff(id, node.readIndex(deadline));
                if (handoff == null) yield new byte[0]; yield PartitionStateMachine.encode(out -> { out.writeUTF(handoff.id()); out.writeUTF(handoff.partition());
                    out.writeUTF(handoff.source()); out.writeUTF(handoff.target()); out.writeLong(handoff.expectedEpoch()); out.writeUTF(handoff.phase()); out.writeUTF(handoff.imageHash()); }); }
            case "PROOF" -> { String id = in.readUTF(); PartitionStateMachine.end(in); yield topology.activationProof(id, node.readIndex(deadline)); }
            default -> throw new IllegalArgumentException("Unknown partition operation");
        };
    }
    private byte[] cursorMac(Binding binding, String queue, long after, long high) throws IOException {
        return ResourceWire.mac(binding.key, PartitionStateMachine.encode(out -> {
            out.writeInt(1); out.writeUTF(partition.status().partition()); out.writeUTF(binding.scope); out.writeUTF(queue); out.writeLong(after); out.writeLong(high);
        }));
    }
    private static byte[] text(String text) { return PartitionStateMachine.encode(out -> out.writeUTF(text)); }
    private static byte[] statusBytes(PartitionStateMachine.Status status) { return PartitionStateMachine.encode(out -> {
        out.writeUTF(status.partition()); out.writeUTF(status.group()); out.writeLong(status.epoch()); out.writeUTF(status.phase()); out.writeUTF(status.handoff()); out.writeUTF(status.imageHash()); out.writeLong(status.logicalIndex()); }); }
    /**
     * {@inheritDoc}
     */
    @Override public void close() throws IOException {
        closed = true; if (queuePush != null) queuePush.close(); if (executionPush != null) executionPush.close(); server.close(); workers.shutdownNow(); for (Socket socket : sockets) socket.close(); acceptor.interrupt();
    }
}
