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

package org.j7m.middleware.security.defaults;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.security.api.AuditEvent;
import org.j7m.middleware.security.api.SecurityAuditSink;

/**
 * Bounded in-memory tamper-evident audit sink.
 *
 * <p>The sink is thread-safe. It records redacted audit events in a SHA-256
 * hash chain with sequence numbers and periodic checkpoint hashes. The bounded
 * implementation is intended for tests, demos, and local evidence capture; a
 * production deployment should attach a {@link WormAuditSink} implementation
 * for external immutable storage.</p>
 */
public final class TamperEvidentAuditSink implements SecurityAuditSink {
    private static final String ZERO_HASH = "0";

    private final int capacity;
    private final int checkpointInterval;
    private final Object lock;
    private final Deque<TamperEvidentAuditRecord> records;
    private final AtomicLong sequence;
    private String lastHash;

    /**
     * Creates a bounded audit sink.
     *
     * @param capacity retained record capacity
     * @param checkpointInterval checkpoint interval
     */
    public TamperEvidentAuditSink(final int capacity, final int checkpointInterval) {
        this.capacity = Math.max(1, capacity);
        this.checkpointInterval = Math.max(1, checkpointInterval);
        this.lock = new Object();
        this.records = new ArrayDeque<>(this.capacity);
        this.sequence = new AtomicLong();
        this.lastHash = ZERO_HASH;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void record(final AuditEvent event) {
        if (event == null) {
            return;
        }
        synchronized (this.lock) {
            long nextSequence = this.sequence.incrementAndGet();
            String previous = this.lastHash;
            String current = hash(nextSequence, event, previous);
            String checkpoint = nextSequence % this.checkpointInterval == 0L ? current : "";
            TamperEvidentAuditRecord record = new TamperEvidentAuditRecord(nextSequence, event, previous, current, checkpoint);
            if (this.records.size() >= this.capacity) {
                this.records.removeFirst();
            }
            this.records.addLast(record);
            this.lastHash = current;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<AuditEvent> recentEvents() {
        synchronized (this.lock) {
            return this.records.stream().map(TamperEvidentAuditRecord::event).toList();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long eventCount() {
        return this.sequence.get();
    }

    /**
     * Returns recent hash-chain records.
     *
     * @return immutable record list
     */
    public List<TamperEvidentAuditRecord> chain() {
        synchronized (this.lock) {
            return List.copyOf(this.records);
        }
    }

    /**
     * Verifies the retained chain.
     *
     * @return verification result
     */
    public AuditChainVerificationResult verify() {
        return verify(this.chain());
    }

    /**
     * Exports retained records as newline-delimited text.
     *
     * @return safe export text
     */
    public String exportChain() {
        StringBuilder builder = new StringBuilder(256);
        for (TamperEvidentAuditRecord record : this.chain()) {
            builder.append(record.sequence()).append(',')
                    .append(record.previousHash()).append(',')
                    .append(record.currentHash()).append(',')
                    .append(record.checkpointHash()).append(',')
                    .append(record.event().timestamp()).append(',')
                    .append(record.event().action()).append(',')
                    .append(record.event().decision()).append('\n');
        }
        return builder.toString();
    }

    /**
     * Verifies a supplied chain snapshot.
     *
     * @param chain chain records
     * @return verification result
     */
    public static AuditChainVerificationResult verify(final List<TamperEvidentAuditRecord> chain) {
        List<TamperEvidentAuditRecord> safeChain = chain == null ? List.of() : List.copyOf(chain);
        String previous = null;
        long verified = 0L;
        long expectedSequence = -1L;
        for (TamperEvidentAuditRecord record : safeChain) {
            if (record == null || record.event() == null) {
                return new AuditChainVerificationResult(false, verified, "missing audit record");
            }
            if (expectedSequence >= 0L && record.sequence() != expectedSequence) {
                return new AuditChainVerificationResult(false, verified, "missing audit sequence");
            }
            if (previous != null && !record.previousHash().equals(previous)) {
                return new AuditChainVerificationResult(false, verified, "previous hash mismatch");
            }
            String expected = hash(record.sequence(), record.event(), record.previousHash());
            if (!expected.equals(record.currentHash())) {
                return new AuditChainVerificationResult(false, verified, "current hash mismatch");
            }
            previous = record.currentHash();
            expectedSequence = record.sequence() + 1L;
            verified++;
        }
        return new AuditChainVerificationResult(true, verified, "chain verified");
    }

    private static String hash(final long sequence, final AuditEvent event, final String previousHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Long.toString(sequence).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(previousHash.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(canonical(event).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    private static String canonical(final AuditEvent event) {
        Instant timestamp = event.timestamp();
        return timestamp + "|" + event.runtimeId() + "|" + event.principal() + "|" + event.action()
                + "|" + event.resource() + "|" + event.decision() + "|" + event.policyId()
                + "|" + event.correlationId() + "|" + event.invocationId() + "|" + event.remoteEndpoint()
                + "|" + event.reason() + "|" + event.transportId() + "|" + event.registryContext()
                + "|" + event.metadata();
    }
}
