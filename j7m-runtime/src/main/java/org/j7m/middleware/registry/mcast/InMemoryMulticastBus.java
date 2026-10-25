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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.registry.api.RegistryPeerState;
import org.j7m.middleware.registry.api.RegistryQuery;
import org.j7m.middleware.registry.api.RegistryRecord;

/**
 * Deterministic in-memory multicast adapter for tests and demos.
 *
 * <p>The adapter models group delivery without sockets. It is opt-in and owns
 * no global state. Calls are synchronous, bounded by caller deadlines, and
 * thread-safe.</p>
 */
public class InMemoryMulticastBus implements MulticastBus {
    private final ConcurrentMap<String, List<MulticastRegistryClient>> members;
    private final AtomicLong sent;
    private final AtomicLong received;
    private final AtomicLong dropped;
    private final AtomicLong ownIgnores;
    private volatile boolean closed;

    /**
     * Creates an empty in-memory adapter.
     */
    public InMemoryMulticastBus() {
        this.members = new ConcurrentHashMap<>();
        this.sent = new AtomicLong();
        this.received = new AtomicLong();
        this.dropped = new AtomicLong();
        this.ownIgnores = new AtomicLong();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void register(final MulticastRegistryClient client) {
        if (this.closed) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "in-memory multicast bus is closed");
        }
        String key = this.key(client.options());
        this.members.compute(key, (ignored, existing) -> {
            List<MulticastRegistryClient> next = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            next.add(client);
            return List.copyOf(next);
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unregister(final MulticastRegistryClient client) {
        String key = this.key(client.options());
        this.members.computeIfPresent(key, (ignored, existing) -> existing.stream()
                .filter(candidate -> candidate != client)
                .toList());
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
        if (this.closed) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "in-memory multicast bus is closed");
        }
        List<RegistryRecord> results = new ArrayList<>();
        Set<MulticastRegistryClient> contacted = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> objectIds = new HashSet<>();
        int maxResponders = Math.max(1, Math.min(query.maxPeerCount(), client.options().maxPeers()));
        for (MulticastRegistryClient peer : this.members.getOrDefault(this.key(client.options()), List.of())) {
            if (results.size() >= query.limit() || contacted.size() >= maxResponders || client.clock().millis() > deadlineMillis) {
                break;
            }
            if (peer == client) {
                this.ownIgnores.incrementAndGet();
                continue;
            }
            if (!contacted.add(peer)) {
                continue;
            }
            this.sent.incrementAndGet();
            long started = System.nanoTime();
            try {
                List<RegistryRecord> peerResults = peer.answerList(envelope, query);
                this.received.incrementAndGet();
                client.peerContactSucceeded(peer.peerStatus(System.nanoTime() - started, RegistryPeerState.ALIVE));
                for (RegistryRecord record : peerResults) {
                    if (objectIds.add(record.ref().objectId())) {
                        results.add(record);
                    }
                    if (results.size() >= query.limit()) {
                        break;
                    }
                }
            } catch (MiddlewareException exception) {
                this.dropped.incrementAndGet();
                client.peerContactFailed(peer.peerStatus(System.nanoTime() - started, RegistryPeerState.DEAD));
            }
        }
        return List.copyOf(results);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MulticastBusDiagnostics diagnostics() {
        return new MulticastBusDiagnostics(this.sent.get(), this.received.get(), this.dropped.get(), 0L, 0L, this.ownIgnores.get(), this.closed);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String adapterType() {
        return "in-memory";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String endpointSummary() {
        return "in-memory";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        this.closed = true;
        this.members.clear();
    }

    private String key(final MulticastRegistryOptions options) {
        return options.group() + ":" + options.port() + ":" + options.contextId();
    }
}
