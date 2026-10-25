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

package org.j7m.middleware.registry.mcast;

import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.registry.api.RegistryPeerState;
import org.j7m.middleware.registry.api.RegistryQuery;
import org.j7m.middleware.registry.api.RegistryRecord;

/**
 * UDP multicast adapter for registry discovery.
 *
 * <p>The adapter opens an OS multicast socket per attached registry client,
 * joins the configured group on start, receives packets on a virtual thread,
 * and leaves the group on shutdown. Discovery calls are bounded by caller
 * deadlines. UDP and codec failures are recorded in diagnostics and wrapped in
 * middleware exceptions where startup cannot proceed.</p>
 */
public final class UdpMulticastBus implements MulticastBus {
    private static final int MAX_PENDING_QUERIES = 1024;
    private static final int RESPONSE_QUEUE_CAPACITY = 64;
    private final ConcurrentMap<MulticastRegistryClient, Endpoint> endpoints;
    private final MulticastProtocolCodec codec;
    private final AtomicLong sent;
    private final AtomicLong received;
    private final AtomicLong dropped;
    private final AtomicLong decodeFailures;
    private final AtomicLong staleDrops;
    private final AtomicLong ownIgnores;
    private volatile boolean closed;

    /**
     * Creates a UDP multicast adapter.
     */
    public UdpMulticastBus() {
        this.endpoints = new ConcurrentHashMap<>();
        this.codec = new MulticastProtocolCodec();
        this.sent = new AtomicLong();
        this.received = new AtomicLong();
        this.dropped = new AtomicLong();
        this.decodeFailures = new AtomicLong();
        this.staleDrops = new AtomicLong();
        this.ownIgnores = new AtomicLong();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void register(final MulticastRegistryClient client) {
        if (this.closed) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "udp multicast bus is closed");
        }
        this.endpoints.computeIfAbsent(client, this::openEndpoint);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unregister(final MulticastRegistryClient client) {
        Endpoint endpoint = this.endpoints.remove(client);
        if (endpoint != null) {
            endpoint.close();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<RegistryRecord> list(
            final MulticastRegistryClient client,
            final MulticastMessageEnvelope envelope,
            final RegistryQuery query,
            final long deadlineMillis) {
        Endpoint endpoint = this.endpoints.get(client);
        if (this.closed || endpoint == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "udp multicast endpoint is not running");
        }
        BlockingQueue<MulticastProtocolMessage> queue = new ArrayBlockingQueue<>(RESPONSE_QUEUE_CAPACITY);
        PendingResponse pending = endpoint.reserve(envelope.questionId(), queue);
        try {
            this.send(endpoint, new MulticastProtocolMessage(envelope, query, List.of()));
            List<RegistryRecord> results = new ArrayList<>();
            Set<String> responderIds = new HashSet<>();
            Set<String> objectIds = new HashSet<>();
            int maxResponders = Math.max(1, Math.min(query.maxPeerCount(), client.options().maxPeers()));
            boolean receivedFirst = false;
            long stopAt = deadlineMillis;
            while (client.clock().millis() <= stopAt && results.size() < query.limit() && responderIds.size() < maxResponders) {
                long waitMillis = Math.max(1L, stopAt - client.clock().millis());
                MulticastProtocolMessage message = queue.poll(waitMillis, TimeUnit.MILLISECONDS);
                if (message == null) {
                    break;
                }
                receivedFirst = true;
                if (!responderIds.add(message.envelope().senderId())) {
                    continue;
                }
                client.peerContactSucceeded(client.peerStatusFor(message.envelope().senderId(), 0L, RegistryPeerState.ALIVE));
                for (RegistryRecord record : message.records()) {
                    if (objectIds.add(record.ref().objectId())) {
                        results.add(record);
                    }
                    if (results.size() >= query.limit()) {
                        break;
                    }
                }
                if (receivedFirst) {
                    Duration gather = client.options().gatherAfterFirstResponse();
                    stopAt = Math.min(stopAt, client.clock().millis() + Math.max(1L, gather.toMillis()));
                }
            }
            return List.copyOf(results);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.CANCELLED, "udp multicast query interrupted",
                    Map.of(), envelope.questionId(), null, true, false), exception);
        } finally {
            PendingResponse removed = endpoint.pending.remove(envelope.questionId());
            if (removed != null) {
                removed.release();
            } else {
                pending.release();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MulticastBusDiagnostics diagnostics() {
        return new MulticastBusDiagnostics(this.sent.get(), this.received.get(), this.dropped.get(), this.decodeFailures.get(),
                this.staleDrops.get(), this.ownIgnores.get(), this.closed);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String adapterType() {
        return "udp";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String endpointSummary() {
        Endpoint endpoint = this.endpoints.values().stream().findFirst().orElse(null);
        if (endpoint == null) {
            return "udp";
        }
        MulticastRegistryOptions options = endpoint.client.options();
        String interfaceName = options.networkInterface() == null ? endpoint.networkInterface.getName() : options.networkInterface().getName();
        return "udp://" + options.group() + ":" + options.port() + "%25" + interfaceName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        this.closed = true;
        for (Endpoint endpoint : this.endpoints.values()) {
            endpoint.close();
        }
        this.endpoints.clear();
    }

    private Endpoint openEndpoint(final MulticastRegistryClient client) {
        try {
            MulticastRegistryOptions options = client.options();
            InetAddress group = InetAddress.getByName(options.group());
            NetworkInterface networkInterface = options.networkInterface() == null ? this.defaultNetworkInterface() : options.networkInterface();
            MulticastSocket socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(options.port()));
            socket.setReceiveBufferSize(options.receiveBufferBytes());
            socket.setTimeToLive(options.ttl());
            socket.setNetworkInterface(networkInterface);
            SocketAddress groupAddress = new InetSocketAddress(group, options.port());
            socket.joinGroup(groupAddress, networkInterface);
            Endpoint endpoint = new Endpoint(client, socket, groupAddress, networkInterface);
            endpoint.listener = Thread.ofVirtual().name("j7m-udp-mcast-" + options.registryId()).start(() -> this.receiveLoop(endpoint));
            return endpoint;
        } catch (java.io.IOException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.REGISTRY_UNAVAILABLE, "failed to start udp multicast registry",
                    Map.of(), null, null, true, true), exception);
        }
    }

    private void receiveLoop(final Endpoint endpoint) {
        int maxMessageBytes = endpoint.client.options().maxMessageBytes();
        byte[] buffer = new byte[maxMessageBytes];
        while (!endpoint.closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                endpoint.socket.receive(packet);
                if (packet.getLength() >= maxMessageBytes) {
                    this.dropped.incrementAndGet();
                    continue;
                }
                MulticastProtocolMessage message = this.codec.decode(packet.getData(), packet.getLength());
                this.handle(endpoint, message);
            } catch (UncheckedIOException exception) {
                this.decodeFailures.incrementAndGet();
            } catch (java.net.SocketException exception) {
                if (!endpoint.closed) {
                    this.dropped.incrementAndGet();
                }
                return;
            } catch (java.io.IOException exception) {
                if (!endpoint.closed) {
                    this.dropped.incrementAndGet();
                }
            } catch (RuntimeException exception) {
                this.dropped.incrementAndGet();
            }
        }
    }

