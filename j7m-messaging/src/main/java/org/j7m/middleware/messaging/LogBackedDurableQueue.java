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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.CRC32;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.CursorOptions;
import org.j7m.middleware.api.durablelog.DurableLog;
import org.j7m.middleware.api.durablelog.DurableLogException;
import org.j7m.middleware.api.durablelog.LogCursor;
import org.j7m.middleware.api.durablelog.LogCorruptionAction;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.j7m.middleware.api.durablelog.LogRecord;
import org.j7m.middleware.api.durablelog.LogRecordHeader;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.api.durablelog.RecoveryOptions;
import org.j7m.middleware.api.messaging.AckOptions;
import org.j7m.middleware.api.messaging.AckResult;
import org.j7m.middleware.api.messaging.ConsumerGroupId;
import org.j7m.middleware.api.messaging.ConsumerId;
import org.j7m.middleware.api.messaging.DurableQueue;
import org.j7m.middleware.api.messaging.EnqueueOptions;
import org.j7m.middleware.api.messaging.EnqueueResult;
import org.j7m.middleware.api.messaging.Inbox;
import org.j7m.middleware.api.messaging.InboxDuplicateDecision;
import org.j7m.middleware.api.messaging.InboxEntry;
import org.j7m.middleware.api.messaging.InboxOptions;
import org.j7m.middleware.api.messaging.InboxStatus;
import org.j7m.middleware.api.messaging.LeaseBatch;
import org.j7m.middleware.api.messaging.LeaseId;
import org.j7m.middleware.api.messaging.LeaseOptions;
import org.j7m.middleware.api.messaging.MessageEnvelope;
import org.j7m.middleware.api.messaging.MessageHeader;
import org.j7m.middleware.api.messaging.MessageId;
import org.j7m.middleware.api.messaging.MessageLease;
import org.j7m.middleware.api.messaging.MessagePriority;
import org.j7m.middleware.api.messaging.MessageState;
import org.j7m.middleware.api.messaging.MessagingException;
import org.j7m.middleware.api.messaging.MessagingRecoveryReport;
import org.j7m.middleware.api.messaging.NackOptions;
import org.j7m.middleware.api.messaging.NackResult;
import org.j7m.middleware.api.messaging.Outbox;
import org.j7m.middleware.api.messaging.OutboxEntry;
import org.j7m.middleware.api.messaging.OutboxStatus;
import org.j7m.middleware.api.messaging.QueueMessage;
import org.j7m.middleware.api.messaging.QueueMetricsSnapshot;
import org.j7m.middleware.api.messaging.QueueName;
import org.j7m.middleware.api.messaging.QueueOptions;
import org.j7m.middleware.api.messaging.QueuePartitionId;
import org.j7m.middleware.api.messaging.QueuePartitionStatus;
import org.j7m.middleware.api.messaging.QueueStatus;
import org.j7m.middleware.api.reliability.DedupeKey;
import org.j7m.middleware.api.reliability.IdempotencyKey;
import org.j7m.middleware.api.reliability.OrderingKey;

/**
 * Local durable-log-backed queue, outbox, and inbox implementation.
 */
public final class LogBackedDurableQueue implements DurableQueue, Outbox, Inbox {
    private static final LogStreamId EVENT_STREAM = new LogStreamId("j7m.messaging.events");
    private static final int TERMINAL_SUMMARY_LIMIT = 4096;
    private static final int DUPLICATE_DECISION_LIMIT = 4096;
    private static final int COMPLETED_OUTBOX_LIMIT = 4096;
    private static final int MAX_RECOVERY_TAIL_EVENTS = 256;
    private static final int CHECKPOINT_LOOKUP_RECORD_LIMIT = 16_384;
    private static final int MAX_INBOX_RESULT_METADATA_BYTES = 16 * 1024;
    private static final String EVENT_METADATA_KEY = "j7m-messaging-event";
    private static final String CHECKPOINT_EVENT_TYPE = "CHECKPOINT";

    private final DurableQueueConfig config;
    private final Map<QueueName, QueueState> queues;
    private final Map<String, EnqueueResult> idempotencyResults;
    private final Map<String, EnqueueResult> dedupeResults;
    private final Map<MessageId, OutboxRecord> outbox;
    private final Map<String, InboxEntry> inbox;
    private final MessagingMetrics metrics;
    private final MessageIdGenerator idGenerator;
    private boolean closed;
    private boolean recovering;
    private boolean restoringCheckpoint;
    private long sequence;
    private long durableEventCount;
    private long eventsSinceCheckpoint;
    private String lastError;
    private MessagingRecoveryReport recoveryReport;

    /**
     * Opens and recovers a log-backed queue.
     *
     * @param config queue configuration
     * @throws MessagingException when durable recovery fails
     */
    public LogBackedDurableQueue(final DurableQueueConfig config) {
        this.config = config;
        this.queues = new LinkedHashMap<>();
        this.idempotencyResults = new LinkedHashMap<>();
        this.dedupeResults = new LinkedHashMap<>();
        this.outbox = new LinkedHashMap<>();
        this.inbox = new LinkedHashMap<>();
        this.metrics = new MessagingMetrics();
        this.idGenerator = new MessageIdGenerator(0L, 0L);
        this.closed = false;
        this.recovering = false;
        this.restoringCheckpoint = false;
        this.sequence = 0L;
        this.durableEventCount = 0L;
        this.eventsSinceCheckpoint = 0L;
        this.lastError = "";
        this.recoveryReport = this.recover();
    }

    /**
     * Returns the latest recovery report.
     *
     * @return latest successful or failed recovery report
     */
    public synchronized MessagingRecoveryReport recoveryReport() {
        return this.recoveryReport;
    }

