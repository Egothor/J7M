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

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Semaphore;
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
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.registry.internal.RegistryRecordTrust;
import org.j7m.middleware.security.api.SecurityAuditSink;

/**
 * Decentralized multicast-style registry client.
 *
 * <p>The client owns a local embedded registry, listens on an injected
 * multicast bus, answers bounded discovery questions, ignores self messages,
 * filters by registry context, and returns only active records. All methods are
 * thread-safe.</p>
 */
public final class MulticastRegistryClient implements RegistryClient, PeerGraphRegistry, RegistryDiagnostics {
    private static final int MAX_IN_FLIGHT_QUESTIONS = 1024;
    private static final int MAX_TRUSTED_REMOTE_CACHE = 4096;
    private final EmbeddedRegistryClient local;
    private final MulticastRegistryOptions options;
    private final Clock clock;
    private final ConcurrentMap<String, RegistryPeerStatus> peerGraph;
    private final ConcurrentMap<String, InFlightQuestion> inFlightQuestions;
    private final ConcurrentMap<String, RegistryRecord> trustedRemoteRecords;
    private final Semaphore inFlightQuestionPermits;
    private final Deque<DiscoveryEvent> recentEvents;
    private final Object eventLock;
    private final AtomicLong queryCount;
    private final AtomicLong sent;
    private final AtomicLong received;
    private final AtomicLong peerSuccesses;
    private final AtomicLong peerFailures;
    private final AtomicLong heartbeatSuccesses;
    private final AtomicLong heartbeatFailures;
    private final AtomicLong leaseExpirations;
    private volatile boolean closed;

    /**
     * Creates a multicast registry with a fresh embedded store and system
     * clock.
     *
     * @param options multicast options
     */
    public MulticastRegistryClient(final MulticastRegistryOptions options) {
        this(new EmbeddedRegistryStore(), Clock.systemUTC(), options);
    }

    /**
     * Creates a multicast registry with supplied store and clock.
     *
     * @param store local embedded store
     * @param clock registry clock
     * @param options multicast options
     */
    public MulticastRegistryClient(final EmbeddedRegistryStore store, final Clock clock, final MulticastRegistryOptions options) {
        this(store, clock, options, null, false, null);
    }

    /**
     * Creates a multicast registry with explicit received-record trust policy.
     *
     * @param store local embedded store
     * @param clock registry clock
     * @param options multicast options
     * @param verifier record verifier
     * @param strictTrust true to reject invalid or unsigned records
     * @param auditSink optional audit sink for suspicious records
     */
    public MulticastRegistryClient(
            final EmbeddedRegistryStore store,
            final Clock clock,
            final MulticastRegistryOptions options,
            final RegistryRecordVerifier verifier,
            final boolean strictTrust,
            final SecurityAuditSink auditSink) {
        this.local = new EmbeddedRegistryClient(store, clock, verifier, strictTrust, auditSink);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.options = options == null ? MulticastRegistryOptions.defaults("registry-" + UUID.randomUUID()) : options;
        this.peerGraph = new ConcurrentHashMap<>();
        this.inFlightQuestions = new ConcurrentHashMap<>();
        this.trustedRemoteRecords = new ConcurrentHashMap<>();
        this.inFlightQuestionPermits = new Semaphore(MAX_IN_FLIGHT_QUESTIONS);
        this.recentEvents = new ArrayDeque<>(128);
        this.eventLock = new Object();
        this.queryCount = new AtomicLong();
        this.sent = new AtomicLong();
        this.received = new AtomicLong();
        this.peerSuccesses = new AtomicLong();
        this.peerFailures = new AtomicLong();
        this.heartbeatSuccesses = new AtomicLong();
        this.heartbeatFailures = new AtomicLong();
        this.leaseExpirations = new AtomicLong();
        this.options.bus().register(this);
        this.updatePeer(this.localPeer());
        this.record(DiscoverySource.MULTICAST, "multicast listener started", Map.of("group", this.options.group(), "port", Integer.toString(this.options.port())));
    }

    MulticastRegistryOptions options() {
        return this.options;
    }

