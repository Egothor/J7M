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

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.registry.api.DiscoveryEvent;
import org.j7m.middleware.registry.api.DiscoverySource;
import org.j7m.middleware.registry.api.DiscoveryStatistics;
import org.j7m.middleware.registry.api.PeerGraphRegistry;
import org.j7m.middleware.registry.api.PeerQuery;
import org.j7m.middleware.registry.api.RegistryClient;
import org.j7m.middleware.registry.api.RegistryDiagnostics;
import org.j7m.middleware.registry.api.RegistryEndpoint;
import org.j7m.middleware.registry.api.RegistryPeerQuality;
import org.j7m.middleware.registry.api.RegistryPeerState;
import org.j7m.middleware.registry.api.RegistryPeerStatus;
import org.j7m.middleware.registry.api.RegistryQuery;
import org.j7m.middleware.registry.api.RegistryRecord;
import org.j7m.middleware.registry.api.RegistryRecordVerifier;
import org.j7m.middleware.registry.api.RegistryTrustStatus;
import org.j7m.middleware.registry.internal.RegistryRecordTrust;
import org.j7m.middleware.security.api.SecurityAuditSink;

/**
 * Bootstrap-hint registry discovery client.
 *
 * <p>The client wraps a local registry and expands through configured endpoint
 * hints and peer statuses. All discovery is bounded by deadline, maximum depth,
 * maximum contacted peers, maximum candidates, and cache TTL.</p>
 */
public final class BootstrapRegistryClient implements RegistryClient, PeerGraphRegistry, RegistryDiagnostics {
    private static final int MAX_PEER_GRAPH = 1024;
    private static final int MAX_CONTACTED_PEERS = 4096;
    private static final int MAX_CACHE_ENTRIES = 1024;
    private static final int MAX_EXPANSION_BYTES = 512 * 1024;
    private final RegistryClient local;
    private final BootstrapRegistryOptions options;
    private final Clock clock;
    private final ConcurrentMap<String, RegistryPeerStatus> peerGraph;
    private final ConcurrentMap<String, CacheEntry> cache;
    private final ConcurrentMap<String, Long> contactedPeers;
    private final ConcurrentMap<String, RegistryRecordTrust.ReplayEntry> replay;
    private final RegistryRecordTrust trust;
    private final AtomicLong trustRejected;
    private final AtomicLong trustDowngraded;
    private final Deque<DiscoveryEvent> recentEvents;
    private final Object eventLock;
    private final AtomicLong registryQueries;
    private final AtomicLong bootstrapQueries;
    private final AtomicLong peerSuccesses;
    private final AtomicLong peerFailures;
    private volatile boolean closed;

    /**
     * Creates a bootstrap registry.
     *
     * @param local local registry
     * @param options options
     */
    public BootstrapRegistryClient(final RegistryClient local, final BootstrapRegistryOptions options) {
        this(local, Clock.systemUTC(), options);
    }

    /**
     * Creates a bootstrap registry.
     *
     * @param local local registry
     * @param clock registry clock
     * @param options options
     */
    public BootstrapRegistryClient(final RegistryClient local, final Clock clock, final BootstrapRegistryOptions options) {
        this(local, clock, options, null, false, null);
    }

