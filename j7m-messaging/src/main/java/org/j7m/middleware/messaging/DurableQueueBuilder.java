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

package org.j7m.middleware.messaging;

import java.nio.file.Path;
import java.time.Clock;
import org.j7m.middleware.api.durablelog.LogCorruptionAction;
import org.j7m.middleware.api.durablelog.RecoveryOptions;
import org.j7m.middleware.api.durablelog.DurableLog;
import org.j7m.middleware.api.messaging.InboxOptions;
import org.j7m.middleware.api.messaging.OutboxOptions;
import org.j7m.middleware.api.messaging.QueueOptions;
import org.j7m.middleware.durablelog.DurableLogBuilder;
import org.j7m.middleware.durablelog.FileDurableLog;

/**
 * Builder for {@link LogBackedDurableQueue}.
 *
 * <p>Durable logs created from {@link #directory(Path)} are owned by the
 * queue and closed with it. Durable logs supplied through
 * {@link #durableLog(DurableLog)} are borrowed by default and remain the
 * caller's responsibility unless {@link #ownedDurableLog(DurableLog)} is used.
 * Instances are mutable and not thread-safe.</p>
 */
public final class DurableQueueBuilder {
    private DurableLog durableLog;
    private boolean ownsDurableLog;
    private Path directory;
    private QueueOptions queueOptions = QueueOptions.defaults();
    private OutboxOptions outboxOptions = OutboxOptions.defaults();
    private InboxOptions inboxOptions = InboxOptions.defaults();
    private Clock clock = Clock.systemUTC();

    /**
     * Creates a builder.
     */
    public DurableQueueBuilder() {
    }

    /**
     * Sets an existing durable log.
     *
     * @param value durable log to use
     * @return this builder
     */
    public DurableQueueBuilder durableLog(final DurableLog value) {
        this.durableLog = value;
        this.ownsDurableLog = false;
        return this;
    }

    /**
     * Sets an existing durable log that will be closed by the queue.
     *
     * @param value durable log to use and close
     * @return this builder
     */
    public DurableQueueBuilder ownedDurableLog(final DurableLog value) {
        this.durableLog = value;
        this.ownsDurableLog = true;
        return this;
    }

    /**
     * Sets a directory for a new file durable log.
     *
     * @param value log directory
     * @return this builder
     */
    public DurableQueueBuilder directory(final Path value) {
        this.directory = value;
        return this;
    }

    /**
     * Sets queue options.
     *
     * @param value queue options
     * @return this builder
     */
    public DurableQueueBuilder queueOptions(final QueueOptions value) {
        this.queueOptions = value;
        return this;
    }

    /**
     * Sets outbox options.
     *
     * @param value outbox options
     * @return this builder
     */
    public DurableQueueBuilder outboxOptions(final OutboxOptions value) {
        this.outboxOptions = value;
        return this;
    }

    /**
     * Sets inbox options.
     *
     * @param value inbox options
     * @return this builder
     */
    public DurableQueueBuilder inboxOptions(final InboxOptions value) {
        this.inboxOptions = value;
        return this;
    }

    /**
     * Sets the clock.
     *
     * @param value clock used by queue time semantics
     * @return this builder
     */
    public DurableQueueBuilder clock(final Clock value) {
        this.clock = value;
        return this;
    }

    /**
     * Builds and recovers the queue service.
     *
     * @return recovered queue service
     * @throws IllegalArgumentException when neither directory nor durable log is configured
     */
    public LogBackedDurableQueue build() {
        DurableLog selectedLog = this.durableLog;
        boolean selectedOwnership = this.ownsDurableLog;
        if (selectedLog == null) {
            if (this.directory == null) {
                throw new IllegalArgumentException("directory or durableLog is required");
            }
            long eventPayloadBytes = ownedEventPayloadLimit(this.queueOptions);
            selectedLog = new DurableLogBuilder().directory(this.directory).clock(this.clock)
                    .maxPayloadBytes(eventPayloadBytes)
                    .recoveryOptions(RecoveryOptions.bounded(LogCorruptionAction.FAIL, true,
                            RecoveryOptions.DEFAULT_MAX_SEGMENTS_SCANNED,
                            LogBackedDurableQueue.recoveryRecordScanLimit()))
                    .build();
            selectedOwnership = true;
        }
        long eventPayloadBytes = eventPayloadLimit(selectedLog, this.queueOptions);
        DurableQueueConfig config;
        try {
            config = new DurableQueueConfig(selectedLog, this.queueOptions, this.outboxOptions, this.inboxOptions,
                    this.clock, selectedOwnership, eventPayloadBytes);
        } catch (RuntimeException exception) {
            if (selectedOwnership) {
                try {
                    selectedLog.close();
                } catch (RuntimeException closeException) {
                    exception.addSuppressed(closeException);
                }
            }
            throw exception;
        }
        return new LogBackedDurableQueue(config);
    }

    private static long eventPayloadLimit(final DurableLog durableLog, final QueueOptions queueOptions) {
        if (durableLog instanceof FileDurableLog fileDurableLog) {
            return Math.min(fileDurableLog.config().maxPayloadBytes(), (long) QueueEventCodec.maxEncodedEventBytes());
        }
        return ownedEventPayloadLimit(queueOptions);
    }

    private static long ownedEventPayloadLimit(final QueueOptions queueOptions) {
        QueueOptions effective = queueOptions == null ? QueueOptions.defaults() : queueOptions;
        long queuePayloadBytes = Math.multiplyExact((long) effective.maxPayloadBytes(), 2L);
        return Math.min(Math.max(4L * 1024L * 1024L, queuePayloadBytes), (long) QueueEventCodec.maxEncodedEventBytes());
    }
}