    private void handle(final Endpoint endpoint, final MulticastProtocolMessage message) {
        MulticastMessageEnvelope envelope = message.envelope();
        MulticastRegistryClient client = endpoint.client;
        if (!envelope.contextId().equals(client.options().contextId())) {
            this.dropped.incrementAndGet();
            return;
        }
        if (envelope.senderId().equals(client.options().registryId())) {
            this.ownIgnores.incrementAndGet();
            return;
        }
        if (!"*".equals(envelope.receiverId()) && !client.options().registryId().equals(envelope.receiverId())) {
            this.dropped.incrementAndGet();
            return;
        }
        if (envelope.createdAtMillis() + envelope.ttlMillis() < client.clock().millis()) {
            this.staleDrops.incrementAndGet();
            return;
        }
        this.received.incrementAndGet();
        if (envelope.messageType() == MulticastMessageType.QUESTION) {
            RegistryQuery query = message.query();
            if (query == null) {
                this.dropped.incrementAndGet();
                return;
            }
            List<RegistryRecord> answers = client.answerList(envelope, query);
            MulticastMessageEnvelope responseEnvelope = new MulticastMessageEnvelope(MulticastMessageType.RESPONSE,
                    client.options().registryId(), envelope.senderId(), envelope.contextId(), envelope.questionId(),
                    envelope.queryType(), client.clock().millis(), envelope.ttlMillis(), envelope.depth(), envelope.protocolVersion(),
                    Map.of("records", Integer.toString(answers.size())));
            this.send(endpoint, new MulticastProtocolMessage(responseEnvelope, null, answers));
        } else {
            PendingResponse pending = endpoint.pending.get(envelope.questionId());
            if (pending == null) {
                this.staleDrops.incrementAndGet();
                return;
            }
            if (!pending.offer(message)) {
                this.dropped.incrementAndGet();
            }
        }
    }

