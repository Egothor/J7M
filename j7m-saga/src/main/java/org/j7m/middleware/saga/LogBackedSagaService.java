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

package org.j7m.middleware.saga;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.CursorOptions;
import org.j7m.middleware.api.durablelog.LogCursor;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.j7m.middleware.api.durablelog.LogRecord;
import org.j7m.middleware.api.durablelog.LogRecordHeader;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.api.durablelog.RecoveryOptions;
import org.j7m.middleware.api.messaging.EnqueueOptions;
import org.j7m.middleware.api.messaging.MessageHeader;
import org.j7m.middleware.api.messaging.QueueMessage;
import org.j7m.middleware.api.messaging.QueueName;
import org.j7m.middleware.api.reliability.DedupeKey;
import org.j7m.middleware.api.reliability.IdempotencyKey;
import org.j7m.middleware.api.saga.SagaCancelRequest;
import org.j7m.middleware.api.saga.SagaCancelResult;
import org.j7m.middleware.api.saga.SagaCancellationPolicy;
import org.j7m.middleware.api.saga.SagaCommand;
import org.j7m.middleware.api.saga.SagaCommandResult;
import org.j7m.middleware.api.saga.SagaCompensationResult;
import org.j7m.middleware.api.saga.SagaDeadLetterRecord;
import org.j7m.middleware.api.saga.SagaDefinition;
import org.j7m.middleware.api.saga.SagaDefinitionId;
import org.j7m.middleware.api.saga.SagaEvidence;
import org.j7m.middleware.api.saga.SagaException;
import org.j7m.middleware.api.saga.SagaExecution;
import org.j7m.middleware.api.saga.SagaExecutionId;
import org.j7m.middleware.api.saga.SagaExecutionState;
import org.j7m.middleware.api.saga.SagaMetricsSnapshot;
import org.j7m.middleware.api.saga.SagaOptions;
import org.j7m.middleware.api.saga.SagaRecoveryReport;
import org.j7m.middleware.api.saga.SagaRetryPolicy;
import org.j7m.middleware.api.saga.SagaService;
import org.j7m.middleware.api.saga.SagaStartRequest;
import org.j7m.middleware.api.saga.SagaStartResult;
import org.j7m.middleware.api.saga.SagaStatus;
import org.j7m.middleware.api.saga.SagaStepDefinition;
import org.j7m.middleware.api.saga.SagaStepExecution;
import org.j7m.middleware.api.saga.SagaStepId;
import org.j7m.middleware.api.saga.SagaStepState;
import org.j7m.middleware.api.saga.SagaTimeoutPolicy;
import org.j7m.middleware.api.saga.TransactionContext;
import org.j7m.middleware.api.saga.TransactionContextSummary;
import org.j7m.middleware.api.saga.TransactionContextPropagation;
import org.j7m.middleware.api.saga.TransactionScope;
import org.j7m.middleware.durablelog.DurableLogConfig;
import org.j7m.middleware.durablelog.FileDurableLog;
import org.j7m.middleware.messaging.DurableQueueFactory;
import org.j7m.middleware.messaging.LogBackedDurableQueue;

/**
 * Durable-log-backed local saga service.
 */
public final class LogBackedSagaService implements SagaService {
    private static final LogStreamId EVENT_STREAM = new LogStreamId("j7m.saga.events");
    private static final QueueName COMMAND_QUEUE = new QueueName("saga-commands");
    private static final String CHECKPOINT_VERSION = "1";
    private static final String EVENT_METADATA_KEY = "eventType";
    private static final int MAX_RECOVERY_TAIL_EVENTS = 256;
    private static final int CHECKPOINT_LOOKUP_RECORD_LIMIT = 4096;
    private static final int MAX_TERMINAL_EXECUTION_SUMMARIES = 256;
    private static final int MAX_OUTBOX_MARKERS = 2048;

    private final SagaConfig config;
    private final FileDurableLog log;
    private final LogBackedDurableQueue outbox;
    private final SagaEventCodec codec;
    private final AtomicBoolean closed;
    private final Map<SagaDefinitionId, SagaDefinition> definitions;
    private final Map<SagaDefinitionId, RecoveredDefinitionMetadata> recoveredDefinitions;
    private final Map<SagaExecutionId, ExecutionRuntime> executions;
    private final Map<String, SagaExecutionId> idempotency;
    private final Set<String> outboxIntents;
    private final Set<String> outboxPublished;
    private final Set<String> outboxFailures;
    private final Set<String> outboxDeadLetters;
    private final List<SagaEvidence> evidence;
    private final List<SagaDeadLetterRecord> deadLetters;
    private SagaRecoveryReport recoveryReport;
    private String lastError;
    private long completedSagas;
    private long failedSagas;
    private long cancelledSagas;
    private long timedOutSagas;
    private long compensations;
    private long retries;
    private long rejectedOperations;
    private long durableEventCount;
    private long eventsSinceCheckpoint;
    private boolean recovering;
    private SagaOutboxExecutionMode outboxExecutionMode;

    /**
     * Opens and recovers the saga service.
     *
     * @param config validated service configuration
     */
    public LogBackedSagaService(final SagaConfig config) {
        this.config = config;
        this.log = new FileDurableLog(DurableLogConfig.defaults(config.eventLogDirectory()));
        this.outbox = DurableQueueFactory.open(config.messagingDirectory());
        this.codec = new SagaEventCodec();
        this.closed = new AtomicBoolean(false);
        this.definitions = new LinkedHashMap<>();
        this.recoveredDefinitions = new LinkedHashMap<>();
        this.executions = new LinkedHashMap<>();
        this.idempotency = new LinkedHashMap<>();
        this.outboxIntents = new LinkedHashSet<>();
        this.outboxPublished = new LinkedHashSet<>();
        this.outboxFailures = new LinkedHashSet<>();
        this.outboxDeadLetters = new LinkedHashSet<>();
        this.evidence = new ArrayList<>();
        this.deadLetters = new ArrayList<>();
        this.recoveryReport = new SagaRecoveryReport(0L, 0L, 0L, 0L, false, List.of());
        this.lastError = "";
        this.durableEventCount = 0L;
        this.eventsSinceCheckpoint = 0L;
        this.recovering = false;
        this.outboxExecutionMode = SagaOutboxExecutionMode.NORMAL;
        this.recover();
    }

