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
import javax.net.ssl.*;

/**
 * Authenticated bounded partition client with endpoint failover. Plaintext is restricted to loopback;
 * nonloopback endpoints require mutual TLS and hostname verification. No failed response proves rollback.
 */
public final class PartitionClient implements PartitionEndpoint {
    private final List<InetSocketAddress> endpoints;
    private final String principal;
    private final byte[] key;
    private final SSLContext tls;
    /**
     * Creates a loopback development client.
     * @param endpoints group endpoints, tried until deadline
     * @param principal configured service principal
     * @param key principal-specific authentication key
     */
    public PartitionClient(List<InetSocketAddress> endpoints, String principal, byte[] key) {
        this(endpoints, principal, key, null);
    }
    /**
     * Creates a secure partition client.
     * @param endpoints explicit group endpoints
     * @param principal authenticated identity
     * @param key principal-specific key of at least 32 bytes
     * @param tls mutual TLS context, mandatory outside loopback
     * @throws IllegalArgumentException for unsafe endpoints or credentials
     */
    public PartitionClient(List<InetSocketAddress> endpoints, String principal, byte[] key, SSLContext tls) {
        PartitionStateMachine.identity(principal);
        if (endpoints.isEmpty() || key.length < 32 || endpoints.stream().anyMatch(address -> address.isUnresolved()
                || (tls == null && !address.getAddress().isLoopbackAddress()))) throw new IllegalArgumentException("Unsafe partition endpoint");
        this.endpoints = List.copyOf(endpoints); this.principal = principal; this.key = key.clone(); this.tls = tls;
    }
    /**
     * Opens a bounded authenticated queue push session without sending a claim or consuming a credit.
     * The caller retains group/member identity on reconnect; a repeated frame requires durable dedupe.
     * @param epoch current partition ownership epoch
     * @param queue named FIFO queue
     * @param group consumer group
     * @param member stable consumer identity
     * @param visibilityMillis requested receipt visibility1..86400000milliseconds
     * @param deadline original connection deadline, at most30seconds from now
     * @return owned single-subscriber session; close it to cancel delivery
     * @throws IOException on unavailable quorum, TLS, authentication or handshake failure
     * @throws IllegalArgumentException for invalid identities, duration or epoch
     */
    public QueueSession queueSession(long epoch, String queue, String group, String member, long visibilityMillis, Instant deadline) throws IOException {
        return openQueueSession(epoch, queue, group, member, visibilityMillis, false, deadline);
    }
    /**
     * Observes retained dead letters through the same bounded credit protocol, without claiming a new receipt.
     * Observation success grants no ACK, removal or redrive authority; use durable logical-handler dedupe.
     * @param epoch committed partition epoch
     * @param queue source FIFO queue
     * @param observer stable observer identity
     * @param deadline original connection deadline
     * @return owned observational push session
     * @throws IOException on unavailable authenticated host or quorum
     */
    public QueueSession deadLetterSession(long epoch, String queue, String observer, Instant deadline) throws IOException {
        return openQueueSession(epoch, queue, "dead-letter-observers", observer, 60000, true, deadline);
    }
    /**
     * {@inheritDoc}
     */
    @Override public QueueSession subscribeQueue(long epoch, String queue, String group, String member, long visibilityMillis, boolean observational, Instant deadline) throws IOException {
        return openQueueSession(epoch, queue, group, member, visibilityMillis, observational, deadline);
    }
    private QueueSession openQueueSession(long epoch, String queue, String group, String member, long visibilityMillis, boolean observational, Instant deadline) throws IOException {
        QueueStateMachine.validate(new ResourceCommand("Q_CLAIM", "validation", "validation", List.of(queue, group, member, "1", Long.toString(visibilityMillis), "0")));
        if (epoch <= 0) throw new IllegalArgumentException("Queue epoch");
        byte[] payload = PartitionStateMachine.encode(out -> { out.writeLong(epoch); out.writeUTF(queue); out.writeUTF(group); out.writeUTF(member); out.writeLong(visibilityMillis); out.writeBoolean(observational); });
        var connection = PartitionSessionHandshake.open(endpoints, principal, key, tls, "QUEUE_SESSION", payload, deadline);
        try { return new QueueSession(connection.socket(), key, connection.nonce()); }
        catch (IOException failed) { connection.socket().close(); throw failed; }
    }
    /**
     * Opens the same authenticated bounded-credit transport for complete native execution claims.
     * It never performs a client-side hint/fetch/claim loop and grants no authority from metadata.
     * @param epoch current partition ownership epoch
     * @param prefix host-authorized job identity prefix, empty for all authorized jobs
     * @param kind JOB or SAGA
     * @param owner stable native execution owner
     * @param leaseMillis positive claim lease, at most24hours
     * @param deadline original absolute handshake budget, clamped to30seconds
     * @return owned single-subscriber complete-claim publisher
     * @throws IOException for unsupported host, authentication or unavailable quorum
     * @throws IllegalArgumentException for invalid prefix, kind, owner, epoch or lease
     */
    public ExecutionSession executionSession(long epoch, String prefix, String kind, String owner, long leaseMillis, Instant deadline) throws IOException {
        PartitionStateMachine.identity(owner);
        if (epoch <= 0 || prefix == null || !prefix.matches("[A-Za-z0-9_.:-]{0,128}") || !Set.of("JOB", "SAGA").contains(kind)
                || leaseMillis < 1 || leaseMillis > 86400000) throw new IllegalArgumentException("Invalid execution session");
        byte[] payload = PartitionStateMachine.encode(out -> { out.writeLong(epoch); out.writeUTF(prefix); out.writeUTF(kind); out.writeUTF(owner); out.writeLong(leaseMillis); });
        var connection = PartitionSessionHandshake.open(endpoints, principal, key, tls, "EXECUTION_SESSION", payload, deadline);
        try { return new ExecutionSession(connection.socket(), key, connection.nonce()); }
        catch (IOException failed) { connection.socket().close(); throw failed; }
    }
    /**
     * {@inheritDoc}
     */
    @Override public ExecutionSession subscribeExecution(long epoch, String prefix, String kind, String owner, long leaseMillis, Instant deadline) throws IOException {
        return executionSession(epoch, prefix, kind, owner, leaseMillis, deadline);
    }
    /**
     * {@inheritDoc}
     *
     * <p>The original absolute deadline bounds endpoint failover and retry. A lost transport reply
     * does not prove rollback: preserve the logical request identity for outcome recovery.</p>
     */
    @Override public PartitionReply execute(long epoch, String requestId, byte[] command, byte[] resultQuery, Instant deadline) throws IOException {
        byte[] response = exchange("EXECUTE", PartitionStateMachine.encode(out -> { out.writeLong(epoch); out.writeUTF(requestId);
            PartitionStateMachine.writeBytes(out, command); PartitionStateMachine.writeBytes(out, resultQuery); }), deadline);
        return reply(response);
    }
    /**
     * {@inheritDoc}
     *
     * <p>Queries retain the supplied partition epoch and original absolute deadline across endpoint
     * failover. Authentication and server-side quorum/read-barrier validation remain required.</p>
     */
    @Override public PartitionReply query(long epoch, byte[] query, Instant deadline) throws IOException {
        return reply(exchange("QUERY", PartitionStateMachine.encode(out -> { out.writeLong(epoch); PartitionStateMachine.writeBytes(out, query); }), deadline));
    }
    /**
     * {@inheritDoc}
     *
     * <p>Returns authenticated group diagnostics within the original absolute deadline. A status
     * response is observational and does not grant ownership or transaction authority.</p>
     */
    @Override public PartitionStateMachine.Status status(Instant deadline) throws IOException {
        DataInputStream in = PartitionStateMachine.input(exchange("STATUS", new byte[0], deadline));
        var status = new PartitionStateMachine.Status(in.readUTF(), in.readUTF(), in.readLong(), in.readUTF(), in.readUTF(), in.readUTF(), in.readLong());
        PartitionStateMachine.end(in); return status;
    }
    byte[] exchange(String operation, byte[] payload, Instant deadline) throws IOException {
        IOException last = null;
        while (Instant.now().isBefore(deadline)) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Partition operation cancelled; outcome UNKNOWN");
            for (InetSocketAddress address : endpoints) {
                long remaining = deadline.toEpochMilli() - System.currentTimeMillis(); if (remaining <= 0) break;
                String nonce = UUID.randomUUID().toString(); long expiry = Math.min(deadline.toEpochMilli(), System.currentTimeMillis() + 30000);
                byte[] request = ResourceWire.encode(out -> { out.writeInt(1); out.writeUTF(principal); out.writeUTF(nonce);
                    out.writeLong(expiry); out.writeUTF(operation); PartitionStateMachine.writeBytes(out, payload); });
                try (Socket socket = tls == null ? new Socket() : tls.getSocketFactory().createSocket()) {
                    int timeout = (int) Math.min(remaining, 2000); socket.connect(address, timeout); socket.setSoTimeout(timeout); socket.setTcpNoDelay(true);
                    if (socket instanceof SSLSocket secure) { SSLParameters parameters = secure.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS"); secure.setSSLParameters(parameters); secure.startHandshake(); }
                    ResourceWire.send(new DataOutputStream(socket.getOutputStream()), request, key);
                    DataInputStream input = new DataInputStream(socket.getInputStream()); byte[] response = ResourceWire.receive(input); ResourceWire.authenticate(input, response, key);
                    DataInputStream decoded = PartitionStateMachine.input(response);
                    if (!decoded.readUTF().equals(nonce)) throw new IOException("Partition response replay");
                    int code = decoded.readInt(); byte[] result = PartitionStateMachine.bytes(decoded, ResourceWire.MAX_FRAME); PartitionStateMachine.end(decoded);
                    if (code == 1) throw new SecurityException("Partition operation not authorized");
                    if (code == 3) throw new IllegalArgumentException(new String(result, java.nio.charset.StandardCharsets.UTF_8));
                    if (code != 0) throw new IOException("Partition unavailable; outcome UNKNOWN");
                    return result;
                } catch (IOException unavailable) { last = unavailable; }
            }
            java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
        }
        throw new IOException("Partition deadline; outcome UNKNOWN", last);
    }
    private static PartitionReply reply(byte[] response) throws IOException {
        DataInputStream in = PartitionStateMachine.input(response);
        var result = new PartitionReply(in.readUTF(), in.readLong(), PartitionStateMachine.bytes(in, ResourceWire.MAX_FRAME)); PartitionStateMachine.end(in); return result;
    }
}