    private void send(final Endpoint endpoint, final MulticastProtocolMessage message) {
        byte[] bytes = this.codec.encode(message);
        if (bytes.length > endpoint.client.options().maxMessageBytes()) {
            this.dropped.incrementAndGet();
            return;
        }
        try {
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, endpoint.groupAddress);
            synchronized (endpoint.socket) {
                endpoint.socket.send(packet);
            }
            this.sent.incrementAndGet();
        } catch (java.io.IOException exception) {
            this.dropped.incrementAndGet();
        }
    }

    private NetworkInterface defaultNetworkInterface() throws java.io.IOException {
        NetworkInterface loopback = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
        if (loopback != null && loopback.isUp() && loopback.supportsMulticast()) {
            return loopback;
        }
        java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface candidate = interfaces.nextElement();
            if (candidate.isUp() && candidate.supportsMulticast()) {
                return candidate;
            }
        }
        throw new java.io.IOException("no multicast-capable network interface available");
    }

    private static final class Endpoint {
        private final MulticastRegistryClient client;
        private final MulticastSocket socket;
        private final SocketAddress groupAddress;
        private final NetworkInterface networkInterface;
        private final ConcurrentMap<String, PendingResponse> pending;
        private final Semaphore pendingPermits;
        private volatile Thread listener;
        private volatile boolean closed;

        private Endpoint(
                final MulticastRegistryClient client,
                final MulticastSocket socket,
                final SocketAddress groupAddress,
                final NetworkInterface networkInterface) {
            this.client = client;
            this.socket = socket;
            this.groupAddress = groupAddress;
            this.networkInterface = networkInterface;
            this.pending = new ConcurrentHashMap<>();
            this.pendingPermits = new Semaphore(MAX_PENDING_QUERIES);
        }

        private PendingResponse reserve(final String questionId, final BlockingQueue<MulticastProtocolMessage> queue) {
            if (!this.pendingPermits.tryAcquire()) {
                throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "udp multicast pending query limit exceeded");
            }
            PendingResponse response = new PendingResponse(queue, this.pendingPermits);
            PendingResponse previous = this.pending.putIfAbsent(questionId, response);
            if (previous != null) {
                response.release();
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "duplicate udp multicast question id");
            }
            return response;
        }

        private void close() {
            this.closed = true;
            for (PendingResponse response : this.pending.values()) {
                response.release();
            }
            this.pending.clear();
            try {
                this.socket.leaveGroup(this.groupAddress, this.networkInterface);
            } catch (java.io.IOException ignored) {
                // Socket close is still authoritative for lifecycle cleanup.
            }
            this.socket.close();
        }
    }

    private static final class PendingResponse {
        private final BlockingQueue<MulticastProtocolMessage> queue;
        private final Semaphore permits;
        private final AtomicBoolean released;

        private PendingResponse(final BlockingQueue<MulticastProtocolMessage> queue, final Semaphore permits) {
            this.queue = queue;
            this.permits = permits;
            this.released = new AtomicBoolean();
        }

        private boolean offer(final MulticastProtocolMessage message) {
            return this.queue.offer(message);
        }

        private void release() {
            if (this.released.compareAndSet(false, true)) {
                this.permits.release();
            }
        }
    }
}
