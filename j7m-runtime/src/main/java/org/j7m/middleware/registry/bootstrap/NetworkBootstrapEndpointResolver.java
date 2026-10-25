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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ObjectState;
import org.j7m.middleware.registry.api.PeerQuery;
import org.j7m.middleware.registry.api.RegistryClient;
import org.j7m.middleware.registry.api.RegistryPeerStatus;
import org.j7m.middleware.registry.api.RegistryQuery;
import org.j7m.middleware.registry.api.RegistryRecord;

/**
 * Explicit, thread-safe lazy resolver for {@code bootstrap+tls://host:port} endpoints.
 * The supplied TLS context must provide a client identity and trust the server; HTTPS endpoint
 * identification verifies the original hostname or IP SAN. Resolution creates no network connection.
 * Each operation uses a fresh socket and at most the configured budget, additionally bounded by the
 * bootstrap traversal deadline. There are at most 64 active calls and four DNS workers with no queue.
 * A JDK DNS lookup may ignore cancellation; such a lookup retains its worker until actual exit,
 * causing bounded admission failures rather than spawning more threads. The resolver owns its
 * sockets, DNS workers and timers; returned facades own no shared backend and are read-only.
 */
public final class NetworkBootstrapEndpointResolver implements BootstrapEndpointResolver, AutoCloseable {
    private final SSLContext tls;
    private final long budget;
    private final BootstrapConnections connections = new BootstrapConnections(64);
    private final ThreadPoolExecutor dns = BootstrapConnections.workers(4, "bootstrap-dns");
    private volatile boolean closed;

    /**
     * Creates an explicit network resolver.
     * @param tls nonnull host-owned TLS identity/trust configuration
     * @param operationBudget maximum total operation budget, 1ms through 5s
     * @throws IllegalArgumentException for an invalid budget
     */
    public NetworkBootstrapEndpointResolver(final SSLContext tls, final Duration operationBudget) {
        this.tls = Objects.requireNonNull(tls, "tls");
        budget = BootstrapConnections.budget(operationBudget);
    }

    /**
     * Creates a lazy read-only facade using the configured per-operation budget.
     * @param endpoint explicit bootstrap TLS URI
     * @return empty for another scheme or malformed endpoint, otherwise a lazy client
     * @throws MiddlewareException if this resolver has been closed
     */
    @Override public Optional<RegistryClient> resolve(final String endpoint) {
        return resolve(endpoint, 0);
    }