    static long recoveryRecordScanLimit() {
        return (long) CHECKPOINT_LOOKUP_RECORD_LIMIT + (long) MAX_RECOVERY_TAIL_EVENTS + 1L;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized EnqueueResult enqueue(final QueueName queue, final QueueMessage message, final EnqueueOptions options) {
        this.ensureOpen();
        QueueName safeQueue = this.requireQueue(queue);
        QueueMessage safeMessage = message == null ? new QueueMessage(MessageHeader.empty(), new byte[0]) : message;
        EnqueueOptions safeOptions = options == null ? EnqueueOptions.defaults() : options;
        QueueOptions queueOptions = this.config.queueOptions();
        QueueState state = this.queueState(safeQueue);
        if (safeMessage.payloadLength() > queueOptions.maxPayloadBytes()) {
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "message payload limit exceeded", null);
        }
        if (state.liveMessageCount() >= queueOptions.maxQueuedMessages()) {
            throw this.failure(MiddlewareErrorCode.OVERLOADED, "queue depth limit exceeded", null);
        }
        String idempotencyKey = safeOptions.idempotencyKey() == null ? "" : safeOptions.idempotencyKey().value();
        String dedupeKey = safeOptions.dedupeKey() == null ? "" : safeOptions.dedupeKey().value();
        EnqueueResult previous = this.previousDuplicate(idempotencyKey, dedupeKey);
        if (previous != null) {
            this.metrics.duplicateCount++;
            return new EnqueueResult(previous.queue(), previous.partitionId(), previous.messageId(), true, previous.sequence());
        }
        this.ensureDomainEventCanAppend();
        MessageId messageId = this.idGenerator.nextMessageId();
        QueuePartitionId partitionId = this.partitionFor(safeOptions, messageId);
        long nextSequence = ++this.sequence;
        Instant now = this.config.clock().instant();
        MessageState initialState = safeOptions.notBefore().isAfter(now) ? MessageState.DELAYED : MessageState.AVAILABLE;
        Map<String, String> fields = this.messageFields("MESSAGE_ENQUEUED", safeQueue, messageId);
        fields.put("partition", Integer.toString(partitionId.value()));
        fields.put("sequence", Long.toString(nextSequence));
        fields.put("priority", Integer.toString(safeOptions.priority().value()));
        fields.put("notBefore", Long.toString(safeOptions.notBefore().toEpochMilli()));
        fields.put("headers", QueueEventCodec.encodeStringMap(safeMessage.header().metadata()));
        fields.put("payload", Base64.getEncoder().encodeToString(safeMessage.payload()));
        fields.put("idempotencyKey", idempotencyKey);
        fields.put("dedupeKey", dedupeKey);
        this.appendDomainEvent(new QueueEvent("MESSAGE_ENQUEUED", fields));
        MessageRecord record = new MessageRecord(safeQueue, partitionId, messageId, safeMessage, safeOptions.priority(),
                nextSequence, initialState, safeOptions.notBefore(), idempotencyKey, dedupeKey);
        state.messages.put(messageId, record);
        EnqueueResult result = new EnqueueResult(safeQueue, partitionId, messageId, false, nextSequence);
        this.rememberDuplicateKeys(record, result);
        this.metrics.enqueueCount++;
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized LeaseBatch lease(final QueueName queue, final ConsumerId consumer, final LeaseOptions options) {
        this.ensureOpen();
        QueueName safeQueue = this.requireQueue(queue);
        if (consumer == null) {
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "consumer is required", null);
        }
        LeaseOptions safeOptions = options == null ? LeaseOptions.defaults() : options;
        QueueOptions queueOptions = this.config.queueOptions();
        if (safeOptions.maxMessages() > queueOptions.maxLeaseBatchSize()) {
            throw this.failure(MiddlewareErrorCode.OVERLOADED, "lease batch size limit exceeded", null);
        }
        int batchSize = Math.min(safeOptions.maxMessages(), queueOptions.maxLeaseBatchSize());
        QueueState state = this.queueState(safeQueue);
        this.expireLeases(state);
        if (state.activeLeaseCount() >= queueOptions.maxActiveLeases()) {
            throw this.failure(MiddlewareErrorCode.OVERLOADED, "active lease limit exceeded", null);
        }
        int availableLeaseSlots = Math.max(0, queueOptions.maxActiveLeases() - state.activeLeaseCount());
        int selectedLimit = Math.min(batchSize, availableLeaseSlots);
        Instant now = this.config.clock().instant();
        List<MessageRecord> candidates = state.messages.values().stream()
                .filter(record -> record.isAvailable(now))
                .sorted(Comparator.comparing((MessageRecord record) -> record.partitionId.value())
                        .thenComparing((MessageRecord record) -> record.priority.value(), Comparator.reverseOrder())
                        .thenComparingLong(record -> record.sequence))
                .limit(selectedLimit)
                .toList();
        List<MessageLease> leases = new ArrayList<>(candidates.size());
        for (MessageRecord record : candidates) {
            this.ensureDomainEventCanAppend();
            LeaseId leaseId = this.idGenerator.nextLeaseId();
            Instant leaseUntil = now.plus(safeOptions.visibilityTimeout().duration());
            Map<String, String> fields = this.messageFields("MESSAGE_LEASED", safeQueue, record.messageId);
            fields.put("leaseId", leaseId.value());
            fields.put("consumerId", consumer.value());
            fields.put("leaseUntil", Long.toString(leaseUntil.toEpochMilli()));
            fields.put("consumerGroup", safeOptions.consumerGroupId() == null ? "" : safeOptions.consumerGroupId().value());
            this.appendDomainEvent(new QueueEvent("MESSAGE_LEASED", fields));
            record.state = MessageState.LEASED;
            record.leaseId = leaseId;
            record.consumerId = consumer;
            record.leaseUntil = leaseUntil;
            if (safeOptions.consumerGroupId() != null) {
                state.consumerGroups.computeIfAbsent(safeOptions.consumerGroupId(), ConsumerGroupState::new)
                        .deliveredSequences.put(record.messageId, Long.valueOf(record.sequence));
            }
            this.metrics.leaseCount++;
            leases.add(new MessageLease(record.envelope(), leaseId, consumer, leaseUntil));
        }
        return new LeaseBatch(leases);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized AckResult ack(final QueueName queue, final MessageId messageId, final AckOptions options) {
        this.ensureOpen();
        MessageRecord record = this.requireMessage(queue, messageId);
        AckOptions safeOptions = options == null ? AckOptions.defaults() : options;
        this.requireLiveLease(record, safeOptions.leaseId());
        Map<String, String> fields = this.messageFields("MESSAGE_ACKED", queue, messageId);
        fields.put("leaseId", record.leaseId.value());
        fields.put("consumerId", record.consumerId.value());
        this.appendDomainEvent(new QueueEvent("MESSAGE_ACKED", fields));
        record.state = MessageState.ACKED;
        record.leaseId = null;
        record.consumerId = null;
        this.queueState(record.queue).moveToTerminal(record);
        this.metrics.ackCount++;
        return new AckResult(messageId, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized NackResult nack(final QueueName queue, final MessageId messageId, final NackOptions options) {
        this.ensureOpen();
        MessageRecord record = this.requireMessage(queue, messageId);
        NackOptions safeOptions = options == null ? NackOptions.defaults() : options;
        this.requireLiveLease(record, safeOptions.leaseId());
        return this.nackRecord(queue, record, safeOptions.notBefore(), safeOptions.reason().isEmpty() ? "nack" : safeOptions.reason());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized QueueStatus status(final QueueName queue) {
        QueueName safeQueue = this.requireQueue(queue);
        QueueState state = this.queueState(safeQueue);
        this.expireLeases(state);
        List<QueuePartitionStatus> partitions = this.partitionStatuses(state);
        long available = partitions.stream().mapToLong(QueuePartitionStatus::available).sum();
        long leased = partitions.stream().mapToLong(QueuePartitionStatus::leased).sum();
        long delayed = partitions.stream().mapToLong(QueuePartitionStatus::delayed).sum();
        long dead = partitions.stream().mapToLong(QueuePartitionStatus::deadLettered).sum();
        return new QueueStatus(safeQueue, state.liveMessageCount(), available, leased, delayed, dead,
                partitions, this.metrics.snapshot(), this.lastError);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized OutboxEntry record(final QueueName targetQueue, final QueueMessage message, final EnqueueOptions options) {
        this.ensureOpen();
        QueueName safeTargetQueue = this.requireQueue(targetQueue);
        EnqueueOptions safeOptions = options == null ? EnqueueOptions.defaults() : options;
        QueueMessage safeMessage = message == null ? new QueueMessage(MessageHeader.empty(), new byte[0]) : message;
        if (safeMessage.payloadLength() > this.config.queueOptions().maxPayloadBytes()) {
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "outbox payload limit exceeded", null);
        }
        OutboxRecord duplicate = this.findOutboxDuplicate(safeTargetQueue, safeOptions);
        if (duplicate != null) {
            this.metrics.duplicateCount++;
            return duplicate.entry();
        }
        if (this.pendingOutboxCount() >= this.config.outboxOptions().maxPendingEntries()) {
            throw this.failure(MiddlewareErrorCode.OVERLOADED, "outbox pending limit exceeded", null);
        }
        this.ensureDomainEventCanAppend();
        MessageId entryId = this.idGenerator.nextMessageId();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("entryId", entryId.value());
        fields.put("queue", safeTargetQueue.value());
        fields.put("payload", Base64.getEncoder().encodeToString(safeMessage.payload()));
        fields.put("headers", QueueEventCodec.encodeStringMap(safeMessage.header().metadata()));
        fields.put("priority", Integer.toString(safeOptions.priority().value()));
        fields.put("partition", safeOptions.partitionId() == null ? "" : Integer.toString(safeOptions.partitionId().value()));
        fields.put("orderingKey", safeOptions.orderingKey() == null ? "" : safeOptions.orderingKey().value());
        fields.put("idempotencyKey", safeOptions.idempotencyKey() == null ? "" : safeOptions.idempotencyKey().value());
        fields.put("dedupeKey", safeOptions.dedupeKey() == null ? "" : safeOptions.dedupeKey().value());
        fields.put("notBefore", Long.toString(safeOptions.notBefore().toEpochMilli()));
        fields.put("createdAt", Long.toString(this.config.clock().instant().toEpochMilli()));
        this.appendDomainEvent(new QueueEvent("OUTBOX_RECORDED", fields));
        OutboxRecord record = new OutboxRecord(entryId, safeTargetQueue, safeMessage, safeOptions, this.config.clock().instant());
        this.outbox.put(entryId, record);
        this.metrics.outboxRecorded++;
        return record.entry();
    }

    private OutboxRecord findOutboxDuplicate(final QueueName targetQueue, final EnqueueOptions options) {
        IdempotencyKey idempotencyKey = options.idempotencyKey();
        DedupeKey dedupeKey = options.dedupeKey();
        if (idempotencyKey == null && dedupeKey == null) {
            return null;
        }
        for (OutboxRecord record : this.outbox.values()) {
            if (!record.targetQueue.equals(targetQueue)) {
                continue;
            }
            EnqueueOptions existing = record.enqueueOptions;
            if (idempotencyKey != null && existing.idempotencyKey() != null
                    && idempotencyKey.equals(existing.idempotencyKey())) {
                return record;
            }
            if (dedupeKey != null && existing.dedupeKey() != null && dedupeKey.equals(existing.dedupeKey())) {
                return record;
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized int relayPending() {
        this.ensureOpen();
        int published = 0;
        List<OutboxRecord> pending = this.outbox.values().stream()
                .filter(record -> !record.published && !record.failed)
                .toList();
        for (OutboxRecord record : pending) {
            this.ensureDomainEventsCanAppend(2);
            try {
                this.enqueue(record.targetQueue, record.message, record.enqueueOptions);
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("entryId", record.entryId.value());
                this.appendDomainEvent(new QueueEvent("OUTBOX_PUBLISHED", fields));
                record.published = true;
                this.metrics.outboxPublished++;
                published++;
                this.enforceCompletedOutboxLimit();
            } catch (RuntimeException exception) {
                record.attempts++;
                if (record.attempts >= this.config.outboxOptions().maxRelayAttempts()) {
                    record.failed = true;
                    this.metrics.outboxFailed++;
                }
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("entryId", record.entryId.value());
                fields.put("attempts", Integer.toString(record.attempts));
                fields.put("failed", Boolean.toString(record.failed));
                this.appendDomainEvent(new QueueEvent("OUTBOX_FAILED", fields));
                this.enforceCompletedOutboxLimit();
            }
        }
        return published;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized OutboxStatus outboxStatus() {
        long pending = this.pendingOutboxCount();
        long published = this.outbox.values().stream().filter(record -> record.published).count();
        long failed = this.outbox.values().stream().filter(record -> record.failed).count();
        return new OutboxStatus(pending, published, failed, this.metrics.snapshot(), this.lastError);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized InboxEntry record(final DedupeKey dedupeKey, final String resultMetadata, final InboxOptions options) {
        this.ensureOpen();
        if (dedupeKey == null) {
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "dedupe key is required", null);
        }
        InboxOptions safeOptions = options == null ? this.config.inboxOptions() : options;
        InboxEntry previous = this.inbox.get(dedupeKey.value());
        if (previous != null) {
            this.metrics.inboxSuppressed++;
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("dedupeKey", dedupeKey.value());
            fields.put("decision", InboxDuplicateDecision.SUPPRESSED.name());
            this.appendDomainEvent(new QueueEvent("INBOX_DUPLICATE_SUPPRESSED", fields));
            if (safeOptions.returnPreviousDecision()) {
                return new InboxEntry(dedupeKey, InboxDuplicateDecision.PREVIOUS_DECISION, previous.recordedAt(), previous.resultMetadata());
            }
            return new InboxEntry(dedupeKey, InboxDuplicateDecision.SUPPRESSED, this.config.clock().instant(), "");
        }
        if (this.inbox.size() >= safeOptions.maxDedupeEntries()) {
            throw this.failure(MiddlewareErrorCode.OVERLOADED, "inbox dedupe limit exceeded", null);
        }
        String safeResultMetadata = resultMetadata == null ? "" : resultMetadata;
        if (exceedsUtf8Bytes(safeResultMetadata, MAX_INBOX_RESULT_METADATA_BYTES)) {
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "inbox result metadata limit exceeded", null);
        }
        this.ensureDomainEventCanAppend();
        InboxEntry entry = new InboxEntry(dedupeKey, InboxDuplicateDecision.ACCEPTED, this.config.clock().instant(), safeResultMetadata);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("dedupeKey", dedupeKey.value());
        fields.put("result", entry.resultMetadata());
        fields.put("recordedAt", Long.toString(entry.recordedAt().toEpochMilli()));
        this.appendDomainEvent(new QueueEvent("INBOX_RECORDED", fields));
        this.inbox.put(dedupeKey.value(), entry);
        this.metrics.inboxRecorded++;
        return entry;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized InboxStatus inboxStatus() {
        return new InboxStatus(this.inbox.size(), this.metrics.inboxSuppressed, this.metrics.snapshot(), this.lastError);
    }

    /**
     * Returns lightweight health.
     *
     * @return redacted health summary
     */
    public synchronized MessagingHealth health() {
        return new MessagingHealth(!this.closed, this.queues.size(), this.metrics.deadLetterCount, this.lastError);
    }

    /**
     * Replays durable messaging events and rebuilds state.
     *
     * @return recovery report
     * @throws MessagingException when durable-log or semantic recovery fails
     */
    public synchronized MessagingRecoveryReport recover() {
        RecoverySnapshot snapshot = this.snapshotRecoveryState();
        this.queues.clear();
        this.idempotencyResults.clear();
        this.dedupeResults.clear();
        this.outbox.clear();
        this.inbox.clear();
        this.sequence = 0L;
        this.durableEventCount = 0L;
        this.eventsSinceCheckpoint = 0L;
        long events = 0L;
        long tailEvents = 0L;
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String recoveryStage = "durable-log-recover";
        String eventType = "unknown";
        String eventVersion = "unknown";
        try {
            this.recovering = true;
            this.config.durableLog().recover(this.recoveryOptions());
            recoveryStage = "open-cursor";
            RecoveryStart recoveryStart = this.recoveryStart();
            long maxRecordsToReplay = recoveryStart.checkpointExpected() ? CHECKPOINT_LOOKUP_RECORD_LIMIT : MAX_RECOVERY_TAIL_EVENTS;
            boolean firstRecord = true;
            try (LogCursor cursor = this.config.durableLog().openCursor(EVENT_STREAM, recoveryStart.offset(), new CursorOptions(true))) {
                Optional<LogRecord> next = cursor.next();
                while (next.isPresent()) {
                    if (events >= maxRecordsToReplay) {
                        throw new IllegalStateException("messaging recovery tail bound exceeded");
                    }
                    LogRecord record = next.orElseThrow();
                    recoveryStage = "decode";
                    if (record.payloadLength() > this.effectiveEventPayloadLimit()) {
                        throw new IllegalArgumentException("messaging event payload limit exceeded");
                    }
                    QueueEventCodec.EventMetadata metadata = QueueEventCodec.metadata(record.payload());
                    eventType = metadata.type();
                    eventVersion = metadata.version();
                    QueueEvent event = QueueEventCodec.decode(record.payload());
                    eventType = event.type();
                    eventVersion = metadata.version();
                    recoveryStage = "apply";
                    if (firstRecord && recoveryStart.checkpointExpected() && !CHECKPOINT_EVENT_TYPE.equals(event.type())) {
                        throw new IllegalStateException("messaging checkpoint recovery start is invalid");
                    }
                    this.apply(event, false);
                    this.validateRecoveredState();
                    if (!CHECKPOINT_EVENT_TYPE.equals(event.type())) {
                        this.durableEventCount++;
                        tailEvents++;
                        if (tailEvents > MAX_RECOVERY_TAIL_EVENTS) {
                            throw new IllegalStateException("messaging recovery tail bound exceeded");
                        }
                    } else {
                        tailEvents = 0L;
                    }
                    events++;
                    firstRecord = false;
                    next = cursor.next();
                }
            }
        } catch (DurableLogException exception) {
            this.recovering = false;
            this.lastError = exception.code().name();
            this.restoreRecoveryState(snapshot);
            MessagingRecoveryReport report = this.failedRecoveryReport(events, warnings, errors,
                    recoveryStage, eventType, eventVersion, exception.code());
            this.closeAfterRecoveryFailure();
            throw new MessagingException(exception.code(), "messaging recovery failed", exception,
                    recoveryDetails(recoveryStage, eventType, eventVersion, exception.code()), report);
        } catch (RuntimeException exception) {
            this.recovering = false;
            this.lastError = MiddlewareErrorCode.RECOVERY_FAILED.name();
            this.restoreRecoveryState(snapshot);
            MessagingRecoveryReport report = this.failedRecoveryReport(events, warnings, errors,
                    recoveryStage, eventType, eventVersion, MiddlewareErrorCode.RECOVERY_FAILED);
            this.closeAfterRecoveryFailure();
            throw new MessagingException(MiddlewareErrorCode.RECOVERY_FAILED, "messaging recovery failed", exception,
                    recoveryDetails(recoveryStage, eventType, eventVersion, MiddlewareErrorCode.RECOVERY_FAILED), report);
        }
        this.recovering = false;
        this.eventsSinceCheckpoint = tailEvents;
        for (QueueState state : this.queues.values()) {
            this.expireLeases(state);
        }
        MessagingRecoveryReport report = new MessagingRecoveryReport(events, this.queues.size(), this.countMessages(),
                this.outbox.size(), this.inbox.size(), warnings, errors);
        this.recoveryReport = report;
        return report;
    }

    private void closeAfterRecoveryFailure() {
        this.queues.clear();
        this.idempotencyResults.clear();
        this.dedupeResults.clear();
        this.outbox.clear();
        this.inbox.clear();
        this.closed = true;
        if (this.config.ownsDurableLog()) {
            this.config.durableLog().close();
        }
    }

    private MessagingRecoveryReport failedRecoveryReport(
            final long events,
            final List<String> warnings,
            final List<String> errors,
            final String recoveryStage,
            final String eventType,
            final String eventVersion,
            final MiddlewareErrorCode errorCode) {
        List<String> safeErrors = new ArrayList<>(errors);
        safeErrors.add("stage=" + safeDiagnosticValue(recoveryStage)
                + ",eventType=" + safeDiagnosticValue(eventType)
                + ",eventVersion=" + safeDiagnosticValue(eventVersion)
                + ",errorCode=" + errorCode.name());
        MessagingRecoveryReport report = new MessagingRecoveryReport(events, this.queues.size(), this.countMessages(),
                this.outbox.size(), this.inbox.size(), warnings, safeErrors);
        this.recoveryReport = report;
        return report;
    }

    private static Map<String, String> recoveryDetails(
            final String recoveryStage,
            final String eventType,
            final String eventVersion,
            final MiddlewareErrorCode errorCode) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("recoveryStage", safeDiagnosticValue(recoveryStage));
        details.put("eventType", safeDiagnosticValue(eventType));
        details.put("eventVersion", safeDiagnosticValue(eventVersion));
        details.put("errorCode", errorCode.name());
        return details;
    }

    private static String safeDiagnosticValue(final String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        StringBuilder builder = new StringBuilder(Math.min(value.length(), 128));
        int limit = Math.min(value.length(), 128);
        for (int index = 0; index < limit; index++) {
            char current = value.charAt(index);
            if ((current >= 'A' && current <= 'Z')
                    || (current >= 'a' && current <= 'z')
                    || (current >= '0' && current <= '9')
                    || current == '_' || current == '-' || current == '.') {
                builder.append(current);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void close() {
        if (!this.closed) {
            this.closed = true;
            if (this.config.ownsDurableLog()) {
                this.config.durableLog().close();
            }
        }
    }

    private NackResult nackRecord(final QueueName queue, final MessageRecord record, final Instant notBefore, final String reason) {
        int retryCount = record.retryCount + 1;
        boolean dead = retryCount >= this.config.queueOptions().maxRetries();
        Map<String, String> fields = this.messageFields(dead ? "MESSAGE_DEAD_LETTERED" : "MESSAGE_NACKED", queue, record.messageId);
        fields.put("retryCount", Integer.toString(retryCount));
        fields.put("notBefore", Long.toString(notBefore.toEpochMilli()));
        fields.put("reason", reason);
        fields.put("leaseId", record.leaseId == null ? "" : record.leaseId.value());
        fields.put("consumerId", record.consumerId == null ? "" : record.consumerId.value());
        this.appendDomainEvent(new QueueEvent(dead ? "MESSAGE_DEAD_LETTERED" : "MESSAGE_NACKED", fields));
        record.retryCount = retryCount;
        record.leaseId = null;
        record.consumerId = null;
        record.leaseUntil = Instant.EPOCH;
        if (dead) {
            record.state = MessageState.DEAD_LETTERED;
            this.queueState(record.queue).moveToTerminal(record);
            this.metrics.deadLetterCount++;
        } else {
            record.state = notBefore.isAfter(this.config.clock().instant()) ? MessageState.RETRY_PENDING : MessageState.AVAILABLE;
            record.notBefore = notBefore;
            this.metrics.retryCount++;
        }
        this.metrics.nackCount++;
        return new NackResult(record.messageId, record.state, record.retryCount, dead);
    }

    private void expireLeases(final QueueState state) {
        Instant now = this.config.clock().instant();
        List<MessageRecord> expired = state.messages.values().stream()
                .filter(record -> record.state == MessageState.LEASED && !record.leaseUntil.isAfter(now))
                .toList();
        for (MessageRecord record : expired) {
            this.nackRecord(state.queue, record, now, "visibility-timeout");
        }
    }

    private void requireLiveLease(final MessageRecord record, final LeaseId expectedLease) {
        if (record.state != MessageState.LEASED || !record.leaseUntil.isAfter(this.config.clock().instant())) {
            throw this.failure(MiddlewareErrorCode.STALE_FENCING_TOKEN, "message lease is stale", null);
        }
        if (expectedLease == null || !expectedLease.equals(record.leaseId)) {
            throw this.failure(MiddlewareErrorCode.STALE_FENCING_TOKEN, "message lease does not match", null);
        }
    }

    private void ensureDomainEventCanAppend() {
        this.ensureDomainEventsCanAppend(1);
    }

    private void ensureDomainEventsCanAppend(final int eventCount) {
        if (eventCount < 1 || eventCount > MAX_RECOVERY_TAIL_EVENTS) {
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "messaging event count is invalid", null);
        }
        if (!this.recovering && this.eventsSinceCheckpoint + eventCount > MAX_RECOVERY_TAIL_EVENTS) {
            this.appendCheckpointRequired();
        }
    }

    private void appendDomainEvent(final QueueEvent event) {
        this.ensureDomainEventCanAppend();
        this.appendEncodedEvent(event);
        this.durableEventCount++;
        this.eventsSinceCheckpoint++;
    }

    private void appendEncodedEvent(final QueueEvent event) {
        long eventLimit = this.effectiveEventPayloadLimit();
        long countedSize = QueueEventCodec.encodedSize(event, eventLimit);
        if (countedSize > eventLimit) {
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "messaging event payload limit exceeded", null);
        }
        try {
            byte[] encoded = QueueEventCodec.encode(event);
            if (encoded.length > eventLimit) {
                throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "messaging event payload limit exceeded", null);
            }
            LogRecord record = new LogRecord(LogRecordHeader.data(Map.of(EVENT_METADATA_KEY, event.type())), encoded);
            this.config.durableLog().append(EVENT_STREAM, List.of(record), AppendOptions.defaults());
        } catch (RuntimeException exception) {
            if (exception instanceof MessagingException messagingException) {
                throw messagingException;
            }
            throw this.failure(MiddlewareErrorCode.DURABLE_LOG_ERROR, "messaging event append failed", exception);
        }
    }

    private void appendCheckpointRequired() {
        if (this.recovering) {
            return;
        }
        try {
            String body = this.checkpointBody();
            long checksum = checksum(body.getBytes(StandardCharsets.UTF_8));
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("checkpointVersion", "1");
            fields.put("eventCount", Long.toString(this.durableEventCount));
            fields.put("checksum", Long.toUnsignedString(checksum));
            fields.put("body", body);
            QueueEvent checkpoint = new QueueEvent(CHECKPOINT_EVENT_TYPE, fields);
            if (QueueEventCodec.encodedSize(checkpoint, this.effectiveEventPayloadLimit()) > this.effectiveEventPayloadLimit()) {
                throw this.failure(MiddlewareErrorCode.OVERLOADED, "messaging checkpoint payload limit exceeded", null);
            }
            this.appendEncodedEvent(checkpoint);
            this.eventsSinceCheckpoint = 0L;
        } catch (RuntimeException exception) {
            if (exception instanceof MessagingException messagingException) {
                throw messagingException;
            }
            throw this.failure(MiddlewareErrorCode.OVERLOADED, "messaging checkpoint could not be represented", exception);
        }
    }

    private RecoveryStart recoveryStart() {
        DurableLog durableLog = this.config.durableLog();
        Optional<LogOffset> checkpointOffset =
                durableLog.findLatestOffsetByMetadata(EVENT_STREAM, EVENT_METADATA_KEY, CHECKPOINT_EVENT_TYPE, CHECKPOINT_LOOKUP_RECORD_LIMIT);
        if (checkpointOffset.isPresent()) {
            return new RecoveryStart(checkpointOffset.orElseThrow(), true);
        }
        return new RecoveryStart(LogOffset.beginning(), false);
    }

    private RecoveryOptions recoveryOptions() {
        return RecoveryOptions.bounded(
                LogCorruptionAction.FAIL,
                true,
                RecoveryOptions.DEFAULT_MAX_SEGMENTS_SCANNED,
                recoveryRecordScanLimit());
    }

    private long effectiveEventPayloadLimit() {
        return Math.min(this.config.maxEventPayloadBytes(), (long) QueueEventCodec.maxEncodedEventBytes());
    }

    private RecoverySnapshot snapshotRecoveryState() {
        return new RecoverySnapshot(new LinkedHashMap<>(this.queues), new LinkedHashMap<>(this.idempotencyResults),
                new LinkedHashMap<>(this.dedupeResults), new LinkedHashMap<>(this.outbox), new LinkedHashMap<>(this.inbox),
                this.idGenerator.snapshot(), this.sequence, this.durableEventCount, this.eventsSinceCheckpoint);
    }

    private void restoreRecoveryState(final RecoverySnapshot snapshot) {
        this.queues.clear();
        this.queues.putAll(snapshot.queues());
        this.idempotencyResults.clear();
        this.idempotencyResults.putAll(snapshot.idempotencyResults());
        this.dedupeResults.clear();
        this.dedupeResults.putAll(snapshot.dedupeResults());
        this.outbox.clear();
        this.outbox.putAll(snapshot.outbox());
        this.inbox.clear();
        this.inbox.putAll(snapshot.inbox());
        this.idGenerator.restore(snapshot.idGenerator());
        this.sequence = snapshot.sequence();
        this.durableEventCount = snapshot.durableEventCount();
        this.eventsSinceCheckpoint = snapshot.eventsSinceCheckpoint();
    }

    private void validateRecoveredState() {
        QueueOptions queueOptions = this.config.queueOptions();
        for (QueueState state : this.queues.values()) {
            long liveMessages = 0L;
            long activePayloadBytes = 0L;
            long activeLeases = 0L;
            long delayedMessages = 0L;
            for (MessageRecord record : state.messages.values()) {
                this.validatePartition(record.partitionId);
                this.validateRetryCount(record.retryCount);
                int payloadLength = record.message.payloadLength();
                if (payloadLength > queueOptions.maxPayloadBytes()) {
                    throw new IllegalArgumentException("message payload limit exceeded");
                }
                if (record.state != MessageState.ACKED && record.state != MessageState.DEAD_LETTERED) {
                    liveMessages++;
                    activePayloadBytes = addRecoveredBytes(activePayloadBytes, payloadLength);
                    if (liveMessages > queueOptions.maxQueuedMessages()) {
                        throw new IllegalArgumentException("queue depth limit exceeded");
                    }
                }
                if (record.state == MessageState.LEASED) {
                    activeLeases++;
                    if (record.leaseId == null || record.consumerId == null) {
                        throw new IllegalArgumentException("active lease owner is invalid");
                    }
                    if (activeLeases > queueOptions.maxActiveLeases()) {
                        throw new IllegalArgumentException("active lease limit exceeded");
                    }
                }
                if (record.state == MessageState.DELAYED || record.state == MessageState.RETRY_PENDING) {
                    delayedMessages++;
                    if (delayedMessages > queueOptions.maxQueuedMessages()) {
                        throw new IllegalArgumentException("delayed message limit exceeded");
                    }
                }
            }
            long aggregateLimit = multiplyLimit(queueOptions.maxQueuedMessages(), queueOptions.maxPayloadBytes());
            if (activePayloadBytes > aggregateLimit) {
                throw new IllegalArgumentException("aggregate message payload limit exceeded");
            }
            if (state.terminalMessages.size() > TERMINAL_SUMMARY_LIMIT) {
                throw new IllegalArgumentException("terminal summary limit exceeded");
            }
            for (TerminalMessageSummary summary : state.terminalMessages.values()) {
                this.validatePartition(summary.partitionId());
                this.validateRetryCount(summary.retryCount());
            }
        }
        if (this.idempotencyResults.size() > DUPLICATE_DECISION_LIMIT || this.dedupeResults.size() > DUPLICATE_DECISION_LIMIT) {
            throw new IllegalArgumentException("duplicate decision limit exceeded");
        }
        if (this.pendingOutboxCount() > this.config.outboxOptions().maxPendingEntries()) {
            throw new IllegalArgumentException("outbox pending limit exceeded");
        }
        if (this.completedOutboxCount() > COMPLETED_OUTBOX_LIMIT) {
            throw new IllegalArgumentException("completed outbox limit exceeded");
        }
        for (OutboxRecord record : this.outbox.values()) {
            if (record.message.payloadLength() > queueOptions.maxPayloadBytes()) {
                throw new IllegalArgumentException("outbox payload limit exceeded");
            }
            if (record.attempts < 0 || record.attempts > this.config.outboxOptions().maxRelayAttempts()) {
                throw new IllegalArgumentException("outbox retry limit exceeded");
            }
        }
        if (this.inbox.size() > this.config.inboxOptions().maxDedupeEntries()) {
            throw new IllegalArgumentException("inbox dedupe limit exceeded");
        }
    }

    private void validatePartition(final QueuePartitionId partitionId) {
        if (partitionId.value() < 0 || partitionId.value() >= this.config.queueOptions().partitions()) {
            throw new IllegalArgumentException("partition id is out of range");
        }
    }

    private void validateRetryCount(final int retryCount) {
        if (retryCount < 0 || retryCount > this.config.queueOptions().maxRetries()) {
            throw new IllegalArgumentException("retry limit exceeded");
        }
    }

    private static long addRecoveredBytes(final long current, final long delta) {
        if (Long.MAX_VALUE - current < delta) {
            throw new IllegalArgumentException("aggregate message payload limit exceeded");
        }
        return current + delta;
    }

    private static long multiplyLimit(final int count, final int bytes) {
        long left = (long) count;
        long right = (long) bytes;
        if (left != 0L && right > Long.MAX_VALUE / left) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private record RecoverySnapshot(
            Map<QueueName, QueueState> queues,
            Map<String, EnqueueResult> idempotencyResults,
            Map<String, EnqueueResult> dedupeResults,
            Map<MessageId, OutboxRecord> outbox,
            Map<String, InboxEntry> inbox,
            MessageIdGenerator idGenerator,
            long sequence,
            long durableEventCount,
            long eventsSinceCheckpoint) {
    }

    private record RecoveryStart(LogOffset offset, boolean checkpointExpected) {
    }

    private String checkpointBody() {
        int bodyCharacterLimit = this.checkpointBodyCharacterLimit();
        StringBuilder builder = new StringBuilder(1024);
        for (QueueState state : this.queues.values()) {
            for (MessageRecord record : state.messages.values()) {
                appendFields(builder, bodyCharacterLimit, "M", record.queue.value(), Integer.toString(record.partitionId.value()), record.messageId.value(),
                        Integer.toString(record.priority.value()), Long.toString(record.sequence), record.state.name(),
                        Long.toString(record.notBefore.toEpochMilli()), Integer.toString(record.retryCount),
                        record.leaseId == null ? "" : record.leaseId.value(),
                        record.consumerId == null ? "" : record.consumerId.value(),
                        Long.toString(record.leaseUntil.toEpochMilli()), record.idempotencyKey, record.dedupeKey,
                        QueueEventCodec.encodeStringMap(record.message.header().metadata()),
                        Base64.getEncoder().encodeToString(record.message.payload()));
            }
            for (TerminalMessageSummary summary : state.terminalMessages.values()) {
                appendFields(builder, bodyCharacterLimit, "T", summary.queue.value(), Integer.toString(summary.partitionId.value()), summary.messageId.value(),
                        summary.state.name(), Integer.toString(summary.retryCount), summary.idempotencyKey, summary.dedupeKey,
                        Long.toString(summary.sequence));
            }
        }
        for (OutboxRecord record : this.outbox.values()) {
            EnqueueOptions options = record.enqueueOptions;
            appendFields(builder, bodyCharacterLimit, "O", record.entryId.value(), record.targetQueue.value(),
                    Base64.getEncoder().encodeToString(record.message.payload()),
                    QueueEventCodec.encodeStringMap(record.message.header().metadata()),
                    Integer.toString(options.priority().value()),
                    options.partitionId() == null ? "" : Integer.toString(options.partitionId().value()),
                    options.orderingKey() == null ? "" : options.orderingKey().value(),
                    options.idempotencyKey() == null ? "" : options.idempotencyKey().value(),
                    options.dedupeKey() == null ? "" : options.dedupeKey().value(),
                    Long.toString(options.notBefore().toEpochMilli()),
                    Long.toString(record.createdAt.toEpochMilli()),
                    Integer.toString(record.attempts), Boolean.toString(record.published), Boolean.toString(record.failed));
        }
        for (InboxEntry entry : this.inbox.values()) {
            appendFields(builder, bodyCharacterLimit, "I", entry.dedupeKey().value(), entry.decision().name(),
                    Long.toString(entry.recordedAt().toEpochMilli()), entry.resultMetadata());
        }
        return builder.toString();
    }

    private int checkpointBodyCharacterLimit() {
        long limit = this.effectiveEventPayloadLimit();
        if (limit <= 256L) {
            return 0;
        }
        return (int) Math.min((limit - 256L) / 2L, (long) Integer.MAX_VALUE);
    }

    private void restoreCheckpoint(final QueueEvent event) {
        String checkpointVersion = required(event.fields(), "checkpointVersion");
        if (!"1".equals(checkpointVersion)) {
            throw new IllegalArgumentException("unsupported messaging checkpoint version");
        }
        String body = required(event.fields(), "body");
        long expectedChecksum = Long.parseUnsignedLong(required(event.fields(), "checksum"));
        long actualChecksum = checksum(body.getBytes(StandardCharsets.UTF_8));
        if (expectedChecksum != actualChecksum) {
            throw new IllegalArgumentException("messaging checkpoint checksum mismatch");
        }
        this.queues.clear();
        this.idempotencyResults.clear();
        this.dedupeResults.clear();
        this.outbox.clear();
        this.inbox.clear();
        this.sequence = 0L;
        this.restoringCheckpoint = true;
        try {
            parseCheckpointBody(body);
            this.validateRecoveredState();
            this.durableEventCount = parseLong(required(event.fields(), "eventCount"));
        } finally {
            this.restoringCheckpoint = false;
        }
    }

    private void parseCheckpointBody(final String body) {
        int position = 0;
        while (position < body.length()) {
            int lineEnd = body.indexOf('\n', position);
            if (lineEnd < 0) {
                throw new IllegalArgumentException("messaging checkpoint line is truncated");
            }
            if (lineEnd > position) {
                List<String> fields = decodeCheckpointFields(body, position, lineEnd);
                applyCheckpointLine(fields);
            }
            position = lineEnd + 1;
        }
    }

    private void applyCheckpointLine(final List<String> fields) {
        String kind = fields.getFirst();
        if ("M".equals(kind)) {
            requireFieldCount(fields, 16);
            QueueName queue = new QueueName(fields.get(1));
            QueuePartitionId partitionId = new QueuePartitionId(parseInt(fields.get(2)));
            MessageId messageId = new MessageId(fields.get(3));
            MessagePriority priority = new MessagePriority(parseInt(fields.get(4)));
            long messageSequence = parseLong(fields.get(5));
            MessageState state = MessageState.valueOf(fields.get(6));
            Instant notBefore = Instant.ofEpochMilli(parseLong(fields.get(7)));
            int retryCount = parseInt(fields.get(8));
            LeaseId leaseId = fields.get(9).isEmpty() ? null : new LeaseId(fields.get(9));
            ConsumerId consumerId = fields.get(10).isEmpty() ? null : new ConsumerId(fields.get(10));
            Instant leaseUntil = Instant.ofEpochMilli(parseLong(fields.get(11)));
            validateCheckpointMessageState(state, leaseId, consumerId);
            String idempotencyKey = fields.get(12);
            String dedupeKey = fields.get(13);
            MessageHeader header = new MessageHeader(QueueEventCodec.decodeStringMap(fields.get(14)));
            QueueMessage message = new QueueMessage(header, Base64.getDecoder().decode(fields.get(15)));
            MessageRecord record = new MessageRecord(queue, partitionId, messageId, message, priority, messageSequence, state,
                    notBefore, idempotencyKey, dedupeKey);
            record.retryCount = retryCount;
            record.leaseId = leaseId;
            record.consumerId = consumerId;
            record.leaseUntil = leaseUntil;
            QueueState queueState = this.queueState(queue);
            if (queueState.messages.containsKey(messageId) || queueState.terminalMessages.containsKey(messageId)) {
                throw new IllegalArgumentException("duplicate messaging checkpoint message id");
            }
            queueState.messages.put(messageId, record);
            this.idGenerator.observeMessage(messageId);
            if (leaseId != null) {
                this.idGenerator.observeLease(leaseId);
            }
            this.sequence = Math.max(this.sequence, messageSequence);
            this.rememberDuplicateKeys(record, new EnqueueResult(queue, partitionId, messageId, false, messageSequence));
        } else if ("T".equals(kind)) {
            requireFieldCount(fields, 9);
            QueueName queue = new QueueName(fields.get(1));
            QueuePartitionId partitionId = new QueuePartitionId(parseInt(fields.get(2)));
            MessageId messageId = new MessageId(fields.get(3));
            MessageState state = MessageState.valueOf(fields.get(4));
            validateCheckpointTerminalState(state);
            int retryCount = parseInt(fields.get(5));
            String idempotencyKey = fields.get(6);
            String dedupeKey = fields.get(7);
            long messageSequence = parseLong(fields.get(8));
            QueueState queueState = this.queueState(queue);
            if (queueState.messages.containsKey(messageId) || queueState.terminalMessages.containsKey(messageId)) {
                throw new IllegalArgumentException("duplicate messaging checkpoint message id");
            }
            queueState.putTerminal(new TerminalMessageSummary(queue, messageId, partitionId, state, retryCount,
                    idempotencyKey, dedupeKey, messageSequence));
            this.idGenerator.observeMessage(messageId);
            this.sequence = Math.max(this.sequence, messageSequence);
            this.rememberDuplicateDecision(idempotencyKey, dedupeKey, new EnqueueResult(queue, partitionId, messageId, false, messageSequence));
        } else if ("O".equals(kind)) {
            requireFieldCount(fields, 15);
            MessageId entryId = new MessageId(fields.get(1));
            QueueName targetQueue = new QueueName(fields.get(2));
            QueueMessage message = new QueueMessage(new MessageHeader(QueueEventCodec.decodeStringMap(fields.get(4))),
                    Base64.getDecoder().decode(fields.get(3)));
            QueuePartitionId partitionId = optionalPartition(fields.get(6));
            OrderingKey orderingKey = optionalOrderingKey(fields.get(7));
            IdempotencyKey idempotencyKey = optionalIdempotencyKey(fields.get(8));
            DedupeKey dedupeKey = optionalDedupeKey(fields.get(9));
            EnqueueOptions options = new EnqueueOptions(new MessagePriority(parseInt(fields.get(5))), partitionId, orderingKey,
                    idempotencyKey, dedupeKey, Instant.ofEpochMilli(parseLong(fields.get(10))));
            OutboxRecord record = new OutboxRecord(entryId, targetQueue, message, options, Instant.ofEpochMilli(parseLong(fields.get(11))));
            record.attempts = parseInt(fields.get(12));
            record.published = parseBooleanStrict(fields.get(13));
            record.failed = parseBooleanStrict(fields.get(14));
            if (this.outbox.containsKey(entryId)) {
                throw new IllegalArgumentException("duplicate messaging checkpoint outbox entry");
            }
            this.outbox.put(entryId, record);
            this.idGenerator.observeMessage(entryId);
            this.enforceCompletedOutboxLimit();
        } else if ("I".equals(kind)) {
            requireFieldCount(fields, 5);
            DedupeKey key = new DedupeKey(fields.get(1));
            if (this.inbox.containsKey(key.value())) {
                throw new IllegalArgumentException("duplicate messaging checkpoint inbox entry");
            }
            this.inbox.put(key.value(), new InboxEntry(key, InboxDuplicateDecision.valueOf(fields.get(2)),
                    Instant.ofEpochMilli(parseLong(fields.get(3))), fields.get(4)));
        } else {
            throw new IllegalArgumentException("unknown messaging checkpoint record");
        }
    }

    private static void appendFields(final StringBuilder builder, final int characterLimit, final String kind, final String... values) {
        builder.append(kind);
        for (String value : values) {
            builder.append('|').append(Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
            if (builder.length() > characterLimit) {
                throw new IllegalArgumentException("messaging checkpoint body limit exceeded");
            }
        }
        builder.append('\n');
        if (builder.length() > characterLimit) {
            throw new IllegalArgumentException("messaging checkpoint body limit exceeded");
        }
    }

    private static List<String> decodeCheckpointFields(final String body, final int start, final int end) {
        List<String> fields = new ArrayList<>();
        int fieldStart = start;
        while (fieldStart <= end) {
            int separator = fieldStart;
            while (separator < end && body.charAt(separator) != '|') {
                separator++;
            }
            if (fields.isEmpty()) {
                fields.add(body.substring(fieldStart, separator));
            } else {
                byte[] decoded = Base64.getUrlDecoder().decode(body.substring(fieldStart, separator));
                fields.add(new String(decoded, StandardCharsets.UTF_8));
            }
            if (separator == end) {
                break;
            }
            fieldStart = separator + 1;
        }
        return fields;
    }

    private static void requireFieldCount(final List<String> fields, final int expected) {
        if (fields.size() != expected) {
            throw new IllegalArgumentException("messaging checkpoint field count is invalid");
        }
    }

    private static void validateCheckpointMessageState(final MessageState state, final LeaseId leaseId, final ConsumerId consumerId) {
        if (state == MessageState.ACKED || state == MessageState.DEAD_LETTERED) {
            throw new IllegalArgumentException("terminal messaging checkpoint state is invalid for active message row");
        }
        if (state == MessageState.LEASED) {
            if (leaseId == null || consumerId == null) {
                throw new IllegalArgumentException("leased messaging checkpoint row requires lease owner");
            }
            return;
        }
        if (leaseId != null || consumerId != null) {
            throw new IllegalArgumentException("non-leased messaging checkpoint row must not carry lease owner");
        }
    }

    private static void validateCheckpointTerminalState(final MessageState state) {
        if (state != MessageState.ACKED && state != MessageState.DEAD_LETTERED) {
            throw new IllegalArgumentException("non-terminal messaging checkpoint state is invalid for terminal row");
        }
    }

    private static long checksum(final byte[] bytes) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes, 0, bytes.length);
        return crc32.getValue();
    }

    private void apply(final QueueEvent event, final boolean countMetrics) {
        Map<String, String> fields = event.fields();
        if (CHECKPOINT_EVENT_TYPE.equals(event.type())) {
            this.restoreCheckpoint(event);
        } else if ("MESSAGE_ENQUEUED".equals(event.type())) {
            QueueName queue = new QueueName(required(fields, "queue"));
            MessageId messageId = new MessageId(required(fields, "messageId"));
            QueuePartitionId partitionId = new QueuePartitionId(parseInt(required(fields, "partition")));
            long eventSequence = parseLong(required(fields, "sequence"));
            MessagePriority priority = new MessagePriority(parseInt(required(fields, "priority")));
            Instant notBefore = Instant.ofEpochMilli(parseLong(required(fields, "notBefore")));
            MessageHeader header = new MessageHeader(QueueEventCodec.decodeStringMap(fields.getOrDefault("headers", "")));
            QueueMessage message = new QueueMessage(header, Base64.getDecoder().decode(required(fields, "payload")));
            MessageState state = notBefore.isAfter(this.config.clock().instant()) ? MessageState.DELAYED : MessageState.AVAILABLE;
            MessageRecord record = new MessageRecord(queue, partitionId, messageId, message, priority, eventSequence, state,
                    notBefore, fields.getOrDefault("idempotencyKey", ""), fields.getOrDefault("dedupeKey", ""));
            QueueState queueState = this.queueState(queue);
            if (this.findRecord(queue, messageId) != null || queueState.terminalMessages.containsKey(messageId)) {
                throw new IllegalArgumentException("duplicate messaging event message id");
            }
            queueState.messages.put(messageId, record);
            this.idGenerator.observeMessage(messageId);
            this.sequence = Math.max(this.sequence, eventSequence);
            this.rememberDuplicateKeys(record, new EnqueueResult(queue, partitionId, messageId, false, eventSequence));
            if (countMetrics) {
                this.metrics.enqueueCount++;
            }
        } else if ("MESSAGE_LEASED".equals(event.type())) {
            MessageRecord record = this.requireEventRecord(fields);
            if (record.state == MessageState.ACKED || record.state == MessageState.DEAD_LETTERED) {
                throw new IllegalArgumentException("invalid messaging event transition");
            }
            LeaseId leaseId = new LeaseId(required(fields, "leaseId"));
            record.state = MessageState.LEASED;
            record.leaseId = leaseId;
            record.consumerId = new ConsumerId(required(fields, "consumerId"));
            record.leaseUntil = Instant.ofEpochMilli(parseLong(required(fields, "leaseUntil")));
            this.idGenerator.observeLease(leaseId);
        } else if ("MESSAGE_ACKED".equals(event.type())) {
            MessageRecord record = this.requireEventRecord(fields);
            if (record.state != MessageState.LEASED) {
                throw new IllegalArgumentException("invalid messaging event transition");
            }
            LeaseId leaseId = new LeaseId(required(fields, "leaseId"));
            ConsumerId consumerId = new ConsumerId(required(fields, "consumerId"));
            if (!leaseId.equals(record.leaseId) || !consumerId.equals(record.consumerId)) {
                throw new IllegalArgumentException("invalid messaging event lease owner");
            }
            record.state = MessageState.ACKED;
            record.leaseId = null;
            record.consumerId = null;
            this.queueState(record.queue).moveToTerminal(record);
        } else if ("MESSAGE_NACKED".equals(event.type()) || "MESSAGE_DEAD_LETTERED".equals(event.type())) {
            MessageRecord record = this.requireEventRecord(fields);
            if (record.state != MessageState.LEASED) {
                throw new IllegalArgumentException("invalid messaging event transition");
            }
            LeaseId leaseId = new LeaseId(required(fields, "leaseId"));
            ConsumerId consumerId = new ConsumerId(required(fields, "consumerId"));
            if (!leaseId.equals(record.leaseId) || !consumerId.equals(record.consumerId)) {
                throw new IllegalArgumentException("invalid messaging event lease owner");
            }
            record.retryCount = parseInt(required(fields, "retryCount"));
            record.notBefore = Instant.ofEpochMilli(parseLong(required(fields, "notBefore")));
            required(fields, "reason");
            record.leaseId = null;
            record.consumerId = null;
            record.leaseUntil = Instant.EPOCH;
            record.state = "MESSAGE_DEAD_LETTERED".equals(event.type()) ? MessageState.DEAD_LETTERED
                    : (record.notBefore.isAfter(this.config.clock().instant()) ? MessageState.RETRY_PENDING : MessageState.AVAILABLE);
            if (record.state == MessageState.DEAD_LETTERED) {
                this.queueState(record.queue).moveToTerminal(record);
            }
        } else if ("OUTBOX_RECORDED".equals(event.type())) {
            MessageId entryId = new MessageId(required(fields, "entryId"));
            QueuePartitionId partitionId = optionalPartition(fields.getOrDefault("partition", ""));
            OrderingKey orderingKey = optionalOrderingKey(fields.getOrDefault("orderingKey", ""));
            IdempotencyKey idempotencyKey = optionalIdempotencyKey(fields.getOrDefault("idempotencyKey", ""));
            DedupeKey dedupeKey = optionalDedupeKey(fields.getOrDefault("dedupeKey", ""));
            EnqueueOptions options = new EnqueueOptions(new MessagePriority(parseInt(required(fields, "priority"))),
                    partitionId, orderingKey, idempotencyKey, dedupeKey, Instant.ofEpochMilli(parseLong(required(fields, "notBefore"))));
            MessageHeader header = new MessageHeader(QueueEventCodec.decodeStringMap(fields.getOrDefault("headers", "")));
            OutboxRecord record = new OutboxRecord(entryId, new QueueName(required(fields, "queue")),
                    new QueueMessage(header, Base64.getDecoder().decode(required(fields, "payload"))),
                    options, Instant.ofEpochMilli(parseLong(required(fields, "createdAt"))));
            if (this.outbox.containsKey(entryId)) {
                throw new IllegalArgumentException("duplicate messaging outbox entry");
            }
            this.outbox.put(entryId, record);
            this.idGenerator.observeMessage(entryId);
        } else if ("OUTBOX_PUBLISHED".equals(event.type())) {
            OutboxRecord record = this.outbox.get(new MessageId(required(fields, "entryId")));
            if (record == null) {
                throw new IllegalArgumentException("missing messaging outbox entry");
            }
            record.published = true;
            this.enforceCompletedOutboxLimit();
        } else if ("OUTBOX_FAILED".equals(event.type())) {
            OutboxRecord record = this.outbox.get(new MessageId(required(fields, "entryId")));
            if (record == null) {
                throw new IllegalArgumentException("missing messaging outbox entry");
            }
            record.attempts = parseInt(required(fields, "attempts"));
            record.failed = parseBooleanStrict(required(fields, "failed"));
            this.enforceCompletedOutboxLimit();
        } else if ("INBOX_RECORDED".equals(event.type())) {
            DedupeKey key = new DedupeKey(required(fields, "dedupeKey"));
            if (this.inbox.containsKey(key.value())) {
                throw new IllegalArgumentException("duplicate messaging inbox entry");
            }
            this.inbox.put(key.value(), new InboxEntry(key, InboxDuplicateDecision.ACCEPTED,
                    Instant.ofEpochMilli(parseLong(required(fields, "recordedAt"))), fields.getOrDefault("result", "")));
        } else if ("INBOX_DUPLICATE_SUPPRESSED".equals(event.type())) {
            required(fields, "dedupeKey");
            required(fields, "decision");
        } else {
            throw new IllegalArgumentException("unknown messaging event type");
        }
    }

    private MessageRecord requireEventRecord(final Map<String, String> fields) {
        MessageRecord record = this.findRecord(new QueueName(required(fields, "queue")), new MessageId(required(fields, "messageId")));
        if (record == null) {
            throw new IllegalArgumentException("missing messaging event record");
        }
        return record;
    }

    private static String required(final Map<String, String> fields, final String key) {
        String value = fields.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("messaging event missing required field");
        }
        return value;
    }

    private static int parseInt(final String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid messaging event number", exception);
        }
    }

    private static long parseLong(final String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid messaging event number", exception);
        }
    }

    private static boolean parseBooleanStrict(final String value) {
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException("invalid messaging event boolean");
    }

    private static boolean exceedsUtf8Bytes(final String value, final int maxBytes) {
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current <= 0x7F) {
                bytes++;
            } else if (current <= 0x7FF) {
                bytes += 2;
            } else if (Character.isHighSurrogate(current) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
            if (bytes > maxBytes) {
                return true;
            }
        }
        return false;
    }

    private static QueuePartitionId optionalPartition(final String value) {
        return value == null || value.isEmpty() ? null : new QueuePartitionId(parseInt(value));
    }

    private static OrderingKey optionalOrderingKey(final String value) {
        return value == null || value.isEmpty() ? null : new OrderingKey(value);
    }

    private static IdempotencyKey optionalIdempotencyKey(final String value) {
        return value == null || value.isEmpty() ? null : new IdempotencyKey(value);
    }

    private static DedupeKey optionalDedupeKey(final String value) {
        return value == null || value.isEmpty() ? null : new DedupeKey(value);
    }

    private QueuePartitionId partitionFor(final EnqueueOptions options, final MessageId messageId) {
        int partitions = this.config.queueOptions().partitions();
        if (options.partitionId() != null) {
            if (options.partitionId().value() >= partitions) {
                throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "partition id is out of range", null);
            }
            return options.partitionId();
        }
        OrderingKey orderingKey = options.orderingKey();
        if (orderingKey != null) {
            return new QueuePartitionId(Math.floorMod(orderingKey.value().hashCode(), partitions));
        }
        return new QueuePartitionId(Math.floorMod(messageId.value().hashCode(), partitions));
    }

    private EnqueueResult previousDuplicate(final String idempotencyKey, final String dedupeKey) {
        if (!idempotencyKey.isEmpty() && this.idempotencyResults.containsKey(idempotencyKey)) {
            return this.idempotencyResults.get(idempotencyKey);
        }
        if (!dedupeKey.isEmpty() && this.dedupeResults.containsKey(dedupeKey)) {
            return this.dedupeResults.get(dedupeKey);
        }
        return null;
    }

    private void rememberDuplicateKeys(final MessageRecord record, final EnqueueResult result) {
        if (!record.idempotencyKey.isEmpty()) {
            this.idempotencyResults.put(record.idempotencyKey, result);
            this.enforceDuplicateDecisionLimit(this.idempotencyResults);
        }
        if (!record.dedupeKey.isEmpty()) {
            this.dedupeResults.put(record.dedupeKey, result);
            this.enforceDuplicateDecisionLimit(this.dedupeResults);
        }
    }

    private void rememberDuplicateDecision(final String idempotencyKey, final String dedupeKey, final EnqueueResult result) {
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            this.idempotencyResults.put(idempotencyKey, result);
            this.enforceDuplicateDecisionLimit(this.idempotencyResults);
        }
        if (dedupeKey != null && !dedupeKey.isEmpty()) {
            this.dedupeResults.put(dedupeKey, result);
            this.enforceDuplicateDecisionLimit(this.dedupeResults);
        }
    }

    private void enforceDuplicateDecisionLimit(final Map<String, EnqueueResult> decisions) {
        if (decisions.size() > DUPLICATE_DECISION_LIMIT && this.recovering) {
            throw new IllegalArgumentException("duplicate decision limit exceeded");
        }
        while (decisions.size() > DUPLICATE_DECISION_LIMIT) {
            String oldest = decisions.keySet().iterator().next();
            decisions.remove(oldest);
        }
    }

    private void removeDuplicateDecision(final String idempotencyKey, final String dedupeKey, final MessageId messageId) {
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            EnqueueResult result = this.idempotencyResults.get(idempotencyKey);
            if (result != null && result.messageId().equals(messageId)) {
                this.idempotencyResults.remove(idempotencyKey);
            }
        }
        if (dedupeKey != null && !dedupeKey.isEmpty()) {
            EnqueueResult result = this.dedupeResults.get(dedupeKey);
            if (result != null && result.messageId().equals(messageId)) {
                this.dedupeResults.remove(dedupeKey);
            }
        }
    }