    void setOutboxExecutionMode(final SagaOutboxExecutionMode mode) {
        this.outboxExecutionMode = mode == null ? SagaOutboxExecutionMode.NORMAL : mode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void register(final SagaDefinition definition) {
        this.ensureOpen();
        if (definition == null) {
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "saga definition is required", Map.of());
        }
        if (definition.steps().size() > this.config.maxStepsPerSaga()) {
            this.rejectedOperations++;
            throw this.failure(MiddlewareErrorCode.OVERLOADED, "saga step limit exceeded",
                    Map.of("limit", Integer.toString(this.config.maxStepsPerSaga())));
        }
        if (definition.steps().stream().anyMatch(step -> step.retryPolicy().maxAttempts() > this.config.maxRetryAttempts())) {
            this.rejectedOperations++;
            throw this.failure(MiddlewareErrorCode.OVERLOADED, "saga retry limit exceeded",
                    Map.of("limit", Integer.toString(this.config.maxRetryAttempts())));
        }
        if (this.definitions.containsKey(definition.definitionId())) {
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "saga definition already registered",
                    Map.of("definitionId", definition.definitionId().value()));
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("definitionId", definition.definitionId().value());
        fields.putAll(this.definitionFields(definition));
        this.append(SagaEventType.SAGA_DEFINITION_REGISTERED, fields);
        this.definitions.put(definition.definitionId(), definition);
        this.recoveredDefinitions.put(definition.definitionId(), this.recoverDefinitionMetadata(fields));
        this.recordEvidence("SAGA_DEFINITION_REGISTERED", null, null, "", "allowed", "SAGA_DEFINITION_REGISTERED",
                Map.of("definitionId", definition.definitionId().value()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized SagaStartResult start(final SagaStartRequest request) {
        this.ensureOpen();
        SagaStartRequest safeRequest = request == null ? null : request;
        if (safeRequest == null) {
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "saga start request is required", Map.of());
        }
        if (safeRequest.payload().length > this.config.maxCommandPayloadBytes()) {
            this.rejectedOperations++;
            throw this.failure(MiddlewareErrorCode.OVERLOADED, "saga command payload limit exceeded",
                    Map.of("limit", Integer.toString(this.config.maxCommandPayloadBytes())));
        }
        IdempotencyKey key = safeRequest.options().idempotencyKey();
        SagaDefinition definition = this.definitions.get(safeRequest.definitionId());
        if (definition == null) {
            throw this.failure(MiddlewareErrorCode.NOT_FOUND, "saga definition not found",
                    Map.of("definitionId", safeRequest.definitionId().value()));
        }
        if (key != null && this.idempotency.containsKey(key.value())) {
            SagaExecutionId existing = this.idempotency.get(key.value());
            ExecutionRuntime existingExecution = this.executions.get(existing);
            this.recordEvidence("DUPLICATE_SAGA_START", existing, null, safeRequest.transactionContext().contextId().value(),
                    "duplicate", "DUPLICATE_SAGA_START", Map.of("definitionId", safeRequest.definitionId().value()));
            this.recordEvidence("SAGA_IDEMPOTENCY_DECISION", existing, null, safeRequest.transactionContext().contextId().value(),
                    "duplicate", "DUPLICATE_SAGA_START", Map.of("definitionId", safeRequest.definitionId().value()));
            return new SagaStartResult(existing,
                    existingExecution == null ? SagaExecutionState.STARTING : existingExecution.state,
                    true);
        }
        long active = this.executions.values().stream().filter(execution -> !execution.terminal()).count();
        if (active >= this.config.maxActiveSagas()) {
            this.rejectedOperations++;
            throw this.failure(MiddlewareErrorCode.OVERLOADED, "active saga limit exceeded",
                    Map.of("limit", Integer.toString(this.config.maxActiveSagas())));
        }
        SagaExecutionId executionId = new SagaExecutionId("saga-" + java.util.UUID.randomUUID());
        ExecutionRuntime runtime = new ExecutionRuntime(executionId, safeRequest.definitionId(),
                safeRequest.options(), safeRequest.transactionContext(), safeRequest.payload(), this.config.clock().instant());
        for (SagaStepDefinition step : definition.steps()) {
            runtime.steps.put(step.stepId(), new StepRuntime(step.stepId()));
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("executionId", executionId.value());
        fields.put("definitionId", safeRequest.definitionId().value());
        fields.put("state", SagaExecutionState.RUNNING.name());
        fields.put("payload", Base64.getEncoder().encodeToString(safeRequest.payload()));
        fields.put("payloadSize", Integer.toString(safeRequest.payload().length));
        fields.put("contextId", safeRequest.transactionContext().contextId().value());
        fields.put("correlationId", safeRequest.transactionContext().correlation().correlationContext().correlationId());
        fields.put("idempotencyKey", key == null ? "" : key.value());
        fields.put("startedAt", Long.toString(runtime.startedAt.toEpochMilli()));
        fields.putAll(this.optionFields(safeRequest.options()));
        this.append(SagaEventType.SAGA_STARTED, fields);
        this.executions.put(executionId, runtime);
        if (key != null) {
            this.idempotency.put(key.value(), executionId);
            this.trimIdempotency();
        }
        runtime.state = SagaExecutionState.RUNNING;
        this.recordEvidence("SAGA_STARTED", executionId, null, safeRequest.transactionContext().contextId().value(),
                "allowed", "SAGA_STARTED", Map.of("definitionId", safeRequest.definitionId().value(),
                        "payloadSize", Integer.toString(safeRequest.payload().length)));
        if (key != null) {
            this.recordEvidence("SAGA_IDEMPOTENCY_DECISION", executionId, null, safeRequest.transactionContext().contextId().value(),
                    "allowed", "IDEMPOTENCY_ACCEPTED", Map.of("definitionId", safeRequest.definitionId().value()));
        }
        this.append(SagaEventType.TRANSACTION_CONTEXT_PROPAGATED, Map.of(
                "executionId", executionId.value(),
                "contextId", safeRequest.transactionContext().contextId().value(),
                "correlationId", safeRequest.transactionContext().correlation().correlationContext().correlationId()));
        return new SagaStartResult(executionId, runtime.state, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized SagaExecution runUntilIdle(final SagaExecutionId executionId) {
        this.ensureOpen();
        ExecutionRuntime execution = this.requireExecution(executionId);
        this.evaluateTimeout(execution);
        while (!execution.terminal()) {
            if (execution.cancelRequested) {
                this.applyCancellation(execution);
                break;
            }
            if (execution.state == SagaExecutionState.COMPENSATING || this.hasCompensationTrigger(execution)) {
                execution.state = SagaExecutionState.COMPENSATING;
                this.compensate(execution);
                break;
            }
            SagaDefinition definition = this.definitions.get(execution.definitionId);
            if (definition == null) {
                break;
            }
            Optional<SagaStepDefinition> next = definition.steps().stream()
                    .filter(step -> this.stepRunnable(execution.steps.get(step.stepId())))
                    .findFirst();
            if (next.isEmpty()) {
                if (execution.steps.values().stream().allMatch(step -> step.state == SagaStepState.SUCCEEDED)) {
                    this.complete(execution);
                }
                break;
            }
            this.runStep(execution, next.get());
            this.evaluateTimeout(execution);
            if (execution.state == SagaExecutionState.COMPENSATING) {
                break;
            }
        }
        return this.toPublic(execution);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized SagaCancelResult cancel(final SagaCancelRequest request) {
        this.ensureOpen();
        SagaCancelRequest safeRequest = request == null ? null : request;
        if (safeRequest == null) {
            throw this.failure(MiddlewareErrorCode.INVALID_ARGUMENT, "saga cancel request is required", Map.of());
        }
        ExecutionRuntime execution = this.requireExecution(safeRequest.executionId());
        this.append(SagaEventType.SAGA_CANCEL_REQUESTED, Map.of(
                "executionId", execution.executionId.value(),
                "reason", safeRequest.reason(),
                "compensate", Boolean.toString(safeRequest.compensate())));
        execution.cancelRequested = true;
        execution.cancelCompensate = safeRequest.compensate();
        this.recordEvidence("SAGA_CANCEL_REQUESTED", execution.executionId, null, execution.context.contextId().value(),
                "allowed", safeRequest.reason(), Map.of());
        this.runUntilIdle(execution.executionId);
        return new SagaCancelResult(execution.executionId, execution.state, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void evaluateTimeouts() {
        this.ensureOpen();
        for (ExecutionRuntime execution : this.executions.values()) {
            this.evaluateTimeout(execution);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized SagaStatus status() {
        List<SagaExecution> publicExecutions = this.executions.values().stream()
                .sorted(Comparator.comparing(runtime -> runtime.executionId.value()))
                .map(this::toPublic)
                .toList();
        long active = this.executions.values().stream().filter(execution -> !execution.terminal()).count();
        long runningSteps = this.executions.values().stream()
                .flatMap(execution -> execution.steps.values().stream())
                .filter(step -> step.state == SagaStepState.RUNNING)
                .count();
        long outboxPending = this.outbox.outboxStatus().pending();
        return new SagaStatus(new SagaMetricsSnapshot(active, this.completedSagas, this.failedSagas, this.cancelledSagas,
                this.timedOutSagas, runningSteps, this.compensations, this.retries, this.deadLetters.size(), outboxPending,
                this.recoveryReport.eventsReplayed() == 0L ? 0L : 1L, this.rejectedOperations),
                publicExecutions,
                this.deadLetters.stream().limit(this.config.maxDeadLettersInStatus()).toList(),
                this.evidence.stream().skip(Math.max(0, this.evidence.size() - this.config.maxEvidenceEntries())).toList(),
                this.recoveryReport,
                this.lastError);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized SagaExecution execution(final SagaExecutionId executionId) {
        return this.toPublic(this.requireExecution(executionId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.outbox.close();
            this.log.close();
        }
    }

    private boolean stepRunnable(final StepRuntime step) {
        if (step == null) {
            return false;
        }
        if (step.state == SagaStepState.PENDING) {
            return true;
        }
        return step.state == SagaStepState.RETRY_PENDING && !this.config.clock().instant().isBefore(step.nextAttemptAt);
    }

    private boolean hasCompensationTrigger(final ExecutionRuntime execution) {
        return execution.steps.values().stream()
                .anyMatch(step -> step.state == SagaStepState.FAILED || step.state == SagaStepState.TIMED_OUT);
    }

    private void runStep(final ExecutionRuntime execution, final SagaStepDefinition definition) {
        StepRuntime step = execution.steps.get(definition.stepId());
        if (step == null) {
            return;
        }
        execution.state = SagaExecutionState.STEP_RUNNING;
        step.state = SagaStepState.RUNNING;
        step.attempts++;
        step.startedAt = this.config.clock().instant();
        this.appendStep(SagaEventType.SAGA_STEP_SCHEDULED, execution, step, "SCHEDULED");
        this.recordEvidence("SAGA_STEP_SCHEDULED", execution.executionId, step.stepId, execution.context.contextId().value(),
                "scheduled", "SAGA_STEP_SCHEDULED", Map.of("attempts", Integer.toString(step.attempts)));
        if (!this.recordOutboxIntent(execution, definition, step)) {
            return;
        }
        this.appendStep(SagaEventType.SAGA_STEP_STARTED, execution, step, "STARTED");
        this.recordEvidence("SAGA_STEP_STARTED", execution.executionId, step.stepId, execution.context.contextId().value(),
                "started", "SAGA_STEP_STARTED", Map.of("attempts", Integer.toString(step.attempts)));
        TransactionContext childContext = execution.context.child(TransactionScope.STEP);
        SagaCommandResult result = definition.action().execute(childContext, new SagaCommand(execution.payload, TransactionContextPropagation.metadata(childContext)));
        if (this.applyStepTimeoutIfExpired(execution, definition, step)) {
            return;
        }
        if (result.success()) {
            step.state = SagaStepState.SUCCEEDED;
            step.reasonCode = result.reasonCode();
            this.appendStep(SagaEventType.SAGA_STEP_SUCCEEDED, execution, step, result.reasonCode());
            this.recordEvidence("SAGA_STEP_SUCCEEDED", execution.executionId, step.stepId, execution.context.contextId().value(),
                    "success", result.reasonCode(), Map.of("attempts", Integer.toString(step.attempts)));
            execution.state = SagaExecutionState.RUNNING;
            return;
        }
        step.state = SagaStepState.FAILED;
        step.reasonCode = result.reasonCode();
        this.appendStep(SagaEventType.SAGA_STEP_FAILED, execution, step, result.reasonCode());
        this.recordEvidence("SAGA_STEP_FAILED", execution.executionId, step.stepId, execution.context.contextId().value(),
                result.retryable() ? "retryable" : "failed", result.reasonCode(), Map.of("attempts", Integer.toString(step.attempts)));
        int maxRetries = Math.min(definition.retryPolicy().maxAttempts(), this.config.maxRetryAttempts());
        if (result.retryable() && step.attempts <= maxRetries) {
            step.state = SagaStepState.RETRY_PENDING;
            step.nextAttemptAt = this.config.clock().instant().plus(definition.retryPolicy().delay());
            this.retries++;
            this.appendStep(SagaEventType.SAGA_STEP_RETRY_SCHEDULED, execution, step, result.reasonCode());
            this.recordEvidence("SAGA_STEP_RETRY_SCHEDULED", execution.executionId, step.stepId, execution.context.contextId().value(),
                    "retry", result.reasonCode(), Map.of("attempts", Integer.toString(step.attempts)));
            execution.state = SagaExecutionState.RUNNING;
            return;
        }
        execution.state = SagaExecutionState.COMPENSATING;
        this.compensate(execution);
    }

    private void compensate(final ExecutionRuntime execution) {
        SagaDefinition definition = this.definitions.get(execution.definitionId);
        if (definition == null) {
            return;
        }
        execution.state = SagaExecutionState.COMPENSATING;
        List<SagaStepDefinition> reversed = new ArrayList<>(definition.steps());
        java.util.Collections.reverse(reversed);
        boolean deadLettered = false;
        for (SagaStepDefinition definitionStep : reversed) {
            StepRuntime step = execution.steps.get(definitionStep.stepId());
            if (step == null || step.state != SagaStepState.SUCCEEDED && step.state != SagaStepState.COMPENSATION_FAILED) {
                continue;
            }
            if (definitionStep.compensation() == null) {
                step.state = SagaStepState.COMPENSATED;
                continue;
            }
            if (step.state == SagaStepState.COMPENSATION_FAILED
                    && this.config.clock().instant().isBefore(step.nextAttemptAt)) {
                return;
            }
            step.state = SagaStepState.COMPENSATING;
            step.compensationAttempts++;
            this.compensations++;
            if (step.compensationOperationId == null || step.compensationOperationId.isBlank()) {
                step.compensationOperationId = compensationOperationId(execution.executionId, step.stepId, 1);
            }
            this.appendStep(SagaEventType.SAGA_COMPENSATION_SCHEDULED, execution, step, "SCHEDULED");
            this.recordEvidence("SAGA_COMPENSATION_SCHEDULED", execution.executionId, step.stepId,
                    execution.context.contextId().value(), "scheduled", "SAGA_COMPENSATION_SCHEDULED",
                    Map.of("attempts", Integer.toString(step.compensationAttempts),
                            "operationId", step.compensationOperationId));
            this.appendStep(SagaEventType.SAGA_COMPENSATION_STARTED, execution, step, "STARTED");
            this.recordEvidence("SAGA_COMPENSATION_STARTED", execution.executionId, step.stepId,
                    execution.context.contextId().value(), "started", "SAGA_COMPENSATION_STARTED",
                    Map.of("attempts", Integer.toString(step.compensationAttempts),
                            "operationId", step.compensationOperationId));
            TransactionContext childContext = compensationContext(execution.context.child(TransactionScope.COMPENSATION), step.compensationOperationId);
            SagaCompensationResult result = definitionStep.compensation().compensate(childContext,
                    new SagaCommand(execution.payload, TransactionContextPropagation.metadata(childContext)));
            if (result.success()) {
                step.state = SagaStepState.COMPENSATED;
                step.reasonCode = result.reasonCode();
                this.appendStep(SagaEventType.SAGA_COMPENSATION_SUCCEEDED, execution, step, result.reasonCode());
                this.recordEvidence("SAGA_COMPENSATION_SUCCEEDED", execution.executionId, step.stepId, execution.context.contextId().value(),
                        "success", result.reasonCode(), Map.of("attempts", Integer.toString(step.compensationAttempts),
                                "operationId", step.compensationOperationId));
            } else {
                step.state = SagaStepState.COMPENSATION_FAILED;
                step.reasonCode = result.reasonCode();
                step.nextAttemptAt = Instant.EPOCH;
                this.appendStep(SagaEventType.SAGA_COMPENSATION_FAILED, execution, step, result.reasonCode());
                this.recordEvidence("SAGA_COMPENSATION_FAILED", execution.executionId, step.stepId,
                        execution.context.contextId().value(), "failed", result.reasonCode(),
                        Map.of("attempts", Integer.toString(step.compensationAttempts),
                                "operationId", step.compensationOperationId));
                int maxRetries = Math.min(definitionStep.retryPolicy().maxAttempts(), this.config.maxRetryAttempts());
                if (result.retryable() && step.compensationAttempts <= maxRetries) {
                    step.nextAttemptAt = this.config.clock().instant().plus(definitionStep.retryPolicy().delay());
                    this.retries++;
                    this.appendStep(SagaEventType.SAGA_COMPENSATION_RETRY_SCHEDULED, execution, step, result.reasonCode());
                    this.recordEvidence("SAGA_COMPENSATION_RETRY_SCHEDULED", execution.executionId, step.stepId,
                            execution.context.contextId().value(), "retry", result.reasonCode(),
                            Map.of("attempts", Integer.toString(step.compensationAttempts),
                                    "operationId", step.compensationOperationId,
                                    "nextAttemptAt", Long.toString(step.nextAttemptAt.toEpochMilli())));
                    execution.state = SagaExecutionState.COMPENSATING;
                    return;
                } else {
                    step.state = SagaStepState.DEAD_LETTERED;
                    deadLettered = true;
                    SagaDeadLetterRecord record = new SagaDeadLetterRecord(execution.executionId, step.stepId,
                            result.reasonCode(), this.config.clock().instant(), Map.of("kind", "compensation"));
                    this.addDeadLetter(record);
                    this.appendStep(SagaEventType.SAGA_COMPENSATION_DEAD_LETTERED, execution, step, result.reasonCode());
                    this.recordEvidence("SAGA_COMPENSATION_DEAD_LETTERED", execution.executionId, step.stepId,
                            execution.context.contextId().value(), "failed", result.reasonCode(), Map.of());
                }
            }
        }
        if (deadLettered) {
            execution.state = SagaExecutionState.DEAD_LETTERED;
            this.failedSagas++;
            this.append(SagaEventType.SAGA_FAILED, Map.of("executionId", execution.executionId.value(), "state", execution.state.name()));
            this.recordEvidence("SAGA_FAILED", execution.executionId, null, execution.context.contextId().value(),
                    "failed", "SAGA_FAILED", Map.of("state", execution.state.name()));
            this.retainTerminalExecutions();
            return;
        }
        execution.state = execution.cancelRequested ? SagaExecutionState.CANCELLED : SagaExecutionState.COMPENSATED;
        if (execution.cancelRequested) {
            this.cancelledSagas++;
            this.append(SagaEventType.SAGA_CANCELLED, Map.of("executionId", execution.executionId.value(), "state", execution.state.name()));
            this.recordEvidence("SAGA_CANCELLED", execution.executionId, null, execution.context.contextId().value(),
                    "cancelled", "SAGA_CANCELLED", Map.of("state", execution.state.name()));
        } else {
            this.failedSagas++;
            this.append(SagaEventType.SAGA_FAILED, Map.of("executionId", execution.executionId.value(), "state", execution.state.name()));
            this.recordEvidence("SAGA_FAILED", execution.executionId, null, execution.context.contextId().value(),
                    "failed", "SAGA_FAILED", Map.of("state", execution.state.name()));
        }
        this.retainTerminalExecutions();
    }

    private void applyCancellation(final ExecutionRuntime execution) {
        execution.state = SagaExecutionState.CANCELLING;
        if (execution.cancelCompensate || execution.options.cancellationPolicy().compensateOnCancel()) {
            this.compensate(execution);
            return;
        }
        for (StepRuntime step : execution.steps.values()) {
            if (step.state == SagaStepState.PENDING || step.state == SagaStepState.RETRY_PENDING) {
                step.state = SagaStepState.CANCELLED;
            }
        }
        execution.state = SagaExecutionState.CANCELLED;
        this.cancelledSagas++;
        this.append(SagaEventType.SAGA_CANCELLED, Map.of("executionId", execution.executionId.value(), "state", execution.state.name()));
        this.recordEvidence("SAGA_CANCELLED", execution.executionId, null, execution.context.contextId().value(),
                "cancelled", "SAGA_CANCELLED", Map.of("state", execution.state.name()));
        this.retainTerminalExecutions();
    }

    private void complete(final ExecutionRuntime execution) {
        execution.state = SagaExecutionState.COMPLETED;
        this.completedSagas++;
        this.append(SagaEventType.SAGA_COMPLETED, Map.of("executionId", execution.executionId.value(), "state", execution.state.name()));
        this.recordEvidence("SAGA_COMPLETED", execution.executionId, null, execution.context.contextId().value(),
                "success", "SAGA_COMPLETED", Map.of());
        this.retainTerminalExecutions();
    }

    private void evaluateTimeout(final ExecutionRuntime execution) {
        if (execution.terminal()) {
            return;
        }
        Instant now = this.config.clock().instant();
        SagaDefinition definition = this.definitions.get(execution.definitionId);
        if (definition != null) {
            for (SagaStepDefinition stepDefinition : definition.steps()) {
                StepRuntime step = execution.steps.get(stepDefinition.stepId());
                if (step != null && step.state == SagaStepState.RUNNING
                        && !stepDefinition.timeoutPolicy().stepTimeout().isZero()
                        && !now.isBefore(step.startedAt.plus(stepDefinition.timeoutPolicy().stepTimeout()))) {
                    this.applyStepTimeout(execution, step);
                    return;
                }
            }
        }
        Duration timeout = execution.options.timeoutPolicy().sagaTimeout();
        if (!timeout.isZero() && !now.isBefore(execution.startedAt.plus(timeout))) {
            execution.state = SagaExecutionState.TIMED_OUT;
            this.timedOutSagas++;
            this.append(SagaEventType.SAGA_TIMED_OUT, Map.of("executionId", execution.executionId.value(), "state", execution.state.name()));
            this.recordEvidence("SAGA_TIMED_OUT", execution.executionId, null, execution.context.contextId().value(),
                    "failed", "SAGA_TIMEOUT", Map.of());
            this.retainTerminalExecutions();
        }
    }

    private boolean applyStepTimeoutIfExpired(
            final ExecutionRuntime execution,
            final SagaStepDefinition definition,
            final StepRuntime step) {
        Duration timeout = definition.timeoutPolicy().stepTimeout();
        if (timeout.isZero()) {
            return false;
        }
        Instant now = this.config.clock().instant();
        if (now.isBefore(step.startedAt.plus(timeout))) {
            return false;
        }
        this.applyStepTimeout(execution, step);
        return true;
    }

    private void applyStepTimeout(final ExecutionRuntime execution, final StepRuntime step) {
        step.state = SagaStepState.TIMED_OUT;
        step.reasonCode = "STEP_TIMEOUT";
        execution.state = SagaExecutionState.COMPENSATING;
        this.appendStep(SagaEventType.SAGA_STEP_TIMED_OUT, execution, step, "STEP_TIMEOUT");
        this.recordEvidence("SAGA_STEP_TIMED_OUT", execution.executionId, step.stepId, execution.context.contextId().value(),
                "failed", "STEP_TIMEOUT", Map.of("attempts", Integer.toString(step.attempts)));
        this.compensate(execution);
    }

    private boolean recordOutboxIntent(
            final ExecutionRuntime execution,
            final SagaStepDefinition definition,
            final StepRuntime step) {
        String outboxKey = outboxKey(execution.executionId, definition.stepId());
        IdempotencyKey idempotencyKey = new IdempotencyKey(execution.executionId.value() + ":" + definition.stepId().value());
        DedupeKey dedupeKey = new DedupeKey("dedupe:" + execution.executionId.value() + ":" + definition.stepId().value());
        if (this.outboxPublished.contains(outboxKey)) {
            this.recordEvidence("SAGA_OUTBOX_DUPLICATE_SUPPRESSED", execution.executionId, definition.stepId(),
                    execution.context.contextId().value(), "suppressed", "OUTBOX_DUPLICATE_SUPPRESSED",
                    Map.of("idempotencyKey", idempotencyKey.value()));
            return true;
        }
        if (!this.outboxIntents.contains(outboxKey)) {
            if (this.outbox.outboxStatus().pending() >= this.config.maxPendingOutboxCommands()) {
                this.rejectedOperations++;
                throw this.failure(MiddlewareErrorCode.OVERLOADED, "saga outbox pending limit exceeded",
                        Map.of("limit", Integer.toString(this.config.maxPendingOutboxCommands())));
            }
            QueueMessage message = new QueueMessage(new MessageHeader(Map.of(
                    "sagaExecutionId", execution.executionId.value(),
                    "sagaStepId", definition.stepId().value(),
                    "transactionContextId", execution.context.contextId().value())),
                    execution.payload);
            this.outbox.record(COMMAND_QUEUE, message, new EnqueueOptions(null, null, null, idempotencyKey, dedupeKey, Instant.EPOCH));
            this.rememberOutboxMarker(this.outboxIntents, outboxKey);
            this.recordEvidence("SAGA_OUTBOX_INTENT_RECORDED", execution.executionId, definition.stepId(),
                    execution.context.contextId().value(), "recorded", "OUTBOX_INTENT_RECORDED",
                    Map.of("idempotencyKey", idempotencyKey.value(), "payloadSize", Integer.toString(execution.payload.length)));
        }
        SagaOutboxExecutionMode mode = this.outboxExecutionMode;
        if (mode == SagaOutboxExecutionMode.STOP_AFTER_INTENT) {
            throw this.failure(MiddlewareErrorCode.UNAVAILABLE, "saga outbox stopped after intent",
                    Map.of("stage", "after-intent"));
        }
        if (mode == SagaOutboxExecutionMode.FAIL_RELAY || mode == SagaOutboxExecutionMode.EXHAUST_RELAY) {
            this.rememberOutboxMarker(this.outboxFailures, outboxKey);
            this.recordEvidence("SAGA_OUTBOX_RELAY_FAILED", execution.executionId, definition.stepId(),
                    execution.context.contextId().value(), "failed", "OUTBOX_RELAY_FAILED",
                    Map.of("idempotencyKey", idempotencyKey.value(), "attempts", "1"));
            if (mode == SagaOutboxExecutionMode.EXHAUST_RELAY) {
                step.state = SagaStepState.DEAD_LETTERED;
                step.reasonCode = "OUTBOX_RELAY_DEAD_LETTERED";
                this.rememberOutboxMarker(this.outboxDeadLetters, outboxKey);
                this.addDeadLetter(new SagaDeadLetterRecord(execution.executionId, step.stepId,
                        "OUTBOX_RELAY_DEAD_LETTERED", this.config.clock().instant(), Map.of("kind", "outbox")));
                this.appendStep(SagaEventType.SAGA_STEP_DEAD_LETTERED, execution, step, "OUTBOX_RELAY_DEAD_LETTERED");
                this.recordEvidence("SAGA_OUTBOX_DEAD_LETTERED", execution.executionId, definition.stepId(),
                        execution.context.contextId().value(), "failed", "OUTBOX_RELAY_DEAD_LETTERED",
                        Map.of("idempotencyKey", idempotencyKey.value()));
                execution.state = SagaExecutionState.DEAD_LETTERED;
                this.failedSagas++;
                this.append(SagaEventType.SAGA_FAILED,
                        Map.of("executionId", execution.executionId.value(), "state", execution.state.name()));
                this.recordEvidence("SAGA_FAILED", execution.executionId, null, execution.context.contextId().value(),
                        "failed", "OUTBOX_RELAY_DEAD_LETTERED", Map.of("state", execution.state.name()));
                return false;
            }
            throw this.failure(MiddlewareErrorCode.UNAVAILABLE, "saga outbox relay failed",
                    Map.of("stage", "relay", "attempts", "1"));
        }
        int published = this.outbox.relayPending();
        if (mode == SagaOutboxExecutionMode.STOP_AFTER_RELAY) {
            throw this.failure(MiddlewareErrorCode.UNAVAILABLE, "saga outbox stopped after relay",
                    Map.of("stage", "after-relay"));
        }
        this.rememberOutboxMarker(this.outboxPublished, outboxKey);
        this.recordEvidence("SAGA_OUTBOX_PUBLISHED", execution.executionId, definition.stepId(),
                execution.context.contextId().value(), "published", "OUTBOX_PUBLISHED",
                Map.of("idempotencyKey", idempotencyKey.value(), "relayCount", Integer.toString(published)));
        if (mode == SagaOutboxExecutionMode.STOP_AFTER_PUBLISH) {
            throw this.failure(MiddlewareErrorCode.UNAVAILABLE, "saga outbox stopped after publish",
                    Map.of("stage", "after-publish"));
        }
        return true;
    }

    private void recover() {
        this.log.recover(RecoveryOptions.defaults());
        this.definitions.clear();
        this.recoveredDefinitions.clear();
        this.executions.clear();
        this.idempotency.clear();
        this.outboxIntents.clear();
        this.outboxPublished.clear();
        this.outboxFailures.clear();
        this.outboxDeadLetters.clear();
        this.evidence.clear();
        this.deadLetters.clear();
        this.durableEventCount = 0L;
        this.eventsSinceCheckpoint = 0L;
        long events = 0L;
        long tailEvents = 0L;
        this.recordEvidence("SAGA_RECOVERY_STARTED", null, null, "", "started", "RECOVERY_STARTED", Map.of());
        long normalizationEvents = 0L;
        try {
            this.recovering = true;
            RecoveryStart recoveryStart = this.recoveryStart();
            long maxRecordsToReplay = recoveryStart.checkpointExpected()
                    ? (long) CHECKPOINT_LOOKUP_RECORD_LIMIT
                    : (long) MAX_RECOVERY_TAIL_EVENTS;
            boolean firstRecord = true;
            try (LogCursor cursor = this.log.openCursor(EVENT_STREAM, recoveryStart.offset(), new CursorOptions(true))) {
            Optional<LogRecord> next = cursor.next();
            while (next.isPresent()) {
                if (events >= maxRecordsToReplay) {
                    throw new IllegalStateException("saga recovery tail bound exceeded");
                }
                byte[] eventPayload = next.get().payload();
                this.codec.requireCheckpointBodyWithinLimit(eventPayload, this.config.maxCheckpointBytes());
                SagaEvent event = this.codec.decode(eventPayload);
                if (firstRecord && recoveryStart.checkpointExpected() && event.type() != SagaEventType.SAGA_CHECKPOINT) {
                    throw new IllegalStateException("saga checkpoint recovery start is invalid");
                }
                this.applyRecovered(event);
                if (event.type() != SagaEventType.SAGA_CHECKPOINT) {
                    this.durableEventCount++;
                    tailEvents++;
                    if (tailEvents > MAX_RECOVERY_TAIL_EVENTS) {
                        throw new IllegalStateException("saga recovery tail bound exceeded");
                    }
                } else {
                    tailEvents = 0L;
                }
                events++;
                firstRecord = false;
                next = cursor.next();
            }
            }
            long eventsSinceCheckpointBeforeNormalization = this.eventsSinceCheckpoint;
            List<Map<String, String>> diagnostics = this.normalizeRecoveredActiveState();
            normalizationEvents = this.eventsSinceCheckpoint - eventsSinceCheckpointBeforeNormalization;
            this.recoveryReport = new SagaRecoveryReport(events, this.recoveredDefinitions.size(),
                    this.executions.size(), this.deadLetters.size(), false, diagnostics);
            this.recordEvidence("SAGA_RECOVERY_COMPLETED", null, null, "", "success", "RECOVERY_COMPLETED",
                    Map.of("eventsReplayed", Long.toString(events),
                            "diagnostics", Integer.toString(diagnostics.size())));
        } catch (RuntimeException exception) {
            this.recovering = false;
            SagaException safe = exception instanceof SagaException sagaException
                    ? sagaException
                    : SagaEventCodec.failure("recover", "SAGA_EVENT", SagaEventCodec.SCHEMA_VERSION, "recovery failed");
            this.recoveryReport = new SagaRecoveryReport(events, this.recoveredDefinitions.size(),
                    this.executions.size(), this.deadLetters.size(), true, List.of(safe.remoteError().details()));
            this.lastError = "RECOVERY_FAILED";
            this.recordEvidence("SAGA_RECOVERY_FAILED", null, null, "", "failed", "RECOVERY_FAILED",
                    safe.remoteError().details());
            throw safe;
        }
        this.recovering = false;
        this.eventsSinceCheckpoint = tailEvents + normalizationEvents;
        if (this.eventsSinceCheckpoint >= MAX_RECOVERY_TAIL_EVENTS) {
            this.appendCheckpoint();
        }
    }

    private void applyRecovered(final SagaEvent event) {
        Map<String, String> fields = event.fields();
        switch (event.type()) {
            case SAGA_DEFINITION_REGISTERED -> {
                SagaDefinitionId definitionId = new SagaDefinitionId(fields.get("definitionId"));
                this.recoveredDefinitions.put(definitionId, this.recoverDefinitionMetadata(fields));
            }
            case SAGA_STARTED -> {
                SagaExecutionId executionId = new SagaExecutionId(fields.get("executionId"));
                SagaDefinitionId definitionId = new SagaDefinitionId(fields.get("definitionId"));
                byte[] payload = Base64.getDecoder().decode(fields.getOrDefault("payload", ""));
                TransactionContext context = new TransactionContext(null, null, null, null, null, null, null, false, null,
                        Map.of("recoveredContextId", fields.getOrDefault("contextId", "")));
                ExecutionRuntime execution = new ExecutionRuntime(executionId, definitionId, this.recoverOptions(fields), context, payload,
                        Instant.ofEpochMilli(parseLong(fields, "startedAt", event.type().name())));
                RecoveredDefinitionMetadata metadata = this.recoveredDefinitions.get(definitionId);
                List<SagaStepId> steps = metadata == null ? List.of() : metadata.stepIds();
                for (SagaStepId step : steps) {
                    execution.steps.put(step, new StepRuntime(step));
                }
                execution.state = this.executionState(fields.get("state"), event.type().name());
                this.executions.put(executionId, execution);
                String idempotencyKey = fields.getOrDefault("idempotencyKey", "");
                if (!idempotencyKey.isBlank()) {
                    this.idempotency.put(idempotencyKey, executionId);
                }
            }
            case SAGA_STEP_STARTED -> this.recoverStep(fields, SagaStepState.RUNNING);
            case SAGA_STEP_SUCCEEDED -> this.recoverStep(fields, SagaStepState.SUCCEEDED);
            case SAGA_STEP_FAILED -> {
                this.recoverStep(fields, SagaStepState.FAILED);
                this.requireRecovered(fields.get("executionId")).state = SagaExecutionState.COMPENSATING;
            }
            case SAGA_STEP_TIMED_OUT -> {
                this.recoverStep(fields, SagaStepState.TIMED_OUT);
                this.requireRecovered(fields.get("executionId")).state = SagaExecutionState.COMPENSATING;
            }
            case SAGA_STEP_RETRY_SCHEDULED -> {
                StepRuntime step = this.recoverStep(fields, SagaStepState.RETRY_PENDING);
                step.nextAttemptAt = Instant.ofEpochMilli(parseOptionalLong(fields, "nextAttemptAt",
                        Long.toString(this.config.clock().instant().toEpochMilli())));
                this.requireRecovered(fields.get("executionId")).state = SagaExecutionState.RUNNING;
                this.retries++;
            }
            case SAGA_STEP_DEAD_LETTERED -> {
                StepRuntime step = this.recoverStep(fields, SagaStepState.DEAD_LETTERED);
                this.addDeadLetter(new SagaDeadLetterRecord(new SagaExecutionId(fields.get("executionId")),
                        step.stepId, fields.getOrDefault("reasonCode", "STEP_DEAD_LETTERED"), this.config.clock().instant(), Map.of()));
            }
            case SAGA_COMPENSATION_SCHEDULED, SAGA_COMPENSATION_STARTED -> {
                this.recoverStep(fields, SagaStepState.COMPENSATING);
                this.requireRecovered(fields.get("executionId")).state = SagaExecutionState.COMPENSATING;
            }
            case SAGA_COMPENSATION_SUCCEEDED -> this.recoverStep(fields, SagaStepState.COMPENSATED);
            case SAGA_COMPENSATION_FAILED -> this.recoverStep(fields, SagaStepState.COMPENSATION_FAILED);
            case SAGA_COMPENSATION_RETRY_SCHEDULED -> {
                StepRuntime step = this.recoverStep(fields, SagaStepState.COMPENSATION_FAILED);
                step.nextAttemptAt = Instant.ofEpochMilli(parseLong(fields, "nextAttemptAt", event.type().name()));
                this.requireRecovered(fields.get("executionId")).state = SagaExecutionState.COMPENSATING;
                this.retries++;
            }
            case SAGA_COMPENSATION_DEAD_LETTERED -> {
                StepRuntime step = this.recoverStep(fields, SagaStepState.DEAD_LETTERED);
                this.addDeadLetter(new SagaDeadLetterRecord(new SagaExecutionId(fields.get("executionId")),
                        step.stepId, fields.getOrDefault("reasonCode", "COMPENSATION_DEAD_LETTERED"), this.config.clock().instant(), Map.of()));
            }
            case SAGA_CANCEL_REQUESTED -> this.requireRecovered(fields.get("executionId")).cancelRequested = true;
            case SAGA_CANCELLED -> {
                this.requireRecovered(fields.get("executionId")).state = SagaExecutionState.CANCELLED;
                this.cancelledSagas++;
            }
            case SAGA_TIMED_OUT -> {
                this.requireRecovered(fields.get("executionId")).state = SagaExecutionState.TIMED_OUT;
                this.timedOutSagas++;
            }
            case SAGA_COMPLETED -> {
                this.requireRecovered(fields.get("executionId")).state = SagaExecutionState.COMPLETED;
                this.completedSagas++;
            }
            case SAGA_FAILED -> {
                this.requireRecovered(fields.get("executionId")).state =
                        this.executionState(fields.getOrDefault("state", "FAILED"), event.type().name());
                this.failedSagas++;
            }
            case SAGA_AUDIT_RECORDED -> {
                this.rememberOutboxEvidence(fields);
                this.rememberCompensationEvidence(fields);
                this.evidence.add(new SagaEvidence(this.config.clock().instant(),
                        fields.getOrDefault("action", "SAGA_AUDIT_RECORDED"),
                        new SagaExecutionId(fields.get("executionId")),
                        fields.get("stepId") == null || fields.get("stepId").isBlank() ? null : new SagaStepId(fields.get("stepId")),
                        fields.getOrDefault("transactionContextId", ""),
                        fields.getOrDefault("decision", "recorded"),
                        fields.getOrDefault("reasonCode", "SAGA_AUDIT_RECORDED"),
                        fields));
                while (this.evidence.size() > this.config.maxEvidenceEntries()) {
                    this.evidence.remove(0);
                }
            }
            case SAGA_CHECKPOINT -> this.restoreCheckpoint(event);
            default -> {
                // Scheduling and context markers are safe evidence-only events.
            }
        }
    }

    private List<Map<String, String>> normalizeRecoveredActiveState() {
        List<Map<String, String>> diagnostics = new ArrayList<>();
        for (ExecutionRuntime execution : this.executions.values()) {
            if (execution.terminal()) {
                diagnostics.add(Map.of("executionId", execution.executionId.value(),
                        "recoveryState", "terminal", "state", execution.state.name()));
                continue;
            }
            if (!this.definitions.containsKey(execution.definitionId)) {
                diagnostics.add(Map.of("executionId", execution.executionId.value(),
                        "recoveryState", "waiting-for-definition", "definitionId", execution.definitionId.value()));
            }
            boolean normalizedCompensation = false;
            for (StepRuntime step : execution.steps.values()) {
                if (step.state == SagaStepState.RUNNING) {
                    step.state = SagaStepState.RETRY_PENDING;
                    step.nextAttemptAt = this.config.clock().instant();
                    step.reasonCode = "RECOVERED_RUNNING_RETRY";
                    execution.state = SagaExecutionState.RUNNING;
                    this.appendStep(SagaEventType.SAGA_STEP_RETRY_SCHEDULED, execution, step, step.reasonCode);
                    this.recordEvidence("SAGA_RECOVERY_STATE_NORMALIZED", execution.executionId, step.stepId,
                            execution.context.contextId().value(), "retry", step.reasonCode,
                            Map.of("from", "RUNNING", "to", "RETRY_PENDING"));
                    diagnostics.add(Map.of("executionId", execution.executionId.value(),
                            "stepId", step.stepId.value(), "recoveryState", "recovered-running-retry"));
                } else if (step.state == SagaStepState.COMPENSATION_FAILED && compensationRetryScheduled(step)) {
                    execution.state = SagaExecutionState.COMPENSATING;
                    diagnostics.add(Map.of("executionId", execution.executionId.value(),
                            "stepId", step.stepId.value(), "recoveryState", "recovered-compensation-retry"));
                    normalizedCompensation = true;
                } else if (step.state == SagaStepState.COMPENSATING || step.state == SagaStepState.COMPENSATION_FAILED) {
                    step.state = SagaStepState.DEAD_LETTERED;
                    step.reasonCode = "RECOVERED_COMPENSATION_MANUAL_INTERVENTION";
                    execution.state = SagaExecutionState.DEAD_LETTERED;
                    this.addDeadLetter(new SagaDeadLetterRecord(execution.executionId, step.stepId,
                            step.reasonCode, this.config.clock().instant(), Map.of("kind", "compensation", "manualIntervention", "true")));
                    this.appendStep(SagaEventType.SAGA_COMPENSATION_DEAD_LETTERED, execution, step, step.reasonCode);
                    this.append(SagaEventType.SAGA_FAILED, Map.of("executionId", execution.executionId.value(), "state", execution.state.name()));
                    this.recordEvidence("SAGA_RECOVERY_STATE_NORMALIZED", execution.executionId, step.stepId,
                            execution.context.contextId().value(), "manual-intervention", step.reasonCode,
                            Map.of("from", "COMPENSATING", "to", "DEAD_LETTERED"));
                    diagnostics.add(Map.of("executionId", execution.executionId.value(),
                            "stepId", step.stepId.value(), "recoveryState", "recovered-compensation-manual-intervention"));
                    normalizedCompensation = true;
                } else if (!normalizedCompensation && (step.state == SagaStepState.FAILED || step.state == SagaStepState.TIMED_OUT)) {
                    execution.state = SagaExecutionState.COMPENSATING;
                    diagnostics.add(Map.of("executionId", execution.executionId.value(),
                            "stepId", step.stepId.value(), "recoveryState", "compensation-required"));
                }
            }
            if (!normalizedCompensation && (execution.state == SagaExecutionState.COMPENSATING || hasAmbiguousCompensationTrigger(execution)
                    || (!this.definitions.containsKey(execution.definitionId) && firstCompensationCandidate(execution) != null))) {
                StepRuntime step = firstCompensationCandidate(execution);
                if (step != null) {
                    step.state = SagaStepState.DEAD_LETTERED;
                    step.reasonCode = "RECOVERED_COMPENSATION_MANUAL_INTERVENTION";
                    execution.state = SagaExecutionState.DEAD_LETTERED;
                    this.addDeadLetter(new SagaDeadLetterRecord(execution.executionId, step.stepId,
                            step.reasonCode, this.config.clock().instant(), Map.of("kind", "compensation", "manualIntervention", "true")));
                    this.appendStep(SagaEventType.SAGA_COMPENSATION_DEAD_LETTERED, execution, step, step.reasonCode);
                    this.append(SagaEventType.SAGA_FAILED, Map.of("executionId", execution.executionId.value(), "state", execution.state.name()));
                    this.recordEvidence("SAGA_RECOVERY_STATE_NORMALIZED", execution.executionId, step.stepId,
                            execution.context.contextId().value(), "manual-intervention", step.reasonCode,
                            Map.of("from", "COMPENSATING", "to", "DEAD_LETTERED"));
                    diagnostics.add(Map.of("executionId", execution.executionId.value(),
                            "stepId", step.stepId.value(), "recoveryState", "recovered-compensation-manual-intervention"));
                }
            }
        }
        this.retainTerminalExecutions();
        return diagnostics;
    }

    private static StepRuntime firstCompensationCandidate(final ExecutionRuntime execution) {
        for (StepRuntime step : execution.steps.values()) {
            if (step.state == SagaStepState.SUCCEEDED
                    || step.state == SagaStepState.COMPENSATION_FAILED
                    || step.state == SagaStepState.COMPENSATING) {
                return step;
            }
        }
        return null;
    }

    private static boolean compensationRetryScheduled(final StepRuntime step) {
        return step.nextAttemptAt != null && step.nextAttemptAt.isAfter(Instant.EPOCH);
    }

    private static boolean hasAmbiguousCompensationTrigger(final ExecutionRuntime execution) {
        boolean completedWork = false;
        boolean failureTrigger = false;
        for (StepRuntime step : execution.steps.values()) {
            if (step.state == SagaStepState.SUCCEEDED || step.state == SagaStepState.COMPENSATING
                    || step.state == SagaStepState.COMPENSATION_FAILED) {
                completedWork = true;
            }
            if (step.state == SagaStepState.FAILED || step.state == SagaStepState.TIMED_OUT
                    || step.state == SagaStepState.COMPENSATING || step.state == SagaStepState.COMPENSATION_FAILED) {
                failureTrigger = true;
            }
        }
        return completedWork && failureTrigger;
    }

    private void rememberOutboxEvidence(final Map<String, String> fields) {
        String action = fields.getOrDefault("action", "");
        String executionId = fields.getOrDefault("executionId", "");
        String stepId = fields.getOrDefault("stepId", "");
        if (executionId.isBlank() || stepId.isBlank()) {
            return;
        }
        String outboxKey = executionId + ":" + stepId;
        switch (action) {
            case "SAGA_OUTBOX_INTENT_RECORDED" -> this.rememberOutboxMarker(this.outboxIntents, outboxKey);
            case "SAGA_OUTBOX_PUBLISHED" -> this.rememberOutboxMarker(this.outboxPublished, outboxKey);
            case "SAGA_OUTBOX_RELAY_FAILED" -> this.rememberOutboxMarker(this.outboxFailures, outboxKey);
            case "SAGA_OUTBOX_DEAD_LETTERED" -> this.rememberOutboxMarker(this.outboxDeadLetters, outboxKey);
            default -> {
                // Not an outbox phase marker.
            }
        }
    }

    private void rememberCompensationEvidence(final Map<String, String> fields) {
        String action = fields.getOrDefault("action", "");
        String executionId = fields.getOrDefault("executionId", "");
        String stepId = fields.getOrDefault("stepId", "");
        if (executionId.isBlank() || stepId.isBlank()) {
            return;
        }
        if ("SAGA_COMPENSATION_SCHEDULED".equals(action) || "SAGA_COMPENSATION_STARTED".equals(action)) {
            ExecutionRuntime execution = this.requireRecovered(executionId);
            StepRuntime step = execution.steps.computeIfAbsent(new SagaStepId(stepId), StepRuntime::new);
            step.state = SagaStepState.COMPENSATING;
            step.reasonCode = action;
            execution.state = SagaExecutionState.COMPENSATING;
            if (step.compensationAttempts == 0) {
                step.compensationAttempts = 1;
            }
        }
    }

    private StepRuntime recoverStep(final Map<String, String> fields, final SagaStepState state) {
        ExecutionRuntime execution = this.requireRecovered(fields.get("executionId"));
        SagaStepId stepId = new SagaStepId(fields.get("stepId"));
        StepRuntime step = execution.steps.computeIfAbsent(stepId, StepRuntime::new);
        step.state = state;
        step.attempts = (int) parseLong(fields, "attempts", "SAGA_STEP");
        step.compensationAttempts = (int) parseLong(fields, "compensationAttempts", "SAGA_STEP");
        step.startedAt = Instant.ofEpochMilli(parseLong(fields, "startedAt", "SAGA_STEP"));
        step.nextAttemptAt = Instant.ofEpochMilli(parseOptionalLong(fields, "nextAttemptAt", Long.toString(step.nextAttemptAt.toEpochMilli())));
        step.reasonCode = fields.getOrDefault("reasonCode", state.name());
        String operationId = fields.getOrDefault("operationId", "");
        if (!operationId.isBlank()) {
            step.compensationOperationId = operationId;
        }
        return step;
    }

    private Map<String, String> definitionFields(final SagaDefinition definition) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("stepCount", Integer.toString(definition.steps().size()));
        fields.put("steps", String.join(",", definition.steps().stream().map(step -> step.stepId().value()).toList()));
        for (int index = 0; index < definition.steps().size(); index++) {
            SagaStepDefinition step = definition.steps().get(index);
            String prefix = "step." + index + ".";
            fields.put(prefix + "id", step.stepId().value());
            fields.put(prefix + "type", step.type().name());
            fields.put(prefix + "retryMaxAttempts", Integer.toString(step.retryPolicy().maxAttempts()));
            fields.put(prefix + "retryDelayMillis", Long.toString(step.retryPolicy().delay().toMillis()));
            fields.put(prefix + "sagaTimeoutMillis", Long.toString(step.timeoutPolicy().sagaTimeout().toMillis()));
            fields.put(prefix + "stepTimeoutMillis", Long.toString(step.timeoutPolicy().stepTimeout().toMillis()));
            fields.put(prefix + "hasCompensation", Boolean.toString(step.compensation() != null));
        }
        return fields;
    }

    private RecoveredDefinitionMetadata recoverDefinitionMetadata(final Map<String, String> fields) {
        long count = parseLong(fields, "stepCount", "SAGA_DEFINITION_REGISTERED");
        List<SagaStepId> stepIds = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String stepId = fields.get("step." + index + ".id");
            if (stepId == null || stepId.isBlank()) {
                throw SagaEventCodec.failure("apply", "SAGA_DEFINITION_REGISTERED", SagaEventCodec.SCHEMA_VERSION,
                        "missing required field: step." + index + ".id");
            }
            stepIds.add(new SagaStepId(stepId));
            parseLong(fields, "step." + index + ".retryMaxAttempts", "SAGA_DEFINITION_REGISTERED");
            parseLong(fields, "step." + index + ".retryDelayMillis", "SAGA_DEFINITION_REGISTERED");
            parseLong(fields, "step." + index + ".sagaTimeoutMillis", "SAGA_DEFINITION_REGISTERED");
            parseLong(fields, "step." + index + ".stepTimeoutMillis", "SAGA_DEFINITION_REGISTERED");
        }
        return new RecoveredDefinitionMetadata(stepIds);
    }

    private Map<String, String> optionFields(final SagaOptions options) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("option.retryMaxAttempts", Integer.toString(options.retryPolicy().maxAttempts()));
        fields.put("option.retryDelayMillis", Long.toString(options.retryPolicy().delay().toMillis()));
        fields.put("option.sagaTimeoutMillis", Long.toString(options.timeoutPolicy().sagaTimeout().toMillis()));
        fields.put("option.stepTimeoutMillis", Long.toString(options.timeoutPolicy().stepTimeout().toMillis()));
        fields.put("option.compensateOnCancel", Boolean.toString(options.cancellationPolicy().compensateOnCancel()));
        fields.put("option.cancelRunningStep", Boolean.toString(options.cancellationPolicy().cancelRunningStep()));
        return fields;
    }

    private SagaOptions recoverOptions(final Map<String, String> fields) {
        SagaRetryPolicy retryPolicy = new SagaRetryPolicy(
                (int) parseLong(fields, "option.retryMaxAttempts", "SAGA_STARTED"),
                Duration.ofMillis(parseLong(fields, "option.retryDelayMillis", "SAGA_STARTED")));
        SagaTimeoutPolicy timeoutPolicy = new SagaTimeoutPolicy(
                Duration.ofMillis(parseLong(fields, "option.sagaTimeoutMillis", "SAGA_STARTED")),
                Duration.ofMillis(parseLong(fields, "option.stepTimeoutMillis", "SAGA_STARTED")));
        SagaCancellationPolicy cancellationPolicy = new SagaCancellationPolicy(
                Boolean.parseBoolean(fields.getOrDefault("option.compensateOnCancel", "true")),
                Boolean.parseBoolean(fields.getOrDefault("option.cancelRunningStep", "false")));
        String idempotencyKey = fields.getOrDefault("idempotencyKey", "");
        return new SagaOptions(idempotencyKey.isBlank() ? null : new IdempotencyKey(idempotencyKey),
                retryPolicy, timeoutPolicy, cancellationPolicy);
    }

    private SagaExecutionState executionState(final String value, final String eventType) {
        try {
            return SagaExecutionState.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw SagaEventCodec.failure("apply", eventType, SagaEventCodec.SCHEMA_VERSION,
                    "malformed execution state");
        }
    }

    private ExecutionRuntime requireRecovered(final String value) {
        ExecutionRuntime execution = this.executions.get(new SagaExecutionId(value));
        if (execution == null) {
            throw SagaEventCodec.failure("apply", "SAGA_EVENT", SagaEventCodec.SCHEMA_VERSION, "unknown saga execution");
        }
        return execution;
    }

    private static long parseLong(final Map<String, String> fields, final String key, final String eventType) {
        try {
            String value = fields.get(key);
            if (value == null || value.isBlank()) {
                throw SagaEventCodec.failure("apply", eventType, SagaEventCodec.SCHEMA_VERSION,
                        "missing required field: " + key);
            }
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw SagaEventCodec.failure("apply", eventType, SagaEventCodec.SCHEMA_VERSION, "malformed numeric field: " + key);
        }
    }

    private static long parseOptionalLong(final Map<String, String> fields, final String key, final String defaultValue) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            return parseLong(defaultValue);
        }
        return parseLong(value);
    }

    private static long parseLong(final String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("malformed saga checkpoint number", exception);
        }
    }

    private static int parseInt(final String value) {
        long parsed = parseLong(value);
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("saga checkpoint integer is out of range");
        }
        return (int) parsed;
    }

    private void appendStep(
            final SagaEventType type,
            final ExecutionRuntime execution,
            final StepRuntime step,
            final String reasonCode) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("executionId", execution.executionId.value());
        fields.put("stepId", step.stepId.value());
        fields.put("attempts", Integer.toString(step.attempts));
        fields.put("compensationAttempts", Integer.toString(step.compensationAttempts));
        fields.put("startedAt", Long.toString(step.startedAt.toEpochMilli()));
        fields.put("nextAttemptAt", Long.toString(step.nextAttemptAt.toEpochMilli()));
        fields.put("reasonCode", reasonCode == null ? type.name() : reasonCode);
        if (type.name().startsWith("SAGA_COMPENSATION_")) {
            if (step.compensationOperationId == null || step.compensationOperationId.isBlank()) {
                step.compensationOperationId = compensationOperationId(execution.executionId, step.stepId, 1);
            }
            fields.put("operationId", step.compensationOperationId);
        }
        this.append(type, fields);
    }

    private void append(final SagaEventType type, final Map<String, String> fields) {
        this.ensureEventCanAppend();
        this.appendEncodedEvent(type, fields);
        if (type != SagaEventType.SAGA_CHECKPOINT) {
            this.durableEventCount++;
            this.eventsSinceCheckpoint++;
        }
    }

    private void appendEncodedEvent(final SagaEventType type, final Map<String, String> fields) {
        SagaEvent event = new SagaEvent(type, fields);
        LogRecord record = new LogRecord(LogRecordHeader.data(Map.of("domain", "saga",
                EVENT_METADATA_KEY, type.name(), "schemaVersion", SagaEventCodec.SCHEMA_VERSION)), this.codec.encode(event));
        this.log.append(EVENT_STREAM, List.of(record), AppendOptions.defaults());
    }

    private void ensureEventCanAppend() {
        if (!this.recovering && this.eventsSinceCheckpoint >= MAX_RECOVERY_TAIL_EVENTS) {
            this.appendCheckpoint();
        }
    }

    private void appendCheckpoint() {
        String body;
        try {
            body = this.checkpointBody();
        } catch (CheckpointTooLargeException exception) {
            this.lastError = "CHECKPOINT_TOO_LARGE";
            this.recordEvidence("SAGA_CHECKPOINT_SKIPPED", null, null, "", "failed", "CHECKPOINT_TOO_LARGE",
                    Map.of("limit", Integer.toString(this.config.maxCheckpointBytes())));
            return;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("checkpointVersion", CHECKPOINT_VERSION);
        fields.put("eventCount", Long.toString(this.durableEventCount));
        fields.put("checksum", Long.toUnsignedString(checksum(body)));
        fields.put("body", body);
        this.appendEncodedEvent(SagaEventType.SAGA_CHECKPOINT, fields);
        this.eventsSinceCheckpoint = 0L;
    }

    private RecoveryStart recoveryStart() {
        Optional<LogOffset> checkpointOffset = this.log.findLatestOffsetByMetadata(
                EVENT_STREAM, EVENT_METADATA_KEY, SagaEventType.SAGA_CHECKPOINT.name(), CHECKPOINT_LOOKUP_RECORD_LIMIT);
        if (checkpointOffset.isPresent()) {
            return new RecoveryStart(checkpointOffset.orElseThrow(), true);
        }
        return new RecoveryStart(LogOffset.beginning(), false);
    }

    private String checkpointBody() {
        BoundedCheckpointBody builder = new BoundedCheckpointBody(this.config.maxCheckpointBytes());
        for (Map.Entry<SagaDefinitionId, RecoveredDefinitionMetadata> entry : this.recoveredDefinitions.entrySet()) {
            List<String> fields = new ArrayList<>();
            fields.add("D");
            fields.add(entry.getKey().value());
            fields.add(Integer.toString(entry.getValue().stepIds().size()));
            for (SagaStepId stepId : entry.getValue().stepIds()) {
                fields.add(stepId.value());
            }
            appendFields(builder, fields);
        }
        for (ExecutionRuntime execution : this.executions.values()) {
            appendExecutionFields(builder, execution);
            for (StepRuntime step : execution.steps.values()) {
                appendFields(builder, List.of("P", execution.executionId.value(), step.stepId.value(), step.state.name(),
                        Integer.toString(step.attempts), Integer.toString(step.compensationAttempts),
                        Long.toString(step.nextAttemptAt.toEpochMilli()), Long.toString(step.startedAt.toEpochMilli()),
                        step.reasonCode, step.compensationOperationId == null ? "" : step.compensationOperationId));
            }
        }
        for (Map.Entry<String, SagaExecutionId> entry : this.idempotency.entrySet()) {
            appendFields(builder, List.of("I", entry.getKey(), entry.getValue().value()));
        }
        appendMarkerFields(builder, "OI", this.outboxIntents);
        appendMarkerFields(builder, "OP", this.outboxPublished);
        appendMarkerFields(builder, "OF", this.outboxFailures);
        appendMarkerFields(builder, "OD", this.outboxDeadLetters);
        for (SagaDeadLetterRecord record : this.deadLetters) {
            appendFields(builder, List.of("L", record.executionId().value(),
                    record.stepId() == null ? "" : record.stepId().value(), record.reasonCode(),
                    Long.toString(record.recordedAt().toEpochMilli()),
                    record.metadata().getOrDefault("kind", ""),
                    record.metadata().getOrDefault("manualIntervention", "")));
        }
        appendFields(builder, List.of("Q", Long.toString(this.completedSagas), Long.toString(this.failedSagas),
                Long.toString(this.cancelledSagas), Long.toString(this.timedOutSagas),
                Long.toString(this.compensations), Long.toString(this.retries),
                Long.toString(this.rejectedOperations), Long.toString(this.durableEventCount)));
        return builder.body();
    }

    private static void appendMarkerFields(final BoundedCheckpointBody builder, final String kind, final Set<String> markers) {
        for (String marker : markers) {
            appendFields(builder, List.of(kind, marker));
        }
    }

    private static void appendExecutionFields(final BoundedCheckpointBody builder, final ExecutionRuntime execution) {
        builder.appendTextField("X");
        builder.appendTextField(execution.executionId.value());
        builder.appendTextField(execution.definitionId.value());
        builder.appendTextField(execution.state.name());
        builder.appendBase64TextField(execution.payload);
        builder.appendTextField(execution.context.contextId().value());
        builder.appendTextField(execution.options.idempotencyKey() == null ? "" : execution.options.idempotencyKey().value());
        builder.appendTextField(Long.toString(execution.startedAt.toEpochMilli()));
        builder.appendTextField(Integer.toString(execution.options.retryPolicy().maxAttempts()));
        builder.appendTextField(Long.toString(execution.options.retryPolicy().delay().toMillis()));
        builder.appendTextField(Long.toString(execution.options.timeoutPolicy().sagaTimeout().toMillis()));
        builder.appendTextField(Long.toString(execution.options.timeoutPolicy().stepTimeout().toMillis()));
        builder.appendTextField(Boolean.toString(execution.options.cancellationPolicy().compensateOnCancel()));
        builder.appendTextField(Boolean.toString(execution.options.cancellationPolicy().cancelRunningStep()));
        builder.appendTextField(Boolean.toString(execution.cancelRequested));
        builder.appendTextField(Boolean.toString(execution.cancelCompensate));
        builder.endLine();
    }

    private void restoreCheckpoint(final SagaEvent event) {
        Map<String, String> fields = event.fields();
        String checkpointVersion = fields.getOrDefault("checkpointVersion", "");
        if (!CHECKPOINT_VERSION.equals(checkpointVersion)) {
            throw new IllegalArgumentException("unsupported saga checkpoint version");
        }
        String body = fields.getOrDefault("body", "");
        if (body.getBytes(StandardCharsets.UTF_8).length > this.config.maxCheckpointBytes()) {
            throw new IllegalArgumentException("saga checkpoint byte limit exceeded");
        }
        long expectedChecksum = Long.parseUnsignedLong(fields.getOrDefault("checksum", "0"));
        if (expectedChecksum != checksum(body)) {
            throw new IllegalArgumentException("saga checkpoint checksum mismatch");
        }
        this.definitions.clear();
        this.recoveredDefinitions.clear();
        this.executions.clear();
        this.idempotency.clear();
        this.outboxIntents.clear();
        this.outboxPublished.clear();
        this.outboxFailures.clear();
        this.outboxDeadLetters.clear();
        this.deadLetters.clear();
        this.completedSagas = 0L;
        this.failedSagas = 0L;
        this.cancelledSagas = 0L;
        this.timedOutSagas = 0L;
        this.compensations = 0L;
        this.retries = 0L;
        this.rejectedOperations = 0L;
        this.durableEventCount = parseLong(fields, "eventCount", SagaEventType.SAGA_CHECKPOINT.name());
        this.parseCheckpointBody(body);
        this.retainTerminalExecutions();
    }

    private void parseCheckpointBody(final String body) {
        int position = 0;
        while (position < body.length()) {
            int lineEnd = body.indexOf('\n', position);
            if (lineEnd < 0) {
                throw new IllegalArgumentException("saga checkpoint line is truncated");
            }
            if (lineEnd > position) {
                this.applyCheckpointLine(decodeCheckpointFields(body, position, lineEnd));
            }
            position = lineEnd + 1;
        }
    }

    private void applyCheckpointLine(final List<String> fields) {
        String kind = fields.getFirst();
        if ("D".equals(kind)) {
            int count = parseInt(fields.get(2));
            if (count > this.config.maxStepsPerSaga()) {
                throw new IllegalArgumentException("saga checkpoint step limit exceeded");
            }
            requireFieldCount(fields, count + 3);
            List<SagaStepId> stepIds = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                stepIds.add(new SagaStepId(fields.get(index + 3)));
            }
            this.recoveredDefinitions.put(new SagaDefinitionId(fields.get(1)), new RecoveredDefinitionMetadata(stepIds));
        } else if ("X".equals(kind)) {
            requireFieldCount(fields, 16);
            SagaExecutionId executionId = new SagaExecutionId(fields.get(1));
            SagaDefinitionId definitionId = new SagaDefinitionId(fields.get(2));
            SagaOptions options = new SagaOptions(fields.get(6).isBlank() ? null : new IdempotencyKey(fields.get(6)),
                    new SagaRetryPolicy(parseInt(fields.get(8)), Duration.ofMillis(parseLong(fields.get(9)))),
                    new SagaTimeoutPolicy(Duration.ofMillis(parseLong(fields.get(10))), Duration.ofMillis(parseLong(fields.get(11)))),
                    new SagaCancellationPolicy(Boolean.parseBoolean(fields.get(12)), Boolean.parseBoolean(fields.get(13))));
            TransactionContext context = new TransactionContext(null, null, null, null, null, null, null, false, null,
                    Map.of("recoveredContextId", fields.get(5)));
            ExecutionRuntime execution = new ExecutionRuntime(executionId, definitionId, options, context,
                    Base64.getDecoder().decode(fields.get(4)), Instant.ofEpochMilli(parseLong(fields.get(7))));
            execution.state = SagaExecutionState.valueOf(fields.get(3));
            execution.cancelRequested = Boolean.parseBoolean(fields.get(14));
            execution.cancelCompensate = Boolean.parseBoolean(fields.get(15));
            this.executions.put(executionId, execution);
            if (this.executions.size() > this.config.maxActiveSagas() + MAX_TERMINAL_EXECUTION_SUMMARIES) {
                throw new IllegalArgumentException("saga checkpoint execution limit exceeded");
            }
        } else if ("P".equals(kind)) {
            if (fields.size() != 9 && fields.size() != 10) {
                throw new IllegalArgumentException("saga checkpoint field count is invalid");
            }
            ExecutionRuntime execution = this.requireRecovered(fields.get(1));
            StepRuntime step = execution.steps.computeIfAbsent(new SagaStepId(fields.get(2)), StepRuntime::new);
            step.state = SagaStepState.valueOf(fields.get(3));
            step.attempts = parseInt(fields.get(4));
            step.compensationAttempts = parseInt(fields.get(5));
            step.nextAttemptAt = Instant.ofEpochMilli(parseLong(fields.get(6)));
            step.startedAt = Instant.ofEpochMilli(parseLong(fields.get(7)));
            step.reasonCode = fields.get(8);
            step.compensationOperationId = fields.size() == 10 && !fields.get(9).isBlank() ? fields.get(9) : null;
        } else if ("I".equals(kind)) {
            requireFieldCount(fields, 3);
            this.idempotency.put(fields.get(1), new SagaExecutionId(fields.get(2)));
            if (this.idempotency.size() > this.config.maxActiveSagas() + MAX_TERMINAL_EXECUTION_SUMMARIES) {
                throw new IllegalArgumentException("saga checkpoint idempotency limit exceeded");
            }
        } else if ("OI".equals(kind)) {
            requireFieldCount(fields, 2);
            rememberCheckpointMarker(this.outboxIntents, fields.get(1));
        } else if ("OP".equals(kind)) {
            requireFieldCount(fields, 2);
            rememberCheckpointMarker(this.outboxPublished, fields.get(1));
        } else if ("OF".equals(kind)) {
            requireFieldCount(fields, 2);
            rememberCheckpointMarker(this.outboxFailures, fields.get(1));
        } else if ("OD".equals(kind)) {
            requireFieldCount(fields, 2);
            rememberCheckpointMarker(this.outboxDeadLetters, fields.get(1));
        } else if ("L".equals(kind)) {
            requireFieldCount(fields, 7);
            Map<String, String> metadata = fields.get(6).isBlank()
                    ? Map.of("kind", fields.get(5))
                    : Map.of("kind", fields.get(5), "manualIntervention", fields.get(6));
            this.addCheckpointDeadLetter(new SagaDeadLetterRecord(new SagaExecutionId(fields.get(1)),
                    fields.get(2).isBlank() ? null : new SagaStepId(fields.get(2)),
                    fields.get(3), Instant.ofEpochMilli(parseLong(fields.get(4))), metadata));
        } else if ("Q".equals(kind)) {
            requireFieldCount(fields, 9);
            this.completedSagas = parseLong(fields.get(1));
            this.failedSagas = parseLong(fields.get(2));
            this.cancelledSagas = parseLong(fields.get(3));
            this.timedOutSagas = parseLong(fields.get(4));
            this.compensations = parseLong(fields.get(5));
            this.retries = parseLong(fields.get(6));
            this.rejectedOperations = parseLong(fields.get(7));
            this.durableEventCount = Math.max(this.durableEventCount, parseLong(fields.get(8)));
        } else {
            throw new IllegalArgumentException("unknown saga checkpoint line");
        }
    }

    private static String outboxKey(final SagaExecutionId executionId, final SagaStepId stepId) {
        return executionId.value() + ":" + stepId.value();
    }

    private static String compensationOperationId(
            final SagaExecutionId executionId,
            final SagaStepId stepId,
            final int attempt) {
        return executionId.value() + ":" + stepId.value() + ":compensation:" + attempt;
    }

    private static void appendFields(final BoundedCheckpointBody builder, final List<String> fields) {
        for (int index = 0; index < fields.size(); index++) {
            builder.appendTextField(fields.get(index));
        }
        builder.endLine();
    }

    private static List<String> decodeCheckpointFields(final String body, final int start, final int end) {
        List<String> fields = new ArrayList<>();
        int position = start;
        while (position <= end) {
            int separator = body.indexOf('|', position);
            int fieldEnd = separator < 0 || separator > end ? end : separator;
            String encoded = body.substring(position, fieldEnd);
            fields.add(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
            if (separator < 0 || separator > end) {
                break;
            }
            position = separator + 1;
        }
        return fields;
    }

    private static void requireFieldCount(final List<String> fields, final int expected) {
        if (fields.size() != expected) {
            throw new IllegalArgumentException("saga checkpoint field count is invalid");
        }
    }

    private static void rememberCheckpointMarker(final Set<String> markers, final String marker) {
        if (marker == null || marker.isBlank()) {
            throw new IllegalArgumentException("saga checkpoint marker is invalid");
        }
        if (!markers.contains(marker) && markers.size() >= MAX_OUTBOX_MARKERS) {
            throw new IllegalArgumentException("saga checkpoint marker limit exceeded");
        }
        markers.add(marker);
    }

    private static long checksum(final String body) {
        CRC32 crc32 = new CRC32();
        crc32.update(body.getBytes(StandardCharsets.UTF_8));
        return crc32.getValue();
    }

    private void recordEvidence(
            final String action,
            final SagaExecutionId executionId,
            final SagaStepId stepId,
            final String contextId,
            final String decision,
            final String reasonCode,
            final Map<String, String> metadata) {
        SagaEvidence item = new SagaEvidence(this.config.clock().instant(), action, executionId, stepId,
                contextId, decision, reasonCode, metadata);
        this.evidence.add(item);
        while (this.evidence.size() > this.config.maxEvidenceEntries()) {
            this.evidence.remove(0);
        }
        Map<String, String> fields = new LinkedHashMap<>(metadata);
        if (executionId != null) {
            fields.put("executionId", executionId.value());
        }
        if (stepId != null) {
            fields.put("stepId", stepId.value());
        }
        fields.put("action", action);
        fields.put("transactionContextId", contextId == null ? "" : contextId);
        fields.put("decision", decision);
        fields.put("reasonCode", reasonCode);
        if (executionId != null) {
            this.append(SagaEventType.SAGA_AUDIT_RECORDED, fields);
        }
    }

    private void addDeadLetter(final SagaDeadLetterRecord record) {
        this.deadLetters.add(record);
        while (this.deadLetters.size() > this.config.maxDeadLettersInStatus()) {
            this.deadLetters.remove(0);
        }
    }

    private void addCheckpointDeadLetter(final SagaDeadLetterRecord record) {
        if (this.deadLetters.size() >= this.config.maxDeadLettersInStatus()) {
            throw new IllegalArgumentException("saga checkpoint dead-letter limit exceeded");
        }
        this.deadLetters.add(record);
    }

    private void rememberOutboxMarker(final Set<String> markers, final String marker) {
        if (marker == null || marker.isBlank()) {
            return;
        }
        markers.remove(marker);
        markers.add(marker);
        while (markers.size() > MAX_OUTBOX_MARKERS) {
            String oldest = markers.iterator().next();
            markers.remove(oldest);
        }
    }

    private void trimIdempotency() {
        int limit = this.config.maxActiveSagas() + MAX_TERMINAL_EXECUTION_SUMMARIES;
        while (this.idempotency.size() > limit) {
            String first = this.idempotency.keySet().iterator().next();
            this.idempotency.remove(first);
        }
    }

    private void retainTerminalExecutions() {
        int terminalCount = 0;
        List<SagaExecutionId> terminalIds = new ArrayList<>();
        for (ExecutionRuntime execution : this.executions.values()) {
            if (execution.terminal()) {
                terminalCount++;
                terminalIds.add(execution.executionId);
            }
        }
        int removeCount = terminalCount - MAX_TERMINAL_EXECUTION_SUMMARIES;
        for (int index = 0; index < removeCount; index++) {
            SagaExecutionId removedId = terminalIds.get(index);
            this.executions.remove(removedId);
            this.idempotency.values().removeIf(value -> value.equals(removedId));
        }
    }

    private ExecutionRuntime requireExecution(final SagaExecutionId executionId) {
        if (executionId == null || !this.executions.containsKey(executionId)) {
            throw this.failure(MiddlewareErrorCode.NOT_FOUND, "saga execution not found", Map.of());
        }
        return this.executions.get(executionId);
    }

    private SagaExecution toPublic(final ExecutionRuntime runtime) {
        SagaExecutionState state = runtime.state;
        if (!runtime.terminal() && !this.definitions.containsKey(runtime.definitionId)) {
            state = SagaExecutionState.WAITING_FOR_DEFINITION;
        }
        return new SagaExecution(runtime.executionId, runtime.definitionId, state,
                TransactionContextSummary.from(runtime.context),
                runtime.steps.values().stream()
                        .map(step -> new SagaStepExecution(step.stepId, step.state, step.attempts, step.reasonCode))
                        .toList());
    }

    private void ensureOpen() {
        if (this.closed.get()) {
            throw this.failure(MiddlewareErrorCode.UNAVAILABLE, "saga service is closed", Map.of());
        }
    }

    private SagaException failure(final MiddlewareErrorCode code, final String message, final Map<String, String> details) {
        this.lastError = code.name();
        return new SagaException(new RemoteError(code, message, safeDetails(details), null, null,
                code == MiddlewareErrorCode.OVERLOADED, code == MiddlewareErrorCode.INTERNAL));
    }

    private static Map<String, String> safeDetails(final Map<String, String> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        Map<String, String> safe = new LinkedHashMap<>();
        details.forEach((key, value) -> {
            String name = key == null ? "" : key.trim();
            if (name.isBlank()) {
                return;
            }
            String normalized = name.replace("-", "").replace("_", "").replace(".", "")
                    .toLowerCase(java.util.Locale.ROOT);
            boolean sensitive = normalized.contains("password")
                    || normalized.contains("passwd")
                    || normalized.equals("pwd")
                    || normalized.contains("secret")
                    || normalized.contains("token")
                    || normalized.contains("apikey")
                    || normalized.contains("accesskey")
                    || normalized.contains("privatekey")
                    || normalized.contains("credential")
                    || normalized.contains("authorization")
                    || normalized.contains("cookie")
                    || normalized.contains("session")
                    || normalized.contains("bearer");
            String text = value == null ? "" : value.trim();
            safe.put(name, sensitive ? "[REDACTED]" : (text.length() > 256 ? text.substring(0, 256) : text));
        });
        return Map.copyOf(safe);
    }

    private static TransactionContext compensationContext(final TransactionContext context, final String operationId) {
        Map<String, String> metadata = new LinkedHashMap<>(context.metadata());
        metadata.put("compensationOperationId", operationId);
        return new TransactionContext(context.contextId(), context.scope(), context.correlation(),
                context.idempotencyKey(), context.dedupeKey(), context.deliverySemantics(), context.deadline(),
                context.cancellationRequested(), context.participant(), metadata);
    }

    private static final class BoundedCheckpointBody {
        private static final Base64.Encoder FIELD_ENCODER = Base64.getUrlEncoder().withoutPadding();
        private static final Base64.Encoder PAYLOAD_ENCODER = Base64.getEncoder();
        private static final byte[] EMPTY_BYTES = new byte[0];
        private final int limit;
        private final StringBuilder builder;
        private int bytes;
        private boolean lineStarted;

        private BoundedCheckpointBody(final int limit) {
            this.limit = limit;
            this.builder = new StringBuilder(Math.min(limit, 2048));
            this.bytes = 0;
            this.lineStarted = false;
        }

        private void appendAscii(final char value) {
            this.addBytes(1);
            this.builder.append(value);
        }

        private void appendTextField(final String text) {
            String safe = text == null ? "" : text;
            this.appendEncodedField(safe.getBytes(StandardCharsets.UTF_8));
        }

        private void appendBase64TextField(final byte[] value) {
            byte[] raw = value == null ? EMPTY_BYTES : value;
            if (this.lineStarted) {
                this.appendAscii('|');
            }
            long payloadTextLength = encodedPaddedBase64Length(raw.length);
            this.addBytes(encodedBase64UrlLength(payloadTextLength));
            this.builder.append(FIELD_ENCODER.encodeToString(PAYLOAD_ENCODER.encode(raw)));
            this.lineStarted = true;
        }

        private void appendEncodedField(final byte[] raw) {
            if (this.lineStarted) {
                this.appendAscii('|');
            }
            this.addBytes(encodedBase64UrlLength(raw.length));
            this.builder.append(FIELD_ENCODER.encodeToString(raw));
            this.lineStarted = true;
        }

        private void endLine() {
            this.appendAscii('\n');
            this.lineStarted = false;
        }

        private void addBytes(final long count) {
            if (count < 0 || this.bytes > this.limit - count) {
                this.bytes = this.limit + 1;
                throw new CheckpointTooLargeException();
            }
            this.bytes += (int) count;
        }

        private String body() {
            return this.builder.toString();
        }

        private static long encodedBase64UrlLength(final long rawLength) {
            if (rawLength < 0) {
                throw new CheckpointTooLargeException();
            }
            long completeGroups = rawLength / 3L;
            long remainder = rawLength % 3L;
            long encoded = completeGroups * 4L;
            if (remainder != 0) {
                encoded += remainder + 1L;
            }
            return encoded;
        }

        private static long encodedPaddedBase64Length(final int rawLength) {
            if (rawLength < 0) {
                throw new CheckpointTooLargeException();
            }
            return ((long) rawLength + 2L) / 3L * 4L;
        }
    }

    private static final class CheckpointTooLargeException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class ExecutionRuntime {
        private final SagaExecutionId executionId;
        private final SagaDefinitionId definitionId;
        private final SagaOptions options;
        private final TransactionContext context;
        private final byte[] payload;
        private final Instant startedAt;
        private final Map<SagaStepId, StepRuntime> steps;
        private SagaExecutionState state;
        private boolean cancelRequested;
        private boolean cancelCompensate;

        private ExecutionRuntime(
                final SagaExecutionId executionId,
                final SagaDefinitionId definitionId,
                final SagaOptions options,
                final TransactionContext context,
                final byte[] payload,
                final Instant startedAt) {
            this.executionId = executionId;
            this.definitionId = definitionId;
            this.options = options;
            this.context = context;
            this.payload = Arrays.copyOf(payload, payload.length);
            this.startedAt = startedAt;
            this.steps = new LinkedHashMap<>();
            this.state = SagaExecutionState.STARTING;
            this.cancelRequested = false;
            this.cancelCompensate = true;
        }

        private boolean terminal() {
            return this.state == SagaExecutionState.COMPLETED || this.state == SagaExecutionState.COMPENSATED
                    || this.state == SagaExecutionState.CANCELLED || this.state == SagaExecutionState.FAILED
                    || this.state == SagaExecutionState.DEAD_LETTERED || this.state == SagaExecutionState.TIMED_OUT;
        }
    }

    private static final class StepRuntime {
        private final SagaStepId stepId;
        private SagaStepState state;
        private int attempts;
        private int compensationAttempts;
        private Instant nextAttemptAt;
        private Instant startedAt;
        private String reasonCode;
        private String compensationOperationId;

        private StepRuntime(final SagaStepId stepId) {
            this.stepId = stepId;
            this.state = SagaStepState.PENDING;
            this.attempts = 0;
            this.compensationAttempts = 0;
            this.nextAttemptAt = Instant.EPOCH;
            this.startedAt = Instant.EPOCH;
            this.reasonCode = "PENDING";
            this.compensationOperationId = null;
        }
    }

    private record RecoveredDefinitionMetadata(List<SagaStepId> stepIds) {
        private RecoveredDefinitionMetadata {
            stepIds = stepIds == null ? List.of() : List.copyOf(stepIds);
        }
    }

    private record RecoveryStart(LogOffset offset, boolean checkpointExpected) {
    }
}
