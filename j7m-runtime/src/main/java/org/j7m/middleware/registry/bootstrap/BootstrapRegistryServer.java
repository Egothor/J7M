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

package org.j7m.middleware.registry.bootstrap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.j7m.middleware.registry.api.PeerQuery;
import org.j7m.middleware.registry.api.RegistryClient;
import org.j7m.middleware.registry.api.RegistryQuery;

/**
 * Explicit read-only mTLS bootstrap registry endpoint. All client certificates trusted by the supplied
 * TLS context may read only the configured registry contexts; use a dedicated bootstrap trust store.
 * There are sixteen concurrent connections, no worker queue and at most 128 admitted connections per
 * second. Requests and responses are bounded to 256 KiB. Record payloads retain the existing smaller
 * multicast field-codec limits. The backend is borrowed and must return promptly; close terminates
 * sockets but cannot forcibly terminate a blocked backend implementation.
 */
public final class BootstrapRegistryServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(BootstrapRegistryServer.class.getName());
    private final RegistryClient registry;
    private final Set<String> contexts;
    private final long budget;
    private final SSLServerSocket listener;
    private final BootstrapConnections connections = new BootstrapConnections(16);
    private final ThreadPoolExecutor workers = BootstrapConnections.workers(16, "bootstrap-server-worker");
    private final Thread acceptor;
    private long rateWindow = System.nanoTime();
    private int admitted;
    private volatile boolean closed;

    /**
     * Starts an explicit host-owned registry endpoint; no runtime default installs it.
     * @param bind bind address, with port zero allowed for OS allocation
     * @param tls nonnull host TLS context with server identity and trusted client certificates
     * @param registry borrowed local registry; do not supply a recursive network bootstrap wrapper
     * @param contexts nonempty finite allowlist of contexts readable by authenticated clients
     * @param requestBudget total handshake/request/backend/write budget, 1ms through 5s
     * @throws IOException if binding fails
     * @throws IllegalArgumentException for invalid limits or contexts
     */
    public BootstrapRegistryServer(final InetSocketAddress bind, final SSLContext tls,
            final RegistryClient registry, final Set<String> contexts, final Duration requestBudget) throws IOException {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.contexts = Set.copyOf(contexts);
        if (this.contexts.isEmpty() || this.contexts.size() > 64
                || this.contexts.stream().anyMatch(value -> value.isBlank() || value.length() > 2048)) {
            throw new IllegalArgumentException("Bootstrap context bounds");
        }
        budget = BootstrapConnections.budget(requestBudget);
        listener = (SSLServerSocket) Objects.requireNonNull(tls, "tls").getServerSocketFactory().createServerSocket();
        listener.setNeedClientAuth(true);
        try {
            listener.bind(Objects.requireNonNull(bind, "bind"), 16);
        } catch (IOException | RuntimeException failure) {
            BootstrapConnections.closeSocket(listener);
            throw failure;
        }
        acceptor = BootstrapConnections.thread(this::accept, "bootstrap-server-accept");
        acceptor.start();
    }

    /**
     * Returns the actual listening address.
     * @return bound address, including the OS-selected port
     */
    public InetSocketAddress address() {
        return (InetSocketAddress) listener.getLocalSocketAddress();
    }

    private synchronized boolean admitRate() {
        long now = System.nanoTime();
        if (now - rateWindow >= 1_000_000_000L) {
            rateWindow = now;
            admitted = 0;
        }
        return ++admitted <= 128;
    }

    private void accept() {
        while (!closed) {
            try {
                SSLSocket socket = (SSLSocket) listener.accept();
                if (!admitRate()) {
                    BootstrapConnections.closeSocket(socket);
                    continue;
                }
                long deadline = System.nanoTime() + budget;
                BootstrapConnections.Lease lease;
                try {
                    lease = connections.admit(socket, deadline);
                } catch (IOException failure) {
                    BootstrapConnections.closeSocket(socket);
                    continue;
                }
                try {
                    workers.execute(() -> serve(socket, deadline, lease));
                } catch (RejectedExecutionException unavailable) {
                    lease.close();
                }
            } catch (IOException failure) {
                if (!closed) {
                    LOG.log(System.Logger.Level.DEBUG, "Bootstrap accept failed ({0})", failure.getClass().getName());
                }
            }
        }
    }

    private void serve(final SSLSocket socket, final long deadline, final BootstrapConnections.Lease lease) {
        try (lease) {
            socket.setSoTimeout(BootstrapConnections.millis(deadline));
            socket.startHandshake();
            DataInputStream request = BootstrapWire.input(BootstrapWire.receive(new DataInputStream(socket.getInputStream())));
            if (request.readInt() != BootstrapWire.MAGIC || request.readInt() != BootstrapWire.VERSION) {
                throw new IOException("Bootstrap protocol mismatch");
            }
            int operation = request.readUnsignedByte();
            String context = BootstrapWire.string(request);
            int limit = BootstrapWire.count(request.readInt(), BootstrapWire.MAX_RESULTS);
            boolean includeDead = request.readBoolean();
            byte[] payload = BootstrapWire.bytes(request);
            BootstrapWire.end(request);
            if (limit == 0 || !contexts.contains(context)) {
                throw new IOException("Bootstrap context denied");
            }
            byte[] result = response(operation, context, limit, includeDead, payload);
            BootstrapConnections.millis(deadline);
            BootstrapWire.send(new DataOutputStream(socket.getOutputStream()), BootstrapWire.encode(output -> {
                output.writeInt(BootstrapWire.MAGIC);
                output.writeInt(BootstrapWire.VERSION);
                BootstrapWire.bytes(output, result);
            }));
        } catch (IOException | RuntimeException failure) {
            LOG.log(System.Logger.Level.DEBUG, "Bootstrap read request failed ({0})", failure.getClass().getName());
        }
    }

    private byte[] response(final int operation, final String context, final int limit,
            final boolean includeDead, final byte[] payload) throws IOException {
        if (operation == 1) {
            var message = BootstrapWire.packet(payload);
            RegistryQuery query = message.query();
            if (query == null || !message.records().isEmpty() || !context.equals(query.registryContext())) {
                throw new IOException("Bootstrap query mismatch");
            }
            var bounded = new RegistryQuery(query.serviceName(), query.interfaces(), query.requiredParams(), context,
                    System.currentTimeMillis(), limit, query.deadlineMillis(), query.maxPeerCount(), query.maxDepth(), query.source());
            var records = registry.list(bounded).stream().filter(record -> record.ref().registryContext().equals(context)
                    && record.activeAt(System.currentTimeMillis())).limit(limit).toList();
            return BootstrapWire.packet(null, records);
        }
        if (operation == 2) {
            var message = BootstrapWire.packet(payload);
            if (message.query() != null || message.records().size() != 1
                    || !context.equals(message.records().getFirst().ref().registryContext())) {
                throw new IOException("Bootstrap lookup mismatch");
            }
            var ref = message.records().getFirst().ref();
            var result = registry.lookup(ref).filter(record -> context.equals(record.ref().registryContext())
                    && ref.objectId().equals(record.ref().objectId())
                    && ref.runtimeId().equals(record.ref().runtimeId()) && ref.generation() == record.ref().generation()
                    && record.activeAt(System.currentTimeMillis()));
            return BootstrapWire.packet(null, result.stream().toList());
        }
        if (operation == 3 && payload.length == 0) {
            var peers = registry.peers(new PeerQuery(context, includeDead, limit)).stream()
                    .filter(peer -> peer.knownContexts().isEmpty() || peer.knownContexts().contains(context))
                    .filter(peer -> includeDead || peer.alive()).limit(limit).toList();
            return BootstrapWire.encode(output -> BootstrapWire.peers(output, peers));
        }
        throw new IOException("Unsupported bootstrap operation");
    }

    /**
     * Stops accepting requests and closes active sockets and owned workers. The borrowed registry
     * remains open. Running backend calls receive interruption, which is advisory.
     */
    @Override public void close() {
        closed = true;
        BootstrapConnections.closeSocket(listener);
        connections.close();
        workers.shutdownNow();
        acceptor.interrupt();
    }
}
