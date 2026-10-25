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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.CursorOptions;
import org.j7m.middleware.api.durablelog.LogCursor;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.j7m.middleware.api.durablelog.LogRecord;
import org.j7m.middleware.api.durablelog.LogRecordHeader;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.api.durablelog.RecoveryOptions;
import org.j7m.middleware.api.saga.SagaDefinitionId;
import org.j7m.middleware.api.saga.SagaException;
import org.j7m.middleware.api.saga.SagaExecutionState;
import org.j7m.middleware.api.saga.SagaOptions;
import org.j7m.middleware.api.saga.SagaRetryPolicy;
import org.j7m.middleware.api.saga.SagaStartRequest;
import org.j7m.middleware.api.saga.SagaStepDefinition;
import org.j7m.middleware.api.saga.SagaStepId;
import org.j7m.middleware.api.saga.SagaStepState;
import org.j7m.middleware.api.saga.SagaStepType;
import org.j7m.middleware.api.saga.SagaTimeoutPolicy;
import org.j7m.middleware.api.saga.SagaCommandResult;
import org.j7m.middleware.api.saga.SagaCompensationResult;
import org.j7m.middleware.api.saga.SagaDefinition;
import org.j7m.middleware.durablelog.DurableLogConfig;
import org.j7m.middleware.durablelog.FileDurableLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SagaRecoveryTest {
    @Test
    void recoversDefinitionsExecutionsDeadLettersAndEvidence(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("recover");
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(SagaTestSupport.twoStepDefinition(id, SagaTestSupport.newOrder(), SagaTestSupport.newOrder()));
            service.runUntilIdle(service.start(SagaTestSupport.request(id)).executionId());
        }
        try (LogBackedSagaService recovered = SagaTestSupport.service(directory, clock)) {
            assertEquals(1, recovered.status().recoveryReport().definitionsRecovered());
            assertEquals(1, recovered.status().recoveryReport().executionsRecovered());
            assertEquals(SagaExecutionState.COMPLETED, recovered.status().executions().getFirst().state());
        }
    }

    @Test
    void malformedEventsFailRecoverySafely(@TempDir final Path directory) throws Exception {
        Path events = directory.resolve("events");
        Files.createDirectories(events);
        try (FileDurableLog log = new FileDurableLog(DurableLogConfig.defaults(events))) {
            String malformed = "schemaVersion=9\ntype=SAGA_STARTED\nexecutionId=saga-bad\n";
            log.append(new LogStreamId("j7m.saga.events"), java.util.List.of(new LogRecord(
                    LogRecordHeader.data(java.util.Map.of("domain", "saga")),
                    Base64.getEncoder().encode(malformed.getBytes(java.nio.charset.StandardCharsets.UTF_8)))),
                    AppendOptions.defaults());
        }
        SagaException failure = assertThrows(SagaException.class, () -> SagaTestSupport.service(directory, SagaTestSupport.clock()));
        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, failure.remoteError().code());
    }

    @Test
    void recoveredRetryingSagaWaitsForDefinitionAndResumesAfterReregistration(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("recover-retry");
        AtomicInteger firstAttempts = new AtomicInteger();
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(new org.j7m.middleware.api.saga.SagaDefinition(id,
                    List.of(SagaTestSupport.retryThenSucceedStep("retry", firstAttempts))));
            service.runUntilIdle(service.start(new SagaStartRequest(id,
                    new SagaOptions(null, new SagaRetryPolicy(2, Duration.ofSeconds(30)),
                            new SagaTimeoutPolicy(Duration.ofHours(2), Duration.ZERO), null),
                    null, SagaTestSupport.SECRET_PAYLOAD)).executionId());
            assertEquals(1, firstAttempts.get());
        }

        try (LogBackedSagaService waiting = SagaTestSupport.service(directory, clock)) {
            assertEquals(SagaExecutionState.WAITING_FOR_DEFINITION, waiting.status().executions().getFirst().state());
            assertEquals(SagaStepState.RETRY_PENDING, waiting.status().executions().getFirst().steps().getFirst().state());
        }

        AtomicInteger resumedAttempts = new AtomicInteger(1);
        try (LogBackedSagaService recovered = SagaTestSupport.service(directory, clock)) {
            recovered.register(new org.j7m.middleware.api.saga.SagaDefinition(id,
                    List.of(SagaTestSupport.retryThenSucceedStep("retry", resumedAttempts))));
            recovered.runUntilIdle(recovered.status().executions().getFirst().executionId());
            assertEquals(SagaExecutionState.RUNNING, recovered.status().executions().getFirst().state());
            assertEquals(1, resumedAttempts.get());

            clock.advance(Duration.ofSeconds(30));
            recovered.runUntilIdle(recovered.status().executions().getFirst().executionId());
            assertEquals(SagaExecutionState.COMPLETED, recovered.status().executions().getFirst().state());
            assertEquals(2, resumedAttempts.get());
        }
    }

    @Test
    void recoveredRunningStepIsNormalizedAndResumesAfterReregistration(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("recover-running");
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(new org.j7m.middleware.api.saga.SagaDefinition(id,
                    List.of(new SagaStepDefinition(new SagaStepId("in-flight"), SagaStepType.LOCAL,
                            (context, command) -> {
                                throw new IllegalStateException("simulated crash after step start");
                            },
                            (context, command) -> SagaCompensationResult.succeeded(),
                            new SagaRetryPolicy(2, Duration.ZERO),
                            SagaTimeoutPolicy.none()))));
            assertThrows(IllegalStateException.class,
                    () -> service.runUntilIdle(service.start(SagaTestSupport.request(id)).executionId()));
        }

        try (LogBackedSagaService waiting = SagaTestSupport.service(directory, clock)) {
            assertEquals(SagaExecutionState.WAITING_FOR_DEFINITION, waiting.status().executions().getFirst().state());
            assertEquals(SagaStepState.RETRY_PENDING, waiting.status().executions().getFirst().steps().getFirst().state());
            assertEquals("RECOVERED_RUNNING_RETRY", waiting.status().executions().getFirst().steps().getFirst().reasonCode());
            assertTrue(waiting.status().recoveryReport().diagnostics().stream()
                    .anyMatch(item -> "recovered-running-retry".equals(item.get("recoveryState"))));
        }

        AtomicInteger attempts = new AtomicInteger(1);
        try (LogBackedSagaService recovered = SagaTestSupport.service(directory, clock)) {
            recovered.register(new org.j7m.middleware.api.saga.SagaDefinition(id,
                    List.of(new SagaStepDefinition(new SagaStepId("in-flight"), SagaStepType.LOCAL,
                            (context, command) -> {
                                attempts.incrementAndGet();
                                return SagaCommandResult.succeeded();
                            },
                            (context, command) -> SagaCompensationResult.succeeded(),
                            new SagaRetryPolicy(2, Duration.ZERO),
                            SagaTimeoutPolicy.none()))));
            recovered.runUntilIdle(recovered.status().executions().getFirst().executionId());
            assertEquals(SagaExecutionState.COMPLETED, recovered.status().executions().getFirst().state());
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void recoveredCompensatingStepRequiresManualInterventionAfterReregistration(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("recover-compensating");
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(new org.j7m.middleware.api.saga.SagaDefinition(id, List.of(
                    new SagaStepDefinition(new SagaStepId("reserve"), SagaStepType.LOCAL,
                            (context, command) -> SagaCommandResult.succeeded(),
                            (context, command) -> {
                                throw new IllegalStateException("simulated crash after compensation start");
                            },
                            SagaRetryPolicy.none(), SagaTimeoutPolicy.none()),
                    SagaTestSupport.failingStep("charge", false))));
            assertThrows(IllegalStateException.class,
                    () -> service.runUntilIdle(service.start(SagaTestSupport.request(id)).executionId()));
        }

        try (LogBackedSagaService waiting = SagaTestSupport.service(directory, clock)) {
            assertEquals(SagaExecutionState.DEAD_LETTERED, waiting.status().executions().getFirst().state());
            assertEquals(SagaStepState.DEAD_LETTERED, waiting.status().executions().getFirst().steps().getFirst().state());
            assertEquals("RECOVERED_COMPENSATION_MANUAL_INTERVENTION",
                    waiting.status().executions().getFirst().steps().getFirst().reasonCode());
            assertEquals(1, waiting.status().deadLetters().size());
        }

        AtomicInteger compensations = new AtomicInteger();
        try (LogBackedSagaService recovered = SagaTestSupport.service(directory, clock)) {
            recovered.register(new org.j7m.middleware.api.saga.SagaDefinition(id, List.of(
                    new SagaStepDefinition(new SagaStepId("reserve"), SagaStepType.LOCAL,
                            (context, command) -> SagaCommandResult.succeeded(),
                            (context, command) -> {
                                compensations.incrementAndGet();
                                return SagaCompensationResult.succeeded();
                            },
                            SagaRetryPolicy.none(), SagaTimeoutPolicy.none()),
                    SagaTestSupport.failingStep("charge", false))));
            recovered.runUntilIdle(recovered.status().executions().getFirst().executionId());
            assertEquals(SagaExecutionState.DEAD_LETTERED, recovered.status().executions().getFirst().state());
            assertEquals(0, compensations.get());
        }
    }

    @Test
    void recoveredCompensationRetryWaitsForPersistedEligibility(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("recover-compensation-retry");
        org.j7m.middleware.api.saga.SagaExecutionId executionId;
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            AtomicInteger firstCompensations = new AtomicInteger();
            service.register(new org.j7m.middleware.api.saga.SagaDefinition(id, List.of(
                    compensationRetryStep("reserve", firstCompensations, false, Duration.ofSeconds(10L)),
                    SagaTestSupport.failingStep("charge", false))));
            executionId = service.start(SagaTestSupport.request(id)).executionId();
            assertEquals(SagaExecutionState.COMPENSATING, service.runUntilIdle(executionId).state());
            assertEquals(1, firstCompensations.get());
        }

        try (LogBackedSagaService waiting = SagaTestSupport.service(directory, clock)) {
            assertEquals(SagaExecutionState.WAITING_FOR_DEFINITION, waiting.status().executions().getFirst().state());
            assertEquals(SagaStepState.COMPENSATION_FAILED, waiting.status().executions().getFirst().steps().getFirst().state());
            assertTrue(waiting.status().recoveryReport().diagnostics().stream()
                    .anyMatch(item -> "recovered-compensation-retry".equals(item.get("recoveryState"))));
        }

        AtomicInteger resumedCompensations = new AtomicInteger();
        try (LogBackedSagaService recovered = SagaTestSupport.service(directory, clock)) {
            recovered.register(new org.j7m.middleware.api.saga.SagaDefinition(id, List.of(
                    compensationRetryStep("reserve", resumedCompensations, true, Duration.ofSeconds(10L)),
                    SagaTestSupport.failingStep("charge", false))));
            assertEquals(SagaExecutionState.COMPENSATING, recovered.runUntilIdle(executionId).state());
            assertEquals(0, resumedCompensations.get());

            clock.advance(Duration.ofSeconds(10L));
            assertEquals(SagaExecutionState.COMPENSATED, recovered.runUntilIdle(executionId).state());
            assertEquals(1, resumedCompensations.get());
        }
    }

    @Test
    void recoveredCrashAfterExhaustedCompensationFailureDoesNotRetryStaleEligibility(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        Path source = directory.resolve("source");
        Path crash = directory.resolve("crash");
        SagaDefinitionId id = new SagaDefinitionId("recover-exhausted-compensation-failure");
        AtomicInteger sourceCompensations = new AtomicInteger();
        org.j7m.middleware.api.saga.SagaExecutionId executionId;
        try (LogBackedSagaService service = SagaTestSupport.service(source, clock)) {
            service.register(new org.j7m.middleware.api.saga.SagaDefinition(id, List.of(
                    compensationRetryStep("reserve", sourceCompensations, false, Duration.ofSeconds(10L)),
                    SagaTestSupport.failingStep("charge", false))));
            executionId = service.start(SagaTestSupport.request(id)).executionId();
            assertEquals(SagaExecutionState.COMPENSATING, service.runUntilIdle(executionId).state());
            clock.advance(Duration.ofSeconds(10L));
            assertEquals(SagaExecutionState.DEAD_LETTERED, service.runUntilIdle(executionId).state());
            assertEquals(2, sourceCompensations.get());
        }

        copyThroughSecondCompensationFailure(source, crash);

        AtomicInteger recoveredCompensations = new AtomicInteger();
        try (LogBackedSagaService recovered = SagaTestSupport.service(crash, clock)) {
            assertEquals(SagaExecutionState.DEAD_LETTERED, recovered.status().executions().getFirst().state());
            assertEquals(SagaStepState.DEAD_LETTERED, recovered.status().executions().getFirst().steps().getFirst().state());
            recovered.register(new org.j7m.middleware.api.saga.SagaDefinition(id, List.of(
                    compensationRetryStep("reserve", recoveredCompensations, true, Duration.ZERO),
                    SagaTestSupport.failingStep("charge", false))));
            recovered.runUntilIdle(executionId);
            assertEquals(SagaExecutionState.DEAD_LETTERED, recovered.status().executions().getFirst().state());
            assertEquals(0, recoveredCompensations.get());
        }
    }

    @Test
    void recoveredTerminalStatesRemainTerminal(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId completedId = new SagaDefinitionId("recover-terminal-completed");
        SagaDefinitionId cancelledId = new SagaDefinitionId("recover-terminal-cancelled");
        SagaDefinitionId timedOutId = new SagaDefinitionId("recover-terminal-timeout");
        SagaDefinitionId deadLetterId = new SagaDefinitionId("recover-terminal-deadletter");
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(SagaTestSupport.twoStepDefinition(completedId, SagaTestSupport.newOrder(), SagaTestSupport.newOrder()));
            service.runUntilIdle(service.start(SagaTestSupport.request(completedId)).executionId());

            service.register(SagaTestSupport.twoStepDefinition(cancelledId, SagaTestSupport.newOrder(), SagaTestSupport.newOrder()));
            org.j7m.middleware.api.saga.SagaExecutionId cancelled =
                    service.start(SagaTestSupport.request(cancelledId)).executionId();
            service.cancel(new org.j7m.middleware.api.saga.SagaCancelRequest(cancelled, "test-cancel", false));

            service.register(new org.j7m.middleware.api.saga.SagaDefinition(timedOutId,
                    List.of(SagaTestSupport.step("timeout-step", SagaTestSupport.newOrder(), SagaTestSupport.newOrder()))));
            org.j7m.middleware.api.saga.SagaExecutionId timedOut = service.start(new SagaStartRequest(timedOutId,
                    new SagaOptions(null, SagaRetryPolicy.none(),
                            new SagaTimeoutPolicy(Duration.ofSeconds(1), Duration.ZERO), null),
                    null, SagaTestSupport.SECRET_PAYLOAD)).executionId();
            clock.advance(Duration.ofSeconds(2));
            service.evaluateTimeouts();
            assertEquals(SagaExecutionState.TIMED_OUT, service.execution(timedOut).state());

            service.register(new org.j7m.middleware.api.saga.SagaDefinition(deadLetterId,
                    List.of(SagaTestSupport.compensationDeadLetterStep("deadletter"),
                            SagaTestSupport.failingStep("fail", false))));
            service.runUntilIdle(service.start(SagaTestSupport.request(deadLetterId)).executionId());
        }

        try (LogBackedSagaService recovered = SagaTestSupport.service(directory, clock)) {
            assertEquals(4, recovered.status().executions().size());
            assertEquals(1, recovered.status().executions().stream()
                    .filter(execution -> execution.state() == SagaExecutionState.COMPLETED)
                    .count());
            assertEquals(1, recovered.status().executions().stream()
                    .filter(execution -> execution.state() == SagaExecutionState.CANCELLED)
                    .count());
            assertEquals(1, recovered.status().executions().stream()
                    .filter(execution -> execution.state() == SagaExecutionState.TIMED_OUT)
                    .count());
            assertEquals(1, recovered.status().executions().stream()
                    .filter(execution -> execution.state() == SagaExecutionState.DEAD_LETTERED)
                    .count());
        }
    }

    @Test
    void malformedNumericDefinitionFieldsFailRecoverySafely(@TempDir final Path directory) throws Exception {
        Path events = directory.resolve("events");
        Files.createDirectories(events);
        String event = String.join("\n",
                "schemaVersion=" + Base64.getEncoder().encodeToString("1".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "type=" + Base64.getEncoder().encodeToString("SAGA_DEFINITION_REGISTERED".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "definitionId=" + Base64.getEncoder().encodeToString("broken".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "stepCount=" + Base64.getEncoder().encodeToString("not-a-number".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "");
        try (FileDurableLog log = new FileDurableLog(DurableLogConfig.defaults(events))) {
            log.append(new LogStreamId("j7m.saga.events"), java.util.List.of(new LogRecord(
                    LogRecordHeader.data(java.util.Map.of("domain", "saga")), event.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                    AppendOptions.defaults());
        }
        SagaException failure = assertThrows(SagaException.class, () -> SagaTestSupport.service(directory, SagaTestSupport.clock()));
        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, failure.remoteError().code());
    }

    @Test
    void missingCompensationRetryEligibilityFailsRecovery(@TempDir final Path directory) {
        appendSagaEvents(directory, validStartedEvent(), compensationRetryEvent(Map.of()));

        SagaException failure = assertThrows(SagaException.class, () -> SagaTestSupport.service(directory, SagaTestSupport.clock()));

        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, failure.remoteError().code());
        assertEquals("SAGA_COMPENSATION_RETRY_SCHEDULED", failure.remoteError().details().get("eventType"));
        assertEquals("missing required field: nextAttemptAt", failure.remoteError().details().get("reason"));
    }

    @Test
    void malformedCompensationRetryEligibilityFailsRecovery(@TempDir final Path directory) {
        appendSagaEvents(directory, validStartedEvent(), compensationRetryEvent(Map.of("nextAttemptAt", "not-a-number")));

        SagaException failure = assertThrows(SagaException.class, () -> SagaTestSupport.service(directory, SagaTestSupport.clock()));

        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, failure.remoteError().code());
        assertEquals("SAGA_COMPENSATION_RETRY_SCHEDULED", failure.remoteError().details().get("eventType"));
        assertEquals("malformed numeric field: nextAttemptAt", failure.remoteError().details().get("reason"));
    }

    @Test
    void recoveryStartsFromBoundedCheckpointAndReplaysTail(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("checkpoint");
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(new org.j7m.middleware.api.saga.SagaDefinition(id,
                    List.of(SagaTestSupport.step("single", SagaTestSupport.newOrder(), SagaTestSupport.newOrder()))));
            for (int index = 0; index < 80; index++) {
                service.runUntilIdle(service.start(SagaTestSupport.request(id)).executionId());
            }
        }

        try (LogBackedSagaService recovered = SagaTestSupport.service(directory, clock)) {
            assertEquals(80, recovered.status().executions().size());
            assertTrue(recovered.status().recoveryReport().eventsReplayed() <= 257L,
                    "checkpoint recovery should replay at most one checkpoint and its bounded tail");
            assertEquals(80L, recovered.status().metrics().completedSagas());
        }
    }

    @Test
    void recoveryNormalizationAppendsForceBoundedCheckpoint(@TempDir final Path directory) {
        List<SagaEvent> events = new java.util.ArrayList<>();
        for (int index = 0; index < 253; index++) {
            events.add(new SagaEvent(SagaEventType.SAGA_AUDIT_RECORDED, Map.of(
                    "executionId", "filler-" + index,
                    "action", "FILLER",
                    "transactionContextId", "",
                    "decision", "recorded",
                    "reasonCode", "FILLER")));
        }
        events.add(validStartedEvent());
        events.add(stepStartedEvent("saga-retry-malformed", "reserve"));
        appendSagaEvents(directory, events.toArray(SagaEvent[]::new));
        assertEquals(0, countEvents(directory, SagaEventType.SAGA_CHECKPOINT));

        try (LogBackedSagaService recovered = SagaTestSupport.service(directory, SagaTestSupport.clock())) {
            assertTrue(recovered.status().recoveryReport().diagnostics().stream()
                    .anyMatch(item -> "recovered-running-retry".equals(item.get("recoveryState"))));
        }

        assertTrue(countEvents(directory, SagaEventType.SAGA_CHECKPOINT) > 0);
    }

    @Test
    void checkpointAtExactConfiguredByteLimitRestores(@TempDir final Path directory) {
        String body = checkpointLine(List.of("Q", "0", "0", "0", "0", "0", "0", "0", "0"));
        appendSagaEvents(directory, checkpointEvent(body));

        try (LogBackedSagaService service = serviceWithCheckpointLimit(directory, body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)) {
            assertEquals(0L, service.status().metrics().completedSagas());
        }
    }

    @Test
    void checkpointLimitPlusOneFailsRecovery(@TempDir final Path directory) {
        String body = checkpointLine(List.of("Q", "0", "0", "0", "0", "0", "0", "0", "0")) + "\n";
        appendSagaEvents(directory, checkpointEvent(body));

        SagaException failure = assertThrows(SagaException.class,
                () -> serviceWithCheckpointLimit(directory, body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length - 1));

        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, failure.remoteError().code());
    }

    @Test
    void malformedCheckpointFailsRecovery(@TempDir final Path directory) {
        String body = checkpointLine(List.of("Q", "0", "0"));
        appendSagaEvents(directory, checkpointEvent(body));

        SagaException failure = assertThrows(SagaException.class,
                () -> serviceWithCheckpointLimit(directory, 4096));

        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, failure.remoteError().code());
    }

    @Test
    void oversizedCheckpointGenerationKeepsPreviousCheckpoint(@TempDir final Path directory) {
        String previousBody = checkpointLine(List.of("Q", "0", "0", "0", "0", "0", "0", "0", "0"));
        appendSagaEvents(directory, checkpointEvent(previousBody));
        assertEquals(1, countEvents(directory, SagaEventType.SAGA_CHECKPOINT));

        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("checkpoint-large-payload");
        byte[] payload = new byte[768];
        Arrays.fill(payload, (byte) 7);
        try (LogBackedSagaService service = new LogBackedSagaService(new SagaConfig(
                directory.resolve("events"), directory.resolve("messaging"), clock,
                64, 16, 3, 64, 2048, 256, 16, 512))) {
            service.register(new SagaDefinition(id, List.of(SagaTestSupport.step(
                    "single",
                    SagaTestSupport.newOrder(),
                    SagaTestSupport.newOrder()))));
            for (int index = 0; index < 40; index++) {
                service.runUntilIdle(service.start(new SagaStartRequest(id, SagaOptions.defaults(), null, payload)).executionId());
            }
            assertTrue(service.status().evidence().stream()
                    .anyMatch(event -> event.action().equals("SAGA_CHECKPOINT_SKIPPED")
                            && event.reasonCode().equals("CHECKPOINT_TOO_LARGE")));
        }

        assertEquals(1, countEvents(directory, SagaEventType.SAGA_CHECKPOINT));
    }

    @Test
    void oversizedRawCheckpointBodyFailsRecoveryBeforeRestore(@TempDir final Path directory) {
        appendRawSagaRecord(directory, rawCheckpointPayload(Base64.getEncoder().encodeToString(new byte[128])));

        SagaException failure = assertThrows(SagaException.class,
                () -> serviceWithCheckpointLimit(directory, 16));

        assertEquals(MiddlewareErrorCode.RECOVERY_FAILED, failure.remoteError().code());
        assertEquals("saga checkpoint byte limit exceeded", failure.remoteError().details().get("reason"));
    }

    private static SagaStepDefinition compensationRetryStep(
            final String id,
            final AtomicInteger compensations,
            final boolean success,
            final Duration retryDelay) {
        return new SagaStepDefinition(new SagaStepId(id), SagaStepType.LOCAL,
                (context, command) -> SagaCommandResult.succeeded(),
                (context, command) -> {
                    compensations.incrementAndGet();
                    return success ? SagaCompensationResult.succeeded()
                            : SagaCompensationResult.failed("COMPENSATION_RETRYABLE", true);
                },
                new SagaRetryPolicy(1, retryDelay),
                SagaTimeoutPolicy.none());
    }

    private static SagaEvent validStartedEvent() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("executionId", "saga-retry-malformed");
        fields.put("definitionId", "definition-retry-malformed");
        fields.put("state", SagaExecutionState.RUNNING.name());
        fields.put("payload", "");
        fields.put("contextId", "context-retry-malformed");
        fields.put("idempotencyKey", "");
        fields.put("startedAt", "0");
        fields.put("option.retryMaxAttempts", "1");
        fields.put("option.retryDelayMillis", "0");
        fields.put("option.sagaTimeoutMillis", "0");
        fields.put("option.stepTimeoutMillis", "0");
        fields.put("option.compensateOnCancel", "true");
        fields.put("option.cancelRunningStep", "false");
        return new SagaEvent(SagaEventType.SAGA_STARTED, fields);
    }

    private static SagaEvent compensationRetryEvent(final Map<String, String> overrides) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("executionId", "saga-retry-malformed");
        fields.put("stepId", "reserve");
        fields.put("attempts", "1");
        fields.put("compensationAttempts", "1");
        fields.put("startedAt", "0");
        fields.put("reasonCode", "COMPENSATION_RETRYABLE");
        fields.putAll(overrides);
        return new SagaEvent(SagaEventType.SAGA_COMPENSATION_RETRY_SCHEDULED, fields);
    }

    private static SagaEvent stepStartedEvent(final String executionId, final String stepId) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("executionId", executionId);
        fields.put("stepId", stepId);
        fields.put("attempts", "1");
        fields.put("compensationAttempts", "0");
        fields.put("startedAt", "0");
        fields.put("nextAttemptAt", "0");
        fields.put("reasonCode", "STARTED");
        return new SagaEvent(SagaEventType.SAGA_STEP_STARTED, fields);
    }

    private static int countEvents(final Path directory, final SagaEventType type) {
        SagaEventCodec codec = new SagaEventCodec();
        int count = 0;
        try (FileDurableLog log = new FileDurableLog(DurableLogConfig.defaults(directory.resolve("events")))) {
            log.recover(RecoveryOptions.defaults());
            try (LogCursor cursor = log.openCursor(new LogStreamId("j7m.saga.events"), LogOffset.beginning(), CursorOptions.defaults())) {
                Optional<LogRecord> next = cursor.next();
                while (next.isPresent()) {
                    SagaEvent event = codec.decode(next.orElseThrow().payload());
                    if (event.type() == type) {
                        count++;
                    }
                    next = cursor.next();
                }
            }
        }
        return count;
    }

    private static void appendSagaEvents(final Path directory, final SagaEvent... events) {
        SagaEventCodec codec = new SagaEventCodec();
        try (FileDurableLog log = new FileDurableLog(DurableLogConfig.defaults(directory.resolve("events")))) {
            for (SagaEvent event : events) {
                LogRecord record = new LogRecord(LogRecordHeader.data(Map.of(
                        "domain", "saga",
                        "eventType", event.type().name(),
                        "schemaVersion", SagaEventCodec.SCHEMA_VERSION)), codec.encode(event));
                log.append(new LogStreamId("j7m.saga.events"), List.of(record), AppendOptions.defaults());
            }
        }
    }

    private static void appendRawSagaRecord(final Path directory, final byte[] payload) {
        try (FileDurableLog log = new FileDurableLog(DurableLogConfig.defaults(directory.resolve("events")))) {
            LogRecord record = new LogRecord(LogRecordHeader.data(Map.of(
                    "domain", "saga",
                    "eventType", SagaEventType.SAGA_CHECKPOINT.name(),
                    "schemaVersion", SagaEventCodec.SCHEMA_VERSION)), payload);
            log.append(new LogStreamId("j7m.saga.events"), List.of(record), AppendOptions.defaults());
        }
    }

    private static byte[] rawCheckpointPayload(final String encodedBody) {
        String text = "schemaVersion=" + encodedField(SagaEventCodec.SCHEMA_VERSION) + "\n"
                + "type=" + encodedField(SagaEventType.SAGA_CHECKPOINT.name()) + "\n"
                + "body=" + encodedBody + "\n"
                + "checkpointVersion=" + encodedField("1") + "\n"
                + "checksum=" + encodedField("0") + "\n";
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String encodedField(final String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static LogBackedSagaService serviceWithCheckpointLimit(final Path directory, final int checkpointBytes) {
        MutableSagaClock clock = SagaTestSupport.clock();
        return new LogBackedSagaService(new SagaConfig(directory.resolve("events"), directory.resolve("messaging"), clock,
                32, 16, 3, 64, 1024, 64, 16, checkpointBytes));
    }

    private static SagaEvent checkpointEvent(final String body) {
        return new SagaEvent(SagaEventType.SAGA_CHECKPOINT, Map.of(
                "checkpointVersion", "1",
                "eventCount", "0",
                "checksum", Long.toUnsignedString(checksum(body)),
                "body", body));
    }

    private static String checkpointLine(final List<String> fields) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0) {
                builder.append('|');
            }
            builder.append(Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(fields.get(index).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        builder.append('\n');
        return builder.toString();
    }

    private static long checksum(final String body) {
        CRC32 crc32 = new CRC32();
        crc32.update(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return crc32.getValue();
    }

    private static void copyThroughSecondCompensationFailure(final Path source, final Path target) {
        SagaEventCodec codec = new SagaEventCodec();
        int compensationFailures = 0;
        try (FileDurableLog sourceLog = new FileDurableLog(DurableLogConfig.defaults(source.resolve("events")));
                FileDurableLog targetLog = new FileDurableLog(DurableLogConfig.defaults(target.resolve("events")))) {
            sourceLog.recover(RecoveryOptions.defaults());
            try (LogCursor cursor = sourceLog.openCursor(new LogStreamId("j7m.saga.events"), LogOffset.beginning(), CursorOptions.defaults())) {
                Optional<LogRecord> next = cursor.next();
                while (next.isPresent()) {
                    SagaEvent event = codec.decode(next.orElseThrow().payload());
                    LogRecord copy = new LogRecord(LogRecordHeader.data(Map.of(
                            "domain", "saga",
                            "eventType", event.type().name(),
                            "schemaVersion", SagaEventCodec.SCHEMA_VERSION)), codec.encode(event));
                    targetLog.append(new LogStreamId("j7m.saga.events"), List.of(copy), AppendOptions.defaults());
                    if (event.type() == SagaEventType.SAGA_COMPENSATION_FAILED) {
                        compensationFailures++;
                        if (compensationFailures == 2) {
                            assertEquals("0", event.fields().get("nextAttemptAt"));
                            return;
                        }
                    }
                    next = cursor.next();
                }
            }
        }
        throw new AssertionError("second compensation failure was not found");
    }
}