    private void enforceCompletedOutboxLimit() {
        if (this.completedOutboxCount() > COMPLETED_OUTBOX_LIMIT && this.restoringCheckpoint) {
            throw new IllegalArgumentException("completed outbox limit exceeded");
        }
        while (this.completedOutboxCount() > COMPLETED_OUTBOX_LIMIT) {
            MessageId oldestCompleted = null;
            for (OutboxRecord record : this.outbox.values()) {
                if (record.published || record.failed) {
                    oldestCompleted = record.entryId;
                    break;
                }
            }
            if (oldestCompleted == null) {
                return;
            }
            this.outbox.remove(oldestCompleted);
        }
    }

    private long completedOutboxCount() {
        long count = 0L;
        for (OutboxRecord record : this.outbox.values()) {
            if (record.published || record.failed) {
                count++;
            }
        }
        return count;
    }

    private Map<String, String> messageFields(final String type, final QueueName queue, final MessageId messageId) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("queue", queue.value());
        fields.put("messageId", messageId.value());
        return fields;
    }

    private QueueState queueState(final QueueName queue) {
        return this.queues.computeIfAbsent(queue, ignored -> new QueueState(queue));
    }

    private QueueName requireQueue(final QueueName queue) {
        if (queue == null) {
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "queue is required", null);
        }
        return queue;
    }

    private MessageRecord requireMessage(final QueueName queue, final MessageId messageId) {
        if (messageId == null) {
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "message id is required", null);
        }
        MessageRecord record = this.findRecord(this.requireQueue(queue), messageId);
        if (record == null) {
            throw this.failure(MiddlewareErrorCode.NOT_FOUND, "message is not available", null);
        }
        return record;
    }

    private MessageRecord findRecord(final QueueName queue, final MessageId messageId) {
        QueueState state = this.queues.get(queue);
        return state == null ? null : state.messages.get(messageId);
    }

    private List<QueuePartitionStatus> partitionStatuses(final QueueState state) {
        Map<QueuePartitionId, QueuePartitionState> partitions = new LinkedHashMap<>();
        for (int index = 0; index < this.config.queueOptions().partitions(); index++) {
            QueuePartitionId partitionId = new QueuePartitionId(index);
            partitions.put(partitionId, new QueuePartitionState(partitionId));
        }
        Instant now = this.config.clock().instant();
        for (MessageRecord record : state.messages.values()) {
            QueuePartitionState partition = partitions.get(record.partitionId);
            if (record.state == MessageState.LEASED) {
                partition.leased++;
            } else if (record.state == MessageState.DEAD_LETTERED) {
                partition.deadLettered++;
            } else if (!record.notBefore.isAfter(now) && record.state != MessageState.ACKED) {
                partition.available++;
            } else if (record.state != MessageState.ACKED) {
                partition.delayed++;
            }
        }
        for (TerminalMessageSummary summary : state.terminalMessages.values()) {
            if (summary.state == MessageState.DEAD_LETTERED) {
                QueuePartitionState partition = partitions.get(summary.partitionId);
                if (partition != null) {
                    partition.deadLettered++;
                }
            }
        }
        return partitions.values().stream()
                .map(partition -> new QueuePartitionStatus(partition.partitionId, partition.available, partition.leased, partition.delayed, partition.deadLettered))
                .toList();
    }

    private long countMessages() {
        return this.queues.values().stream().mapToLong(state -> state.messages.size() + state.terminalMessages.size()).sum();
    }

    private long pendingOutboxCount() {
        return this.outbox.values().stream().filter(record -> !record.published && !record.failed).count();
    }

    private void ensureOpen() {
        if (this.closed) {
            throw this.failure(MiddlewareErrorCode.UNAVAILABLE, "messaging service is closed", null);
        }
    }

    private MessagingException failure(final MiddlewareErrorCode code, final String message, final Throwable cause) {
        this.lastError = code.name();
        if (cause == null) {
            return new MessagingException(code, message);
        }
        return new MessagingException(code, message, cause);
    }

    private final class QueueState {
        final QueueName queue;
        final Map<MessageId, MessageRecord> messages;
        final Map<MessageId, TerminalMessageSummary> terminalMessages;
        final Map<ConsumerGroupId, ConsumerGroupState> consumerGroups;

        QueueState(final QueueName queue) {
            this.queue = queue;
            this.messages = new LinkedHashMap<>();
            this.terminalMessages = new LinkedHashMap<>();
            this.consumerGroups = new LinkedHashMap<>();
        }

        int activeLeaseCount() {
            return (int) this.messages.values().stream().filter(record -> record.state == MessageState.LEASED).count();
        }

        long liveMessageCount() {
            return this.messages.values().stream()
                    .filter(record -> record.state != MessageState.ACKED && record.state != MessageState.DEAD_LETTERED)
                    .count();
        }

        void moveToTerminal(final MessageRecord record) {
            this.messages.remove(record.messageId);
            this.putTerminal(new TerminalMessageSummary(record.queue, record.messageId, record.partitionId, record.state, record.retryCount,
                    record.idempotencyKey, record.dedupeKey, record.sequence));
        }

        void putTerminal(final TerminalMessageSummary summary) {
            this.terminalMessages.put(summary.messageId, summary);
            if (this.terminalMessages.size() > TERMINAL_SUMMARY_LIMIT && LogBackedDurableQueue.this.recovering) {
                throw new IllegalArgumentException("terminal summary limit exceeded");
            }
            while (this.terminalMessages.size() > TERMINAL_SUMMARY_LIMIT) {
                MessageId first = this.terminalMessages.keySet().iterator().next();
                TerminalMessageSummary removed = this.terminalMessages.remove(first);
                if (removed != null) {
                    LogBackedDurableQueue.this.removeDuplicateDecision(removed.idempotencyKey, removed.dedupeKey, removed.messageId);
                }
            }
        }
    }

    private record TerminalMessageSummary(
            QueueName queue,
            MessageId messageId,
            QueuePartitionId partitionId,
            MessageState state,
            int retryCount,
            String idempotencyKey,
            String dedupeKey,
            long sequence) {
    }

    private static final class MessageRecord {
        final QueueName queue;
        final QueuePartitionId partitionId;
        final MessageId messageId;
        final QueueMessage message;
        final MessagePriority priority;
        final long sequence;
        final String idempotencyKey;
        final String dedupeKey;
        MessageState state;
        Instant notBefore;
        Instant leaseUntil;
        LeaseId leaseId;
        ConsumerId consumerId;
        int retryCount;

        MessageRecord(final QueueName queue, final QueuePartitionId partitionId, final MessageId messageId, final QueueMessage message,
                final MessagePriority priority, final long sequence, final MessageState state, final Instant notBefore,
                final String idempotencyKey, final String dedupeKey) {
            this.queue = queue;
            this.partitionId = partitionId;
            this.messageId = messageId;
            this.message = message;
            this.priority = priority;
            this.sequence = sequence;
            this.state = state;
            this.notBefore = notBefore;
            this.idempotencyKey = idempotencyKey == null ? "" : idempotencyKey;
            this.dedupeKey = dedupeKey == null ? "" : dedupeKey;
            this.leaseUntil = Instant.EPOCH;
            this.retryCount = 0;
        }

        boolean isAvailable(final Instant now) {
            return this.state != MessageState.ACKED
                    && this.state != MessageState.DEAD_LETTERED
                    && this.state != MessageState.LEASED
                    && !this.notBefore.isAfter(now);
        }

        MessageEnvelope envelope() {
            return new MessageEnvelope(this.queue, this.partitionId, this.messageId, this.message, this.state, this.priority, this.sequence, this.retryCount);
        }
    }

    private static final class OutboxRecord {
        final MessageId entryId;
        final QueueName targetQueue;
        final QueueMessage message;
        final EnqueueOptions enqueueOptions;
        final Instant createdAt;
        int attempts;
        boolean published;
        boolean failed;

        OutboxRecord(final MessageId entryId, final QueueName targetQueue, final QueueMessage message, final EnqueueOptions enqueueOptions, final Instant createdAt) {
            this.entryId = entryId;
            this.targetQueue = targetQueue;
            this.message = message;
            this.enqueueOptions = enqueueOptions;
            this.createdAt = createdAt;
        }

        OutboxEntry entry() {
            return new OutboxEntry(this.entryId, this.targetQueue, this.message, this.enqueueOptions, this.enqueueOptions.idempotencyKey(),
                    this.createdAt, this.attempts, this.published, this.failed);
        }
    }
}
