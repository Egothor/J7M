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

package org.j7m.middleware.registry.embedded;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ObjectState;
import org.j7m.middleware.registry.api.DiscoveryEvent;
import org.j7m.middleware.registry.api.DiscoverySource;
import org.j7m.middleware.registry.api.DiscoveryStatistics;
import org.j7m.middleware.registry.api.PeerQuery;
import org.j7m.middleware.registry.api.PeerGraphRegistry;
import org.j7m.middleware.registry.api.RegistryClient;
import org.j7m.middleware.registry.api.RegistryDiagnostics;
import org.j7m.middleware.registry.api.RegistryEndpoint;
import org.j7m.middleware.registry.api.RegistryPeerStatus;
import org.j7m.middleware.registry.api.RegistryPeerState;
import org.j7m.middleware.registry.api.RegistryTrustStatus;
import org.j7m.middleware.registry.api.RegistryQuery;
import org.j7m.middleware.registry.api.RegistryRecord;
import org.j7m.middleware.registry.api.RegistryRecordVerifier;
import org.j7m.middleware.registry.internal.RegistryRecordTrust;
import org.j7m.middleware.security.api.AuditEvent;
import org.j7m.middleware.security.api.SecurityAuditSink;

/**
 * Thread-safe in-memory registry implementation for Phase 1.
 *
 * <p>The client stores immutable records in a concurrent map and filters
 * expired or inactive records on every lookup/list operation. It owns no
 * background thread; tests can use a custom {@link Clock} for deterministic
 * expiration behavior.</p>
 */
public final class EmbeddedRegistryClient implements RegistryClient, PeerGraphRegistry, RegistryDiagnostics {
    private final ConcurrentMap<String, RegistryRecord> records;
    private final ConcurrentMap<String, RegistryPeerStatus> peers;
    private final Clock clock;
    private final RegistryRecordTrust trust;
    private final SecurityAuditSink auditSink;
    private final int maxRecords;
    private final AtomicLong trustRejected;
    private final AtomicLong trustDowngraded;
    private volatile boolean closed;

    /**
     * Creates an embedded registry using the system clock.
     */
    public EmbeddedRegistryClient() {
        this(Clock.systemUTC());
    }

    /**
     * Creates an embedded registry using a supplied clock.
     *
     * @param clock registry clock
     */
    public EmbeddedRegistryClient(final Clock clock) {
        this(new EmbeddedRegistryStore(), clock);
    }

    /**
     * Creates an embedded registry client using a shared store and clock.
     *
     * @param store shared store
     * @param clock registry clock
     */
    public EmbeddedRegistryClient(final EmbeddedRegistryStore store, final Clock clock) {
        this(store, clock, null, false, null);
    }

