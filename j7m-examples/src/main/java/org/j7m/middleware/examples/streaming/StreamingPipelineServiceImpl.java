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

package org.j7m.middleware.examples.streaming;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;

/**
 * Thread-safe in-memory implementation of {@link StreamingPipelineService}.
 */
public final class StreamingPipelineServiceImpl implements StreamingPipelineService {
    private final AtomicLong ids;
    private final Map<String, SessionRecord> sessions;

    /**
     * Creates an empty streaming-pipeline service.
     */
    public StreamingPipelineServiceImpl() {
        this.ids = new AtomicLong();
        this.sessions = new LinkedHashMap<>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized StreamSession openSession(final StreamOptions options) {
        StreamOptions actualOptions = options == null ? StreamOptions.defaults() : options;
        StreamSession session = new StreamSession("stream-" + this.ids.incrementAndGet());
        SessionRecord record = new SessionRecord(actualOptions);
        record.serverItems.add("server-item-1");
        record.serverItems.add("server-item-2");
        record.serverItems.add("server-item-3");
        this.sessions.put(session.sessionId(), record);
        return session;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized BatchResult submitBatch(final StreamSession session, final List<String> items) {
        SessionRecord record = this.record(session);
        if (record.cancelled) {
            return new BatchResult(0, items == null ? 0 : items.size(), false);
        }
        int accepted = 0;
        int rejected = 0;
        for (String item : items == null ? List.<String>of() : items) {
            if (!this.acceptable(item, record.options)) {
                rejected++;
                continue;
            }
            if (record.clientItems.size() >= record.options.queueLimit()) {
                record.backpressureSignals++;
                rejected++;
                continue;
            }
            record.clientItems.add(item);
            accepted++;
        }
        record.submitted += accepted;
        record.rejected += rejected;
        return new BatchResult(accepted, rejected, record.clientItems.size() >= record.options.queueLimit());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized List<String> readBatch(final StreamSession session, final int maxItems) {
        if (maxItems <= 0) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "maxItems must be positive");
        }
        SessionRecord record = this.record(session);
        List<String> values = new ArrayList<>();
        while (values.size() < maxItems && !record.serverItems.isEmpty()) {
            values.add(record.serverItems.remove());
        }
        record.returned += values.size();
        return List.copyOf(values);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized StreamExchangeResult exchange(
            final StreamSession session,
            final List<String> clientItems,
            final int maxServerItems) {
        BatchResult batch = this.submitBatch(session, clientItems);
        List<String> serverItems = this.readBatch(session, maxServerItems);
        return new StreamExchangeResult(batch.accepted(), serverItems, batch.backpressure());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized boolean cancel(final StreamSession session) {
        SessionRecord record = this.record(session);
        if (record.cancelled) {
            return false;
        }
        record.cancelled = true;
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized StreamMetrics metrics(final StreamSession session) {
        SessionRecord record = this.record(session);
        return new StreamMetrics(record.submitted, record.returned, record.rejected, record.cancelled,
                record.backpressureSignals);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized String adminStatus() {
        long cancelled = this.sessions.values().stream().filter(record -> record.cancelled).count();
        return "sessions=" + this.sessions.size() + ", cancelled=" + cancelled;
    }

    private boolean acceptable(final String item, final StreamOptions options) {
        return item != null && item.getBytes(StandardCharsets.UTF_8).length <= options.maxItemBytes();
    }

    private SessionRecord record(final StreamSession session) {
        if (session == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "stream session is required");
        }
        SessionRecord record = this.sessions.get(session.sessionId());
        if (record == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "stream session not found");
        }
        return record;
    }

    private static final class SessionRecord {
        private final StreamOptions options;
        private final Queue<String> serverItems;
        private final Queue<String> clientItems;
        private long submitted;
        private long returned;
        private long rejected;
        private long backpressureSignals;
        private boolean cancelled;

        private SessionRecord(final StreamOptions options) {
            this.options = options;
            this.serverItems = new ArrayDeque<>();
            this.clientItems = new ArrayDeque<>();
        }
    }
}
