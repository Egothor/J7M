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
 * Bounded authenticated network resource endpoint. Retries the configured leader candidates using the
 * identical operation/id/arguments/deadline. IOException means UNKNOWN; it never implies rollback.
 * Cleartext HMAC mode is restricted to loopback development. Nonloopback connections require TLS with
 * endpoint hostname verification. Keys must belong to a single service principal, not end users.
 */
public final class RemoteResourceClient implements ResourceEndpoint {
    private final List<InetSocketAddress> endpoints;
    private final String principal;
    private final byte[] key;
    private final SSLContext tls;
    /**
     * Creates an immutable remote endpoint; each operation owns its socket and is thread-safe.
     * @param endpoints fixed configured replicas, never client-provided discovery addresses
     * @param principal authenticated service identity bound to a server-side scope
     * @param authenticationKey secret HMAC key of at least 32 bytes
     * @param tls TLS context, null only for loopback development connections
     * @throws IllegalArgumentException for empty endpoints, invalid identity/key or insecure remote endpoints
     */
    public RemoteResourceClient(List<InetSocketAddress> endpoints, String principal, byte[] authenticationKey, SSLContext tls) {
        this.endpoints = List.copyOf(endpoints); this.principal = Objects.requireNonNull(principal);
        this.key = authenticationKey.clone(); this.tls = tls;
        if (endpoints.isEmpty() || principal.isBlank() || principal.length() > 128 || key.length < 32) throw new IllegalArgumentException("Invalid resource client configuration");
        for (InetSocketAddress address : endpoints) {
            if (address.isUnresolved() || (tls == null && !address.getAddress().isLoopbackAddress())) throw new IllegalArgumentException("Remote resources require TLS outside loopback");
        }
    }
    /**
     * {@inheritDoc}
     */
    @Override public ReplicatedResources.Result execute(String operation, String request, List<String> arguments, Instant deadline) throws IOException {
        return call("EXECUTE", operation, request, arguments, deadline, ResourceWire::result);
    }
    /**
     * {@inheritDoc}
     */
    @Override public ReplicatedResources.Value read(String key, Instant deadline) throws IOException {
        return call("READ", "", key, List.of(), deadline, in -> in.readBoolean()
                ? new ReplicatedResources.Value(ResourceWire.text(in), in.readLong()) : null);
    }
    /**
     * {@inheritDoc}
     */
    @Override public ReplicatedResources.Lease lease(String key, Instant deadline) throws IOException {
        return call("LEASE", "", key, List.of(), deadline, ResourceWire::lease);
    }
    /**
     * {@inheritDoc}
     */
    @Override public ReplicatedResources.Result advanceTime(String request, Instant deadline) throws IOException {
        return call("ADVANCE_TIME", "TIME", request, List.of(), deadline, ResourceWire::result);
    }
    /**
     * {@inheritDoc}
     */
    @Override public ReplicatedResources.Result transaction(String tx, Instant deadline) throws IOException {
        return call("TRANSACTION", "", tx, List.of(), deadline, ResourceWire::result);
    }
    /**
     * {@inheritDoc}
     */
    @Override public List<String> prepared(Instant deadline) throws IOException {
        return call("PREPARED", "", "", List.of(), deadline, ResourceWire::strings);
    }
    /**
     * {@inheritDoc}
     */
    @Override public List<String> participants(String tx, Instant deadline) throws IOException {
        return call("PARTICIPANTS", "", tx, List.of(), deadline, ResourceWire::strings);
    }
    /**
     * {@inheritDoc}
     */
    @Override public EventJournal.Page poll(String subscription, int maximum, Instant deadline) throws IOException {
        return call("POLL", "", subscription, List.of(Integer.toString(maximum)), deadline, ResourceWire::page);
    }
    /**
     * {@inheritDoc}
     */
    @Override public ReplicatedResources.SnapshotTail snapshotAndTail(Instant deadline) throws IOException {
        return callStreaming("SNAPSHOT_TAIL", "", "", List.of(), deadline, (decoded, input, nonce) -> {
            int size = decoded.readInt(); if (size < 0 || size > 67108864) throw new IOException("Snapshot projection bound");
            EventJournal.Cursor cursor = ResourceWire.cursor(decoded); long index = decoded.readLong();
            byte[] bytes = new byte[size];
            for (int offset = 0; offset < size;) {
                byte[] body = ResourceWire.receive(input); ResourceWire.authenticate(input, body, key);
                try (DataInputStream frame = new DataInputStream(new ByteArrayInputStream(body))) {
                    if (!nonce.equals(ResourceWire.text(frame)) || frame.readInt() != offset) throw new IOException("Snapshot chunk identity/offset");
                    int length = frame.readInt(); if (length < 1 || length > 65536 || length > size - offset || length != frame.available()) throw new IOException("Snapshot chunk bound");
                    frame.readFully(bytes, offset, length); offset += length;
                }
            }
            return new ReplicatedResources.SnapshotTail(bytes, cursor, index);
        });
    }
    private <T> T call(String type, String operation, String request, List<String> arguments, Instant deadline, Reader<T> reader) throws IOException {
        return callStreaming(type, operation, request, arguments, deadline, (decoded, input, nonce) -> reader.read(decoded));
    }
    private <T> T callStreaming(String type, String operation, String request, List<String> arguments, Instant deadline, StreamingReader<T> reader) throws IOException {
        Objects.requireNonNull(deadline);
        List<String> exactArguments = List.copyOf(arguments);
        String nonce = UUID.randomUUID().toString();
        byte[] body = ResourceWire.encode(out -> {
            out.writeInt(1); ResourceWire.text(out, principal); ResourceWire.text(out, nonce);
            out.writeLong(deadline.toEpochMilli()); ResourceWire.text(out, type); ResourceWire.text(out, operation);
            ResourceWire.text(out, request); ResourceWire.strings(out, exactArguments);
        });
        IOException failure = null;
        for (InetSocketAddress address : endpoints) {
            int timeout = timeout(deadline);
            try (Socket socket = tls == null ? new Socket() : tls.getSocketFactory().createSocket()) {
                socket.connect(address, timeout); socket.setSoTimeout(timeout(deadline));
                if (socket instanceof SSLSocket secure) {
                    SSLParameters parameters = secure.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS"); secure.setSSLParameters(parameters); secure.startHandshake();
                }
                DataInputStream input = new DataInputStream(socket.getInputStream());
                ResourceWire.send(new DataOutputStream(socket.getOutputStream()), body, key);
                byte[] reply = ResourceWire.receive(input); ResourceWire.authenticate(input, reply, key);
                try (DataInputStream decoded = new DataInputStream(new ByteArrayInputStream(reply))) {
                    if (!nonce.equals(ResourceWire.text(decoded))) throw new IOException("Resource response correlation mismatch");
                    int status = decoded.readInt();
                    if (status == 1) throw new SecurityException(ResourceWire.text(decoded));
                    if (status != 0) throw new IOException(ResourceWire.text(decoded));
                    T value = reader.read(decoded, input, nonce);
                    if (decoded.available() != 0) throw new IOException("Trailing resource response data");
                    return value;
                }
            } catch (IOException e) {
                if ("CURSOR_EXPIRED".equals(e.getMessage()) || "SUBSCRIPTION_EXPIRED".equals(e.getMessage())) throw e;
                failure = e;
            }
        }
        throw new IOException("UNKNOWN resource outcome: no authoritative endpoint replied", failure);
    }
    private static int timeout(Instant deadline) throws IOException {
        long remaining = deadline.toEpochMilli() - System.currentTimeMillis();
        if (remaining <= 0) throw new IOException("Resource deadline expired; outcome UNKNOWN");
        return (int) Math.min(remaining, 30000);
    }
    private interface StreamingReader<T> { T read(DataInputStream decoded, DataInputStream input, String nonce) throws IOException; }
    private interface Reader<T> { T read(DataInputStream input) throws IOException; }
}