    Clock clock() {
        return this.clock;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bind(final RegistryRecord record) {
        this.ensureOpen();
        this.local.bind(record);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unbind(final ObjectRef ref) {
        this.ensureOpen();
        this.local.unbind(ref);
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
                this.clock.millis(), 1, this.deadlineMillis(), this.options.maxPeers(), 0, DiscoverySource.MULTICAST);
        return this.list(query).stream()
                .filter(record -> record.ref().objectId().equals(ref.objectId()))
                .findFirst();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<RegistryRecord> list(final RegistryQuery query) {
        this.ensureOpen();
        RegistryQuery safeQuery = query == null
                ? new RegistryQuery("", List.of(), Map.of(), this.options.contextId(), this.clock.millis(), this.options.maxResults())
                : query;
        safeQuery = this.effectiveQuery(safeQuery);
        this.queryCount.incrementAndGet();
        List<RegistryRecord> results = new ArrayList<>(this.local.list(safeQuery));
        long deadline = safeQuery.deadlineMillis() > 0L ? safeQuery.deadlineMillis() : this.deadlineMillis();
        String questionId = UUID.randomUUID().toString();
        InFlightQuestion question = this.reserveQuestion(questionId, deadline);
        try {
            if (results.size() < safeQuery.limit()) {
                MulticastMessageEnvelope envelope = new MulticastMessageEnvelope(MulticastMessageType.QUESTION, this.options.registryId(), "*",
                        safeQuery.registryContext(), questionId, MulticastQueryType.LIST, this.clock.millis(),
                        Math.max(1L, deadline - this.clock.millis()), 0, 1, Map.of("serviceName", safeQuery.serviceName()));
                for (RegistryRecord record : this.options.bus().list(this, envelope, safeQuery, deadline)) {
                    RegistryRecord admitted = this.admitRemoteRecord(record, safeQuery.registryContext());
                    if (RegistryRecordTrust.trustedCandidate(admitted)
                            && results.size() < safeQuery.limit()
                            && !this.containsObject(results, admitted.ref().objectId())) {
                        results.add(admitted);
                    }
                    if (results.size() >= safeQuery.limit()) {
                        break;
                    }
                }
            }
            if (results.isEmpty()) {
                this.record(DiscoverySource.MULTICAST, "multicast list returned no candidates", Map.of("questionId", questionId));
            } else {
                this.record(DiscoverySource.MULTICAST, "multicast list returned candidates", Map.of("questionId", questionId, "count", Integer.toString(results.size())));
            }
            return this.sortedLimited(results, safeQuery.limit());
        } finally {
            InFlightQuestion removed = this.inFlightQuestions.remove(questionId);
            if (removed != null) {
                removed.release();
            } else {
                question.release();
            }
            this.expireQuestionCache();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void heartbeat(final ObjectRef ref, final long leaseUntil) {
        this.ensureOpen();
        try {
            this.local.heartbeat(ref, leaseUntil);
            this.heartbeatSuccesses.incrementAndGet();
        } catch (MiddlewareException exception) {
            this.heartbeatFailures.incrementAndGet();
            throw exception;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<RegistryPeerStatus> peers(final PeerQuery query) {
        this.ensureOpen();
        PeerQuery safeQuery = query == null ? new PeerQuery(this.options.contextId()) : query;
        Map<String, RegistryPeerStatus> statuses = new LinkedHashMap<>();
        this.expirePeerGraph();
        this.trimPeerGraph();
        for (RegistryPeerStatus status : this.peerGraph.values()) {
            boolean contextMatches = status.knownContexts().isEmpty() || status.knownContexts().contains(safeQuery.registryContext());
            boolean stateMatches = safeQuery.includeDead() || status.alive();
            if (contextMatches && stateMatches) {
                statuses.put(status.peerId(), status);
            }
            if (statuses.size() >= safeQuery.limit()) {
                break;
            }
        }
        return statuses.values().stream().sorted(Comparator.comparing(RegistryPeerStatus::peerId)).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePeer(final RegistryPeerStatus status) {
        this.ensureOpen();
        if (status != null) {
            this.expirePeerGraph();
            this.peerGraph.put(status.peerId(), status);
            this.trimPeerGraph();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        this.closed = true;
        this.options.bus().unregister(this);
        for (InFlightQuestion question : this.inFlightQuestions.values()) {
            question.release();
        }
        this.inFlightQuestions.clear();
        this.peerGraph.clear();
        this.trustedRemoteRecords.clear();
        this.local.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DiscoveryStatistics discoveryStatistics() {
        MulticastBusDiagnostics bus = this.options.bus().diagnostics();
        Map<String, Long> localMetadata = this.local.discoveryStatistics().metadata();
        return new DiscoveryStatistics(this.queryCount.get(), this.sent.get() + bus.sent(), this.received.get() + bus.received(), 0L,
                this.peerSuccesses.get(), this.peerFailures.get(), this.heartbeatSuccesses.get(), this.heartbeatFailures.get(),
                this.leaseExpirations.get(), Map.of(
                        "peerGraphSize", Long.valueOf(this.peerGraph.size()),
                        "trustedRemoteCacheSize", Long.valueOf(this.trustedRemoteRecords.size()),
                        "multicastDropped", Long.valueOf(bus.dropped()),
                        "multicastDecodeFailures", Long.valueOf(bus.decodeFailures()),
                        "multicastStaleDrops", Long.valueOf(bus.staleDrops()),
                        "multicastOwnIgnores", Long.valueOf(bus.ownMessageIgnores()),
                        "registryTrustRejected", localMetadata.getOrDefault("registryTrustRejected", Long.valueOf(0L)),
                        "registryTrustDowngraded", localMetadata.getOrDefault("registryTrustDowngraded", Long.valueOf(0L))));
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
        this.expireQuestionCache();
        return this.inFlightQuestions.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int lookupCacheSize() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int bootstrapHintCount() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int contactedBootstrapPeerCount() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean multicastListenerRunning() {
        return !this.closed && !this.options.bus().diagnostics().closed();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String multicastAdapterType() {
        return this.options.bus().adapterType();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String multicastEndpoint() {
        return this.options.bus().endpointSummary();
    }

    /**
     * Expires stale in-flight questions.
     */
    public void expireQuestionCache() {
        long now = this.clock.millis();
        for (Map.Entry<String, InFlightQuestion> entry : this.inFlightQuestions.entrySet()) {
            InFlightQuestion question = entry.getValue();
            if (question.deadlineMillis() < now && this.inFlightQuestions.remove(entry.getKey(), question)) {
                question.release();
            }
        }
    }

    List<RegistryRecord> answerList(final MulticastMessageEnvelope envelope, final RegistryQuery query) {
        this.received.incrementAndGet();
        if (envelope.senderId().equals(this.options.registryId())) {
            return List.of();
        }
        if (!envelope.contextId().equals(this.options.contextId())) {
            return List.of();
        }
        if (envelope.createdAtMillis() + envelope.ttlMillis() < this.clock.millis()) {
            return List.of();
        }
        RegistryQuery safeQuery = this.effectiveQuery(query);
        this.record(DiscoverySource.MULTICAST, "answered multicast list", Map.of("questionId", envelope.questionId()));
        return this.local.list(safeQuery);
    }

    RegistryRecord admitRemoteRecord(final RegistryRecord record, final String expectedContext) {
        try {
            RegistryRecord admitted = this.local.admitReceivedRecord(record, expectedContext);
            if (RegistryRecordTrust.trustedCandidate(admitted)) {
                this.rememberTrustedRemote(admitted);
            }
            return admitted;
        } catch (MiddlewareException exception) {
            return this.cachedTrustedRemote(record, expectedContext);
        }
    }

    private List<RegistryRecord> sortedLimited(final List<RegistryRecord> records, final int limit) {
        return records.stream()
                .filter(record -> {
                    boolean active = record.activeAt(this.clock.millis());
                    if (!active) {
                        this.leaseExpirations.incrementAndGet();
                    }
                    return active;
                })
                .sorted(Comparator.comparing((RegistryRecord record) -> record.ref().runtimeId()).thenComparing(record -> record.ref().objectId()))
                .limit(limit)
                .toList();
    }

    private RegistryQuery effectiveQuery(final RegistryQuery query) {
        RegistryQuery base = query == null
                ? new RegistryQuery("", List.of(), Map.of(), this.options.contextId(), this.clock.millis(), this.options.maxResults())
                : query;
        int effectivePeers = Math.max(1, Math.min(base.maxPeerCount(), this.options.maxPeers()));
        int effectiveResults = Math.max(1, Math.min(Math.min(base.limit(), this.options.maxResults()),
                Math.min(effectivePeers, MulticastProtocolCodec.MAX_RECORDS)));
        return new RegistryQuery(base.serviceName(), base.interfaces(), base.requiredParams(), base.registryContext(), base.nowMillis(),
                effectiveResults, base.deadlineMillis(), effectivePeers, base.maxDepth(), base.source());
    }

    private RegistryPeerStatus localPeer() {
        return this.peerStatus(0L, RegistryPeerState.ALIVE);
    }

    private void rememberTrustedRemote(final RegistryRecord record) {
        this.expireTrustedRemoteCache();
        this.trustedRemoteRecords.put(this.remoteCacheKey(record.ref()), record);
        this.trimTrustedRemoteCache();
    }

    private RegistryRecord cachedTrustedRemote(final RegistryRecord record, final String expectedContext) {
        if (record == null) {
            return null;
        }
        String context = expectedContext == null || expectedContext.isBlank() ? record.ref().registryContext() : expectedContext;
        if (!record.ref().registryContext().equals(context)) {
            return null;
        }
        long nowMillis = this.clock.millis();
        if (!record.activeAt(nowMillis)) {
            return null;
        }
        this.expireTrustedRemoteCache();
        RegistryRecord cached = this.trustedRemoteRecords.get(this.remoteCacheKey(record.ref()));
        if (cached == null || !cached.activeAt(nowMillis) || !this.sameRemotePublication(record, cached)) {
            return null;
        }
        return cached;
    }

    private boolean sameRemotePublication(final RegistryRecord received, final RegistryRecord cached) {
        ObjectRef receivedRef = received.ref();
        ObjectRef cachedRef = cached.ref();
        return receivedRef.objectId().equals(cachedRef.objectId())
                && receivedRef.runtimeId().equals(cachedRef.runtimeId())
                && receivedRef.registryContext().equals(cachedRef.registryContext())
                && receivedRef.generation() == cachedRef.generation()
                && receivedRef.serviceName().equals(cachedRef.serviceName())
                && receivedRef.interfaces().equals(cachedRef.interfaces())
                && receivedRef.transport().equals(cachedRef.transport())
                && receivedRef.endpoint().equals(cachedRef.endpoint())
                && receivedRef.metadata().equals(cachedRef.metadata())
                && received.state() == cached.state()
                && received.createdAtMillis() == cached.createdAtMillis()
                && received.lastHeartbeatMillis() == cached.lastHeartbeatMillis()
                && received.leaseUntilMillis() == cached.leaseUntilMillis()
                && received.parameters().equals(cached.parameters())
                && this.sameAuthenticationMaterial(received.authMetadata(), cached.authMetadata());
    }

    private boolean sameAuthenticationMaterial(final Map<String, String> received, final Map<String, String> cached) {
        return this.sameMetadataValue(received, cached, "keyId")
                && this.sameMetadataValue(received, cached, "algorithm")
                && this.sameMetadataValue(received, cached, "keyFingerprint")
                && this.sameMetadataValue(received, cached, "publisher")
                && this.sameMetadataValue(received, cached, "signature");
    }

    private boolean sameMetadataValue(final Map<String, String> first, final Map<String, String> second, final String key) {
        String firstValue = first.get(key);
        String secondValue = second.get(key);
        return firstValue == null ? secondValue == null : firstValue.equals(secondValue);
    }

    private void expireTrustedRemoteCache() {
        long nowMillis = this.clock.millis();
        for (Map.Entry<String, RegistryRecord> entry : this.trustedRemoteRecords.entrySet()) {
            if (!entry.getValue().activeAt(nowMillis)) {
                this.trustedRemoteRecords.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private void trimTrustedRemoteCache() {
        if (this.trustedRemoteRecords.size() <= MAX_TRUSTED_REMOTE_CACHE) {
            return;
        }
        List<RegistryRecord> records = new ArrayList<>(this.trustedRemoteRecords.values());
        records.sort(Comparator
                .comparingLong(RegistryRecord::lastHeartbeatMillis)
                .thenComparing(record -> this.remoteCacheKey(record.ref())));
        for (RegistryRecord record : records) {
            if (this.trustedRemoteRecords.size() <= MAX_TRUSTED_REMOTE_CACHE) {
                return;
            }
            this.trustedRemoteRecords.remove(this.remoteCacheKey(record.ref()), record);
        }
    }

    private String remoteCacheKey(final ObjectRef ref) {
        return ref.registryContext() + "|" + ref.runtimeId() + "|" + ref.objectId() + "|" + ref.generation();
    }

    private InFlightQuestion reserveQuestion(final String questionId, final long deadlineMillis) {
        if (!this.inFlightQuestionPermits.tryAcquire()) {
            throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "multicast in-flight question limit exceeded");
        }
        InFlightQuestion question = new InFlightQuestion(deadlineMillis, this.inFlightQuestionPermits);
        InFlightQuestion previous = this.inFlightQuestions.putIfAbsent(questionId, question);
        if (previous != null) {
            question.release();
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "duplicate multicast question id");
        }
        return question;
    }

    private boolean containsObject(final List<RegistryRecord> records, final String objectId) {
        for (RegistryRecord record : records) {
            if (record.ref().objectId().equals(objectId)) {
                return true;
            }
        }
        return false;
    }

    private void expirePeerGraph() {
        long nowMillis = this.clock.millis();
        long ttlMillis = this.options.peerGraphTtlMillis();
        if (ttlMillis == Long.MAX_VALUE) {
            return;
        }
        long cutoff = nowMillis <= Long.MIN_VALUE + ttlMillis ? Long.MIN_VALUE : nowMillis - ttlMillis;
        for (Map.Entry<String, RegistryPeerStatus> entry : this.peerGraph.entrySet()) {
            RegistryPeerStatus status = entry.getValue();
            if (status.lastSeenMillis() <= 0L || status.lastSeenMillis() < cutoff) {
                this.peerGraph.remove(entry.getKey(), status);
            }
        }
    }

    private void trimPeerGraph() {
        int maxEntries = this.options.maxPeerGraphEntries();
        if (this.peerGraph.size() <= maxEntries) {
            return;
        }
        List<RegistryPeerStatus> statuses = new ArrayList<>(this.peerGraph.values());
        statuses.sort(Comparator
                .comparing((RegistryPeerStatus status) -> Boolean.valueOf(status.alive()))
                .thenComparingLong(RegistryPeerStatus::lastSeenMillis)
                .thenComparing(RegistryPeerStatus::peerId));
        for (RegistryPeerStatus status : statuses) {
            if (this.peerGraph.size() <= maxEntries) {
                return;
            }
            this.peerGraph.remove(status.peerId(), status);
        }
    }

    RegistryPeerStatus peerStatus(final long latencyNanos, final RegistryPeerState state) {
        return this.peerStatusFor(this.options.registryId(), latencyNanos, state);
    }

    RegistryPeerStatus peerStatusFor(final String peerId, final long latencyNanos, final RegistryPeerState state) {
        boolean alive = state == RegistryPeerState.ALIVE;
        return new RegistryPeerStatus(peerId, alive, state, false,
                latencyNanos > 5_000_000L ? RegistryPeerQuality.SLOW : RegistryPeerQuality.FAST,
                peerId.equals(this.options.registryId()), RegistryTrustStatus.UNVERIFIED, this.clock.millis(), latencyNanos, 0.0D, alive ? 1.0D : 0.0D,
                List.of(new RegistryEndpoint("mcast", "mcast://" + this.options.group() + ":" + this.options.port(), Map.of())),
                List.of(1), List.of(this.options.contextId()), "", this.clock.millis(), Map.of());
    }

    void peerContactSucceeded(final RegistryPeerStatus status) {
        this.peerSuccesses.incrementAndGet();
        this.updatePeer(status);
    }

    void peerContactFailed(final RegistryPeerStatus status) {
        this.peerFailures.incrementAndGet();
        this.updatePeer(status);
    }

    private long deadlineMillis() {
        return this.clock.millis() + Math.max(1L, this.options.queryDeadline().toMillis());
    }

    private void record(final DiscoverySource source, final String message, final Map<String, String> metadata) {
        DiscoveryEvent event = new DiscoveryEvent(this.clock.millis(), source, message, metadata);
        synchronized (this.eventLock) {
            if (this.recentEvents.size() >= 128) {
                this.recentEvents.removeFirst();
            }
            this.recentEvents.addLast(event);
        }
    }

    private void ensureOpen() {
        if (this.closed) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "multicast registry is closed");
        }
    }

    private static final class InFlightQuestion {
        private final long deadlineMillis;
        private final Semaphore permits;
        private final AtomicBoolean released;

        private InFlightQuestion(final long deadlineMillis, final Semaphore permits) {
            this.deadlineMillis = deadlineMillis;
            this.permits = permits;
            this.released = new AtomicBoolean();
        }

        private long deadlineMillis() {
            return this.deadlineMillis;
        }

        private void release() {
            if (this.released.compareAndSet(false, true)) {
                this.permits.release();
            }
        }
    }
}