    /**
     * Creates a bootstrap registry with explicit received-record trust policy.
     *
     * @param local local registry
     * @param clock registry clock
     * @param options options
     * @param verifier record verifier
     * @param strictTrust true to reject invalid or unsigned remote records
     * @param auditSink optional audit sink for suspicious records
     */
    public BootstrapRegistryClient(
            final RegistryClient local,
            final Clock clock,
            final BootstrapRegistryOptions options,
            final RegistryRecordVerifier verifier,
            final boolean strictTrust,
            final SecurityAuditSink auditSink) {
        if (local == null) {
            throw new IllegalArgumentException("local registry must not be null");
        }
        this.local = local;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.options = options == null
                ? new BootstrapRegistryOptions("bootstrap", "default", List.of(), null, 0, 16, 64, null, null)
                : options;
        this.peerGraph = new ConcurrentHashMap<>();
        this.cache = new ConcurrentHashMap<>();
        this.contactedPeers = new ConcurrentHashMap<>();
        this.replay = new ConcurrentHashMap<>();
        this.trustRejected = new AtomicLong();
        this.trustDowngraded = new AtomicLong();
        this.trust = new RegistryRecordTrust(verifier, strictTrust, this.clock, this.replay, this.trustRejected, this.trustDowngraded);
        this.recentEvents = new ArrayDeque<>(128);
        this.eventLock = new Object();
        this.registryQueries = new AtomicLong();
        this.bootstrapQueries = new AtomicLong();
        this.peerSuccesses = new AtomicLong();
        this.peerFailures = new AtomicLong();
        this.record("bootstrap registry active", Map.of("hints", Integer.toString(this.options.bootstrapEndpoints().size())));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bind(final RegistryRecord record) {
        this.ensureOpen();
        this.local.bind(record);
        this.cache.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unbind(final ObjectRef ref) {
        this.ensureOpen();
        this.local.unbind(ref);
        this.cache.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<RegistryRecord> lookup(final ObjectRef ref) {
        this.ensureOpen();
        Optional<RegistryRecord> localMatch = this.local.lookup(ref);
        if (localMatch.isPresent()) {
            return localMatch;
        }
        RegistryQuery query = new RegistryQuery(ref.serviceName(), ref.interfaces(), ref.metadata(), ref.registryContext(),
                this.clock.millis(), 1, this.deadlineMillis(), this.options.maxContactedPeers(), this.options.maxDepth(), DiscoverySource.BOOTSTRAP);
        return this.discover(query, ref).stream().findFirst();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<RegistryRecord> list(final RegistryQuery query) {
        this.ensureOpen();
        RegistryQuery safeQuery = query == null
                ? new RegistryQuery("", List.of(), Map.of(), this.options.contextId(), this.clock.millis(), this.options.maxCandidates())
                : query;
        return this.discover(safeQuery, null);
    }

    private List<RegistryRecord> discover(final RegistryQuery safeQuery, final ObjectRef target) {
        this.registryQueries.incrementAndGet();
        String cacheKey = this.cacheKey(safeQuery) + (target == null ? "" : "|lookup|"
                + target.runtimeId() + "|" + target.objectId() + "|" + target.generation());
        CacheEntry cached = this.cache.get(cacheKey);
        if (cached != null && cached.expiresAtMillis >= this.clock.millis()) {
            return this.sortedLimited(cached.records, Math.min(this.options.maxCandidates(), safeQuery.limit()));
        }
        List<RegistryRecord> results = target == null ? new ArrayList<>(this.local.list(safeQuery)) : new ArrayList<>();
        long deadline = safeQuery.deadlineMillis() > 0L
                ? Math.min(safeQuery.deadlineMillis(), this.deadlineMillis()) : this.deadlineMillis();
        RegistryQuery boundedQuery = new RegistryQuery(safeQuery.serviceName(), safeQuery.interfaces(),
                safeQuery.requiredParams(), safeQuery.registryContext(), safeQuery.nowMillis(), safeQuery.limit(),
                deadline, safeQuery.maxPeerCount(), safeQuery.maxDepth(), safeQuery.source());
        int maxDepth = Math.min(this.options.maxDepth(), safeQuery.maxDepth());
        int maxContacts = Math.min(this.options.maxContactedPeers(), safeQuery.maxPeerCount());
        int maxCandidates = Math.min(this.options.maxCandidates(), safeQuery.limit());
        Deque<PeerWork> queue = new ArrayDeque<>();
        for (String endpoint : this.options.bootstrapEndpoints()) {
            if (queue.size() < maxContacts) {
                queue.addLast(new PeerWork(endpoint, 0));
            }
        }
        Set<String> visited = new HashSet<>();
        int expansionBytes = 0;
        while (!queue.isEmpty()
                && visited.size() < maxContacts
                && results.size() < maxCandidates
                && this.clock.millis() <= deadline) {
            PeerWork work = queue.removeFirst();
            if (work.depth > maxDepth || !visited.add(work.endpoint)) {
                continue;
            }
            expansionBytes = this.addExpansionBytes(expansionBytes, work.endpoint);
            this.rememberContacted(work.endpoint);
            this.bootstrapQueries.incrementAndGet();
            long started = System.nanoTime();
            try {
                Optional<RegistryClient> resolved = this.options.resolver().resolve(work.endpoint, deadline);
                if (resolved.isEmpty()) {
                    this.peerFailures.incrementAndGet();
                    this.updatePeer(this.peerStatus(work.endpoint, RegistryPeerState.DEAD, System.nanoTime() - started));
                    continue;
                }
                RegistryClient peer = resolved.get();
                List<RegistryRecord> received = target == null ? peer.list(boundedQuery) : peer.lookup(target).stream().toList();
                for (RegistryRecord record : received) {
                    RegistryRecord admitted = this.admitRemoteRecord(record, safeQuery.registryContext());
                    if (RegistryRecordTrust.trustedCandidate(admitted) && admitted.activeAt(this.clock.millis())
                            && (target == null || target.objectId().equals(admitted.ref().objectId())
                                    && target.runtimeId().equals(admitted.ref().runtimeId())
                                    && target.generation() == admitted.ref().generation())
                            && !this.containsObject(results, admitted.ref().objectId())) {
                        expansionBytes = this.addExpansionBytes(expansionBytes, admitted.ref().objectId());
                        results.add(admitted);
                    }
                    if (results.size() >= maxCandidates) {
                        break;
                    }
                }
                this.peerSuccesses.incrementAndGet();
                this.updatePeer(this.peerStatus(work.endpoint, RegistryPeerState.ALIVE, System.nanoTime() - started));
                if (work.depth < maxDepth && this.clock.millis() < deadline) {
                    for (RegistryPeerStatus status : peer.peers(new PeerQuery(safeQuery.registryContext(), true, maxContacts))) {
                        this.updatePeer(status);
                        for (RegistryEndpoint endpoint : status.endpoints()) {
                            if (!visited.contains(endpoint.endpoint()) && queue.size() < maxContacts && expansionBytes < MAX_EXPANSION_BYTES) {
                                expansionBytes = this.addExpansionBytes(expansionBytes, endpoint.endpoint());
                                queue.addLast(new PeerWork(endpoint.endpoint(), work.depth + 1));
                            }
                        }
                    }
                }
            } catch (MiddlewareException exception) {
                this.peerFailures.incrementAndGet();
                this.updatePeer(this.peerStatus(work.endpoint, RegistryPeerState.DEAD, System.nanoTime() - started));
            }
        }
        List<RegistryRecord> finalResults = this.sortedLimited(results, maxCandidates);
        this.cache.put(cacheKey, new CacheEntry(finalResults, this.clock.millis() + Math.max(1L, this.options.cacheTtl().toMillis()), this.clock.millis()));
        this.trimCache();
        this.record("bootstrap list completed", Map.of("count", Integer.toString(finalResults.size()), "contacted", Integer.toString(visited.size())));
        return finalResults;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void heartbeat(final ObjectRef ref, final long leaseUntil) {
        this.ensureOpen();
        this.local.heartbeat(ref, leaseUntil);
        this.cache.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<RegistryPeerStatus> peers(final PeerQuery query) {
        this.ensureOpen();
        PeerQuery safeQuery = query == null ? new PeerQuery(this.options.contextId()) : query;
        Map<String, RegistryPeerStatus> values = new LinkedHashMap<>();
        for (RegistryPeerStatus status : this.local.peers(safeQuery)) {
            values.put(status.peerId(), status);
        }
        for (RegistryPeerStatus status : this.peerGraph.values()) {
            boolean contextMatches = status.knownContexts().isEmpty() || status.knownContexts().contains(safeQuery.registryContext());
            boolean stateMatches = safeQuery.includeDead() || status.alive();
            if (contextMatches && stateMatches) {
                values.put(status.peerId(), status);
            }
            if (values.size() >= safeQuery.limit()) {
                break;
            }
        }
        return values.values().stream().sorted(Comparator.comparing(RegistryPeerStatus::peerId)).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePeer(final RegistryPeerStatus status) {
        if (status != null) {
            this.peerGraph.put(status.peerId(), status);
            this.trimPeerGraph();
            if (this.local instanceof PeerGraphRegistry graph) {
                graph.updatePeer(status);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        this.closed = true;
        this.local.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DiscoveryStatistics discoveryStatistics() {
        return new DiscoveryStatistics(this.registryQueries.get(), 0L, 0L, this.bootstrapQueries.get(),
                this.peerSuccesses.get(), this.peerFailures.get(), 0L, 0L, 0L,
                Map.of("peerGraphSize", Long.valueOf(this.peerGraph.size()), "cacheSize", Long.valueOf(this.cache.size()),
                        "registryTrustRejected", this.trustRejected.get(), "registryTrustDowngraded", this.trustDowngraded.get()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<DiscoveryEvent> recentDiscoveryEvents() {
        synchronized (this.eventLock) {
            return List.copyOf(this.recentEvents);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int activeDiscoveryQuestions() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int lookupCacheSize() {
        this.cache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis < this.clock.millis());
        return this.cache.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int bootstrapHintCount() {
        return this.options.bootstrapEndpoints().size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int contactedBootstrapPeerCount() {
        return this.contactedPeers.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean multicastListenerRunning() {
        return false;
    }

    /**
     * Clears cached lookup/list results.
     */
    public void clearCache() {
        this.cache.clear();
    }

    private RegistryRecord admitRemoteRecord(final RegistryRecord record, final String expectedContext) {
        try {
            return this.trust.admit(record, expectedContext);
        } catch (MiddlewareException exception) {
            return null;
        }
    }

    private List<RegistryRecord> sortedLimited(final List<RegistryRecord> records, final int limit) {
        return records.stream()
                .filter(record -> record.activeAt(this.clock.millis()))
                .sorted(Comparator.comparing((RegistryRecord record) -> record.ref().runtimeId()).thenComparing(record -> record.ref().objectId()))
                .limit(limit)
                .toList();
    }

    private RegistryPeerStatus peerStatus(final String endpoint, final RegistryPeerState state, final long latencyNanos) {
        boolean alive = state == RegistryPeerState.ALIVE;
        return new RegistryPeerStatus(endpoint, alive, state, false,
                latencyNanos > 5_000_000L ? RegistryPeerQuality.SLOW : RegistryPeerQuality.FAST,
                false, RegistryTrustStatus.UNVERIFIED, this.clock.millis(), latencyNanos, 0.0D, alive ? 1.0D : 0.0D,
                List.of(new RegistryEndpoint("bootstrap", endpoint, Map.of())),
                List.of(1), List.of(this.options.contextId()), this.options.registryId(), this.clock.millis(), Map.of());
    }

    private long deadlineMillis() {
        return this.clock.millis() + Math.max(1L, this.options.deadline().toMillis());
    }

    private String cacheKey(final RegistryQuery query) {
        return query.registryContext() + "|" + query.serviceName() + "|" + query.interfaces() + "|" + query.requiredParams();
    }

    private boolean containsObject(final List<RegistryRecord> records, final String objectId) {
        for (RegistryRecord record : records) {
            if (record.ref().objectId().equals(objectId)) {
                return true;
            }
        }
        return false;
    }

    private int addExpansionBytes(final int currentBytes, final String text) {
        int bytes = text == null ? 0 : text.length();
        int next = currentBytes + bytes;
        if (next > MAX_EXPANSION_BYTES) {
            return MAX_EXPANSION_BYTES;
        }
        return next;
    }

    private void rememberContacted(final String endpoint) {
        this.contactedPeers.put(endpoint, Long.valueOf(this.clock.millis()));
        if (this.contactedPeers.size() <= MAX_CONTACTED_PEERS) {
            return;
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>(this.contactedPeers.entrySet());
        entries.sort(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()));
        for (Map.Entry<String, Long> entry : entries) {
            if (this.contactedPeers.size() <= MAX_CONTACTED_PEERS) {
                return;
            }
            this.contactedPeers.remove(entry.getKey(), entry.getValue());
        }
    }

    private void trimPeerGraph() {
        if (this.peerGraph.size() <= MAX_PEER_GRAPH) {
            return;
        }
        List<RegistryPeerStatus> statuses = new ArrayList<>(this.peerGraph.values());
        statuses.sort(Comparator
                .comparing((RegistryPeerStatus status) -> Boolean.valueOf(status.alive()))
                .thenComparingLong(RegistryPeerStatus::lastSeenMillis)
                .thenComparing(RegistryPeerStatus::peerId));
        for (RegistryPeerStatus status : statuses) {
            if (this.peerGraph.size() <= MAX_PEER_GRAPH) {
                return;
            }
            this.peerGraph.remove(status.peerId(), status);
        }
    }

    private void trimCache() {
        long nowMillis = this.clock.millis();
        this.cache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis < nowMillis);
        if (this.cache.size() <= MAX_CACHE_ENTRIES) {
            return;
        }
        List<Map.Entry<String, CacheEntry>> entries = new ArrayList<>(this.cache.entrySet());
        entries.sort(Comparator
                .comparing((Map.Entry<String, CacheEntry> entry) -> Boolean.valueOf(entry.getValue().expiresAtMillis >= nowMillis))
                .thenComparingLong(entry -> entry.getValue().createdAtMillis())
                .thenComparing(Map.Entry::getKey));
        for (Map.Entry<String, CacheEntry> entry : entries) {
            if (this.cache.size() <= MAX_CACHE_ENTRIES) {
                return;
            }
            this.cache.remove(entry.getKey(), entry.getValue());
        }
    }

    private void record(final String message, final Map<String, String> metadata) {
        DiscoveryEvent event = new DiscoveryEvent(this.clock.millis(), DiscoverySource.BOOTSTRAP, message, metadata);
        synchronized (this.eventLock) {
            if (this.recentEvents.size() >= 128) {
                this.recentEvents.removeFirst();
            }
            this.recentEvents.addLast(event);
        }
    }

    private void ensureOpen() {
        if (this.closed) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "bootstrap registry is closed");
        }
    }

    private record PeerWork(String endpoint, int depth) {
    }

    private record CacheEntry(List<RegistryRecord> records, long expiresAtMillis, long createdAtMillis) {
        private CacheEntry {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }
}