    /**
     * Creates an embedded registry with optional record trust enforcement.
     *
     * @param store shared store
     * @param clock registry clock
     * @param verifier record verifier
     * @param strictTrust true to reject invalid or unsigned records
     * @param auditSink optional audit sink for suspicious records
     */
    public EmbeddedRegistryClient(
            final EmbeddedRegistryStore store,
            final Clock clock,
            final RegistryRecordVerifier verifier,
            final boolean strictTrust,
            final SecurityAuditSink auditSink) {
        EmbeddedRegistryStore safeStore = store == null ? new EmbeddedRegistryStore() : store;
        this.records = safeStore.records();
        this.peers = safeStore.peers();
        this.maxRecords = safeStore.maxRecords();
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.auditSink = auditSink;
        this.trustRejected = new AtomicLong();
        this.trustDowngraded = new AtomicLong();
        this.trust = new RegistryRecordTrust(verifier, strictTrust, this.clock, safeStore.replay(), this.trustRejected, this.trustDowngraded);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bind(final RegistryRecord record) {
        this.ensureOpen();
        if (record == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "registry record is required");
        }
        long nowMillis = this.clock.millis();
        if (!record.activeAt(nowMillis)) {
            throw MiddlewareException.of(MiddlewareErrorCode.LEASE_EXPIRED, "registry record lease is expired");
        }
        RegistryRecord trusted = RegistryRecordTrust.localTrustedPublication(record);
        synchronized (this.records) {
            this.records.put(this.key(record.ref()), trusted);
            this.trimRecords();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unbind(final ObjectRef ref) {
        this.ensureOpen();
        if (ref != null) {
            this.records.remove(this.key(ref));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<RegistryRecord> lookup(final ObjectRef ref) {
        this.ensureOpen();
        if (ref == null) {
            return Optional.empty();
        }
        RegistryRecord record = this.records.get(this.key(ref));
        long nowMillis = this.clock.millis();
        if (record == null || !record.activeAt(nowMillis) || !this.returnable(record)) {
            return Optional.empty();
        }
        return Optional.of(record);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<RegistryRecord> list(final RegistryQuery query) {
        this.ensureOpen();
        RegistryQuery safeQuery = query == null
                ? new RegistryQuery("", List.of(), java.util.Map.of(), "default", this.clock.millis(), 1024)
                : query;
        long nowMillis = safeQuery.nowMillis() == 0L ? this.clock.millis() : safeQuery.nowMillis();
        List<RegistryRecord> matches = new ArrayList<>();
        for (RegistryRecord record : this.records.values()) {
            if (matches.size() >= safeQuery.limit()) {
                break;
            }
            if (this.matches(record, safeQuery, nowMillis) && this.returnable(record)) {
                matches.add(record);
            }
        }
        matches.sort(Comparator.comparing((RegistryRecord record) -> record.ref().runtimeId()).thenComparing(record -> record.ref().objectId()));
        return List.copyOf(matches);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void heartbeat(final ObjectRef ref, final long leaseUntil) {
        this.ensureOpen();
        if (ref == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "heartbeat reference is required");
        }
        String key = this.key(ref);
        RegistryRecord updated = this.records.computeIfPresent(key, (ignored, existing) -> existing.heartbeat(this.clock.millis(), leaseUntil));
        if (updated == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "registry record not found for heartbeat");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<RegistryPeerStatus> peers(final PeerQuery query) {
        this.ensureOpen();
        PeerQuery safeQuery = query == null ? new PeerQuery("default") : query;
        List<RegistryPeerStatus> matches = new ArrayList<>();
        for (RegistryPeerStatus status : this.peers.values()) {
            boolean contextMatches = status.knownContexts().isEmpty() || status.knownContexts().contains(safeQuery.registryContext());
            boolean stateMatches = safeQuery.includeDead() || status.alive();
            if (contextMatches && stateMatches) {
                matches.add(status);
            }
            if (matches.size() >= safeQuery.limit()) {
                break;
            }
        }
        if (matches.isEmpty()) {
            matches.add(new RegistryPeerStatus("embedded", true, RegistryPeerState.ALIVE, false,
                    org.j7m.middleware.registry.api.RegistryPeerQuality.FAST, true, RegistryTrustStatus.TRUSTED,
                    this.clock.millis(), 0L, 0.0D, 1.0D,
                    List.of(new RegistryEndpoint("embedded", "embedded://local", Map.of())),
                    List.of(1), List.of(safeQuery.registryContext()), "", this.clock.millis(), Map.of("phase", "2")));
        }
        matches.sort(Comparator.comparing(RegistryPeerStatus::peerId));
        return List.copyOf(matches);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePeer(final RegistryPeerStatus status) {
        this.ensureOpen();
        if (status != null) {
            synchronized (this.peers) {
                this.peers.put(status.peerId(), status);
                this.trimPeers();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        this.closed = true;
    }

    /**
     * Returns the number of active records at the registry clock's current time.
     *
     * @return active record count
     */
    public int activeRecordCount() {
        long nowMillis = this.clock.millis();
        int count = 0;
        for (RegistryRecord record : this.records.values()) {
            if (record.activeAt(nowMillis)) {
                count++;
            }
        }
        return count;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DiscoveryStatistics discoveryStatistics() {
        return new DiscoveryStatistics(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                Map.of("registryTrustRejected", this.trustRejected.get(), "registryTrustDowngraded", this.trustDowngraded.get()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<DiscoveryEvent> recentDiscoveryEvents() {
        return List.of(new DiscoveryEvent(this.clock.millis(), DiscoverySource.LOCAL, "embedded registry active", Map.of()));
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
        return false;
    }

    private boolean matches(final RegistryRecord record, final RegistryQuery query, final long nowMillis) {
        if (!record.activeAt(nowMillis) || record.state() != ObjectState.EXECUTED) {
            return false;
        }
        if (!query.serviceName().isBlank() && !record.ref().serviceName().equals(query.serviceName())) {
            return false;
        }
        if (!record.ref().registryContext().equals(query.registryContext())) {
            return false;
        }
        if (!record.ref().interfaces().containsAll(query.interfaces())) {
            return false;
        }
        for (java.util.Map.Entry<String, String> entry : query.requiredParams().entrySet()) {
            String value = record.parameters().get(entry.getKey());
            if (!entry.getValue().equals(value)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Applies this registry's trust policy to a record received through an
     * external discovery boundary.
     *
     * @param record received record
     * @param expectedContext expected registry context
     * @return admitted record with trust metadata
     */
    public RegistryRecord admitReceivedRecord(final RegistryRecord record, final String expectedContext) {
        this.ensureOpen();
        return this.trustedRecord(record, expectedContext);
    }

    private RegistryRecord trustedRecord(final RegistryRecord record, final String expectedContext) {
        RegistryRecord admitted;
        try {
            admitted = this.trust.admit(record, expectedContext);
        } catch (MiddlewareException exception) {
            this.auditTrust(record, "rejected", "untrusted");
            throw exception;
        }
        String trustStatus = admitted.authMetadata().get("trustStatus");
        if ("trusted".equals(trustStatus)) {
            this.auditTrust(record, "accepted", "trusted");
        } else {
            this.auditTrust(record, "downgraded", admitted.authMetadata().getOrDefault("trustReason", "untrusted"));
        }
        return admitted;
    }

    private boolean returnable(final RegistryRecord record) {
        return RegistryRecordTrust.candidateEligible(record);
    }

    private void auditTrust(final RegistryRecord record, final String decision, final String reason) {
        if (this.auditSink == null) {
            return;
        }
        this.auditSink.record(new AuditEvent(this.clock.instant(), record.ref().runtimeId(), record.ref().runtimeId(),
                "registry-record-" + decision, record.ref().objectId(), decision, "registry-trust", "", "", record.ref().endpoint(),
                reason, record.ref().transport(), record.ref().registryContext(), Map.of("serviceName", record.ref().serviceName())));
    }

    private String key(final ObjectRef ref) {
        return ref.registryContext() + "|" + ref.runtimeId() + "|" + ref.objectId() + "|" + ref.generation();
    }

    private void trimRecords() {
        if (this.records.size() <= this.maxRecords) {
            return;
        }
        List<String> keys = new ArrayList<>(this.records.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            this.records.remove(key);
            if (this.records.size() <= this.maxRecords) {
                return;
            }
        }
    }

    private void trimPeers() {
        if (this.peers.size() <= this.maxRecords) {
            return;
        }
        List<String> keys = new ArrayList<>(this.peers.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            this.peers.remove(key);
            if (this.peers.size() <= this.maxRecords) {
                return;
            }
        }
    }

    private void ensureOpen() {
        if (this.closed) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "registry is closed");
        }
    }

}