    /**
     * Creates a facade whose operations share an original absolute traversal deadline.
     * @param endpoint explicit bootstrap TLS URI; hostname or IPv4/IPv6 literal
     * @param deadlineMillis absolute epoch-millisecond deadline, or zero for per-operation budget
     * @return a lazy client for a syntactically valid endpoint, otherwise empty
     * @throws MiddlewareException if this resolver has been closed
     */
    @Override public Optional<RegistryClient> resolve(final String endpoint, final long deadlineMillis) {
        ensureOpen();
        if (endpoint == null || endpoint.length() > 2048) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(endpoint);
            if (!"bootstrap+tls".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1
                    || uri.getPort() > 65535 || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || uri.getPath() != null && !uri.getPath().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Client(uri, deadlineMillis));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw unavailable();
        }
    }

    private static MiddlewareException unavailable() {
        return MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Bootstrap endpoint unavailable");
    }

    private byte[] exchange(final URI endpoint, final long originalDeadline, final BootstrapWire.Writer request) {
        ensureOpen();
        long remaining = originalDeadline == 0 ? budget : Math.min(budget,
                TimeUnit.MILLISECONDS.toNanos(Math.max(0L, originalDeadline - System.currentTimeMillis())));
        long deadline = System.nanoTime() + remaining;
        try (Socket socket = new Socket(); var lease = connections.admit(socket, deadline)) {
            byte[] body = BootstrapWire.encode(request);
            var resolution = dns.submit(() -> InetAddress.getAllByName(endpoint.getHost()));
            InetAddress[] addresses;
            try {
                addresses = resolution.get(BootstrapConnections.millis(deadline), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw unavailable();
            } catch (ExecutionException | TimeoutException failedResolution) {
                throw unavailable();
            } finally {
                resolution.cancel(true);
            }
            if (addresses.length == 0) {
                throw unavailable();
            }
            socket.connect(new InetSocketAddress(addresses[0], endpoint.getPort()), BootstrapConnections.millis(deadline));
            try (SSLSocket secure = (SSLSocket) tls.getSocketFactory().createSocket(socket,
                    endpoint.getHost(), endpoint.getPort(), true)) {
                secure.setSoTimeout(BootstrapConnections.millis(deadline));
                SSLParameters parameters = secure.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                secure.setSSLParameters(parameters);
                secure.startHandshake();
                BootstrapWire.send(new DataOutputStream(secure.getOutputStream()), body);
                var response = BootstrapWire.input(BootstrapWire.receive(new DataInputStream(secure.getInputStream())));
                if (response.readInt() != BootstrapWire.MAGIC || response.readInt() != BootstrapWire.VERSION) {
                    throw new IOException("Bootstrap protocol mismatch");
                }
                byte[] result = BootstrapWire.bytes(response);
                BootstrapWire.end(response);
                BootstrapConnections.millis(deadline);
                return result;
            }
        } catch (IOException | RuntimeException failure) {
            throw unavailable();
        }
    }

    private final class Client implements RegistryClient {
        private final URI endpoint;
        private final long deadline;
        private volatile boolean clientClosed;

        private Client(final URI endpoint, final long deadline) {
            this.endpoint = endpoint;
            this.deadline = deadline;
        }

        private byte[] read(final int operation, final String context, final int limit,
                final boolean includeDead, final byte[] payload) {
            if (clientClosed) {
                throw unavailable();
            }
            return exchange(endpoint, deadline, output -> {
                output.writeInt(BootstrapWire.MAGIC);
                output.writeInt(BootstrapWire.VERSION);
                output.writeByte(operation);
                BootstrapWire.string(output, context);
                output.writeInt(Math.min(BootstrapWire.MAX_RESULTS, limit));
                output.writeBoolean(includeDead);
                BootstrapWire.bytes(output, payload);
            });
        }

        private List<RegistryRecord> records(final byte[] payload) {
            try {
                var message = BootstrapWire.packet(payload);
                if (message.query() != null || message.records().size() > BootstrapWire.MAX_RESULTS) {
                    throw unavailable();
                }
                return message.records();
            } catch (RuntimeException malformed) {
                throw unavailable();
            }
        }

        @Override public Optional<RegistryRecord> lookup(final ObjectRef ref) {
            var carrier = new RegistryRecord(ref, Map.of(), 0, 0, 0, ObjectState.INITIALIZED);
            byte[] response = read(2, ref.registryContext(), 1, false, BootstrapWire.packet(null, List.of(carrier)));
            return records(response).stream()
                    .filter(record -> ref.objectId().equals(record.ref().objectId())
                            && ref.registryContext().equals(record.ref().registryContext())
                            && ref.runtimeId().equals(record.ref().runtimeId()) && ref.generation() == record.ref().generation()
                            && record.activeAt(System.currentTimeMillis())).findFirst();
        }

        @Override public List<RegistryRecord> list(final RegistryQuery query) {
            byte[] response = read(1, query.registryContext(), query.limit(), false, BootstrapWire.packet(query, List.of()));
            return records(response).stream()
                    .filter(record -> query.registryContext().equals(record.ref().registryContext())
                            && record.activeAt(System.currentTimeMillis())
                            && (query.serviceName() == null || query.serviceName().isBlank()
                                    || query.serviceName().equals(record.ref().serviceName()))
                            && record.ref().interfaces().containsAll(query.interfaces())
                            && record.parameters().entrySet().containsAll(query.requiredParams().entrySet()))
                    .limit(query.limit()).toList();
        }

        @Override public List<RegistryPeerStatus> peers(final PeerQuery query) {
            try {
                var response = BootstrapWire.input(read(3, query.registryContext(), query.limit(), query.includeDead(), new byte[0]));
                var result = BootstrapWire.peers(response);
                BootstrapWire.end(response);
                return result;
            } catch (IOException | RuntimeException malformed) {
                throw unavailable();
            }
        }

        @Override public void bind(final RegistryRecord record) {
            throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED, "Bootstrap registry is read-only");
        }

        @Override public void unbind(final ObjectRef ref) {
            throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED, "Bootstrap registry is read-only");
        }

        @Override public void heartbeat(final ObjectRef ref, final long leaseUntil) {
            throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED, "Bootstrap registry is read-only");
        }

        @Override public void close() {
            clientClosed = true;
        }
    }

    /**
     * Closes active sockets and owned DNS/timer resources. Idempotent; existing facades then fail.
     * DNS interruption is advisory and never triggers replacement workers beyond the fixed cap.
     */
    @Override public void close() {
        closed = true;
        connections.close();
        dns.shutdownNow();
    }
}
