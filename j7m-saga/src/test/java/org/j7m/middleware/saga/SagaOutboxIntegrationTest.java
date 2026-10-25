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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.messaging.EnqueueOptions;
import org.j7m.middleware.api.messaging.MessageHeader;
import org.j7m.middleware.api.messaging.QueueMessage;
import org.j7m.middleware.api.messaging.QueueName;
import org.j7m.middleware.api.saga.SagaException;
import org.j7m.middleware.api.saga.SagaDefinition;
import org.j7m.middleware.api.saga.SagaDefinitionId;
import org.j7m.middleware.api.saga.SagaExecutionState;
import org.j7m.middleware.messaging.DurableQueueFactory;
import org.j7m.middleware.messaging.LogBackedDurableQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SagaOutboxIntegrationTest {
    @Test
    void recordsAndPublishesStepCommandIntent(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("outbox");
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(SagaTestSupport.twoStepDefinition(id, SagaTestSupport.newOrder(), SagaTestSupport.newOrder()));
            service.runUntilIdle(service.start(SagaTestSupport.request(id)).executionId());

            assertEquals(0, service.status().metrics().outboxPending());
            assertTrue(service.status().evidence().stream().anyMatch(event ->
                    event.action().equals("SAGA_OUTBOX_INTENT_RECORDED")
                            && event.decision().equals("recorded")
                            && event.reasonCode().equals("OUTBOX_INTENT_RECORDED")));
            assertTrue(service.status().evidence().stream().anyMatch(event ->
                    event.action().equals("SAGA_OUTBOX_PUBLISHED")
                            && event.decision().equals("published")
                            && event.reasonCode().equals("OUTBOX_PUBLISHED")));
            assertTrue(service.status().evidence().stream().anyMatch(event -> event.action().equals("SAGA_STEP_SUCCEEDED")));
            assertFalse(service.status().toString().contains(new String(SagaTestSupport.SECRET_PAYLOAD, java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    @Test
    void crashRecoveryPreservesOutboxBackedExecutionState(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("outbox-recovery");
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(SagaTestSupport.twoStepDefinition(id, SagaTestSupport.newOrder(), SagaTestSupport.newOrder()));
            service.runUntilIdle(service.start(SagaTestSupport.request(id)).executionId());
        }
        try (LogBackedSagaService recovered = SagaTestSupport.service(directory, clock)) {
            assertEquals(1, recovered.status().metrics().recoveries());
            assertEquals(1, recovered.status().executions().size());
        }
    }

    @Test
    void completedExecutionDoesNotRedispatchAfterRecovery(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("outbox-no-duplicate");
        AtomicInteger attempts = new AtomicInteger();
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(new SagaDefinition(id,
                    List.of(SagaTestSupport.retryThenSucceedStep("step", attempts))));
            service.runUntilIdle(service.start(SagaTestSupport.request(id)).executionId());
            clock.advance(java.time.Duration.ofSeconds(10));
            service.runUntilIdle(service.status().executions().getFirst().executionId());
            assertEquals(2, attempts.get());
        }
        AtomicInteger recoveredAttempts = new AtomicInteger();
        try (LogBackedSagaService recovered = SagaTestSupport.service(directory, clock)) {
            recovered.register(new SagaDefinition(id,
                    List.of(SagaTestSupport.retryThenSucceedStep("step", recoveredAttempts))));
            recovered.runUntilIdle(recovered.status().executions().getFirst().executionId());
            assertEquals(0, recoveredAttempts.get());
            assertEquals(0, recovered.status().metrics().outboxPending());
        }
    }

    @Test
    void crashAfterIntentBeforeRelayRecoversPendingCommand(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("outbox-crash-after-intent");
        AtomicInteger attempts = new AtomicInteger();
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.setOutboxExecutionMode(SagaOutboxExecutionMode.STOP_AFTER_INTENT);
            service.register(new SagaDefinition(id, List.of(SagaTestSupport.retryThenSucceedStep("step", attempts))));
            SagaException failure = assertThrows(SagaException.class,
                    () -> service.runUntilIdle(service.start(SagaTestSupport.request(id)).executionId()));
            assertEquals(MiddlewareErrorCode.UNAVAILABLE, failure.remoteError().code());
            assertEquals(0, attempts.get());
        }

        try (LogBackedSagaService recovered = SagaTestSupport.service(directory, clock)) {
            assertEquals(1, recovered.status().metrics().outboxPending());
            recovered.register(new SagaDefinition(id, List.of(SagaTestSupport.retryThenSucceedStep("step", attempts))));
            recovered.runUntilIdle(recovered.status().executions().getFirst().executionId());
            assertEquals(1, attempts.get());
            assertEquals(0, recovered.status().metrics().outboxPending());
            assertTrue(recovered.status().evidence().stream()
                    .anyMatch(event -> event.action().equals("SAGA_OUTBOX_PUBLISHED")
                            && event.reasonCode().equals("OUTBOX_PUBLISHED")));
            assertFalse(recovered.status().toString().contains(new String(SagaTestSupport.SECRET_PAYLOAD, java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    @Test
    void crashAfterRelayBeforePublishMarkerCompletesWithoutDuplicateCommand(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("outbox-crash-after-relay");
        AtomicInteger attempts = new AtomicInteger();
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.setOutboxExecutionMode(SagaOutboxExecutionMode.STOP_AFTER_RELAY);
            service.register(new SagaDefinition(id, List.of(SagaTestSupport.retryThenSucceedStep("step", attempts))));
            assertThrows(SagaException.class,
                    () -> service.runUntilIdle(service.start(SagaTestSupport.request(id)).executionId()));
            assertEquals(0, attempts.get());
            assertEquals(0, service.status().metrics().outboxPending());
        }

        try (LogBackedSagaService recovered = SagaTestSupport.service(directory, clock)) {
            assertEquals(0, recovered.status().metrics().outboxPending());
            recovered.register(new SagaDefinition(id, List.of(SagaTestSupport.retryThenSucceedStep("step", attempts))));
            recovered.runUntilIdle(recovered.status().executions().getFirst().executionId());
            assertEquals(1, attempts.get());
            assertTrue(recovered.status().evidence().stream()
                    .anyMatch(event -> event.action().equals("SAGA_OUTBOX_PUBLISHED")
                            && event.metadata().containsKey("relayCount")));
        }
    }

    @Test
    void crashAfterPublishMarkerSuppressesDuplicateDispatchAfterRecovery(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("outbox-crash-after-publish");
        AtomicInteger attempts = new AtomicInteger();
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.setOutboxExecutionMode(SagaOutboxExecutionMode.STOP_AFTER_PUBLISH);
            service.register(new SagaDefinition(id, List.of(SagaTestSupport.retryThenSucceedStep("step", attempts))));
            assertThrows(SagaException.class,
                    () -> service.runUntilIdle(service.start(SagaTestSupport.request(id)).executionId()));
            assertEquals(0, attempts.get());
        }

        try (LogBackedSagaService recovered = SagaTestSupport.service(directory, clock)) {
            recovered.register(new SagaDefinition(id, List.of(SagaTestSupport.retryThenSucceedStep("step", attempts))));
            recovered.runUntilIdle(recovered.status().executions().getFirst().executionId());
            assertEquals(1, attempts.get());
            assertTrue(recovered.status().evidence().stream()
                    .anyMatch(event -> event.action().equals("SAGA_OUTBOX_DUPLICATE_SUPPRESSED")
                            && event.reasonCode().equals("OUTBOX_DUPLICATE_SUPPRESSED")));
        }
    }

    @Test
    void publishedDuplicateIsSuppressedWhenPendingCapacityIsFull(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("outbox-published-capacity-full");
        List<String> firstRunOrder = SagaTestSupport.newOrder();
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.setOutboxExecutionMode(SagaOutboxExecutionMode.STOP_AFTER_PUBLISH);
            service.register(new SagaDefinition(id, List.of(SagaTestSupport.step(
                    "step",
                    firstRunOrder,
                    SagaTestSupport.newOrder()))));
            assertThrows(SagaException.class,
                    () -> service.runUntilIdle(service.start(SagaTestSupport.request(id)).executionId()));
            assertTrue(firstRunOrder.isEmpty());
        }

        Path messaging = directory.resolve("messaging");
        try (LogBackedDurableQueue queue = DurableQueueFactory.open(messaging)) {
            queue.record(new QueueName("saga-commands"),
                    new QueueMessage(MessageHeader.empty(), "pending".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    EnqueueOptions.defaults());
            assertEquals(1, queue.outboxStatus().pending());
        }

        try (LogBackedSagaService recovered = new LogBackedSagaService(new SagaConfig(
                directory.resolve("events"), messaging, clock,
                4, 16, 3, 1, 1024, 64, 16))) {
            List<String> recoveredOrder = SagaTestSupport.newOrder();
            recovered.register(new SagaDefinition(id, List.of(SagaTestSupport.step(
                    "step",
                    recoveredOrder,
                    SagaTestSupport.newOrder()))));
            recovered.runUntilIdle(recovered.status().executions().getFirst().executionId());

            assertEquals(List.of("step:STEP"), recoveredOrder);
            assertEquals(1, recovered.status().metrics().outboxPending());
            assertTrue(recovered.status().evidence().stream()
                    .anyMatch(event -> event.action().equals("SAGA_OUTBOX_DUPLICATE_SUPPRESSED")
                            && event.reasonCode().equals("OUTBOX_DUPLICATE_SUPPRESSED")));
        }
    }

    @Test
    void relayFailureIsDurableAndRetryCanPublishAfterRecovery(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("outbox-relay-failure");
        AtomicInteger attempts = new AtomicInteger();
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.setOutboxExecutionMode(SagaOutboxExecutionMode.FAIL_RELAY);
            service.register(new SagaDefinition(id, List.of(SagaTestSupport.retryThenSucceedStep("step", attempts))));
            assertThrows(SagaException.class,
                    () -> service.runUntilIdle(service.start(SagaTestSupport.request(id)).executionId()));
            assertEquals(0, attempts.get());
            assertTrue(service.status().evidence().stream()
                    .anyMatch(event -> event.action().equals("SAGA_OUTBOX_RELAY_FAILED")
                            && event.reasonCode().equals("OUTBOX_RELAY_FAILED")));
        }

        try (LogBackedSagaService recovered = SagaTestSupport.service(directory, clock)) {
            assertTrue(recovered.status().evidence().stream()
                    .anyMatch(event -> event.action().equals("SAGA_OUTBOX_RELAY_FAILED")));
            recovered.register(new SagaDefinition(id, List.of(SagaTestSupport.retryThenSucceedStep("step", attempts))));
            recovered.runUntilIdle(recovered.status().executions().getFirst().executionId());
            assertEquals(1, attempts.get());
            assertTrue(recovered.status().evidence().stream()
                    .anyMatch(event -> event.action().equals("SAGA_OUTBOX_PUBLISHED")));
        }
    }

    @Test
    void relayRetryExhaustionDeadLettersSafely(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("outbox-relay-deadletter");
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.setOutboxExecutionMode(SagaOutboxExecutionMode.EXHAUST_RELAY);
            service.register(SagaTestSupport.twoStepDefinition(id, SagaTestSupport.newOrder(), SagaTestSupport.newOrder()));
            service.runUntilIdle(service.start(SagaTestSupport.request(id)).executionId());
            assertEquals(SagaExecutionState.DEAD_LETTERED, service.status().executions().getFirst().state());
            assertEquals(1, service.status().deadLetters().size());
            assertTrue(service.status().evidence().stream()
                    .anyMatch(event -> event.action().equals("SAGA_OUTBOX_DEAD_LETTERED")
                            && event.reasonCode().equals("OUTBOX_RELAY_DEAD_LETTERED")));
            assertFalse(service.status().toString().contains(new String(SagaTestSupport.SECRET_PAYLOAD, java.nio.charset.StandardCharsets.UTF_8)));
        }

        try (LogBackedSagaService recovered = SagaTestSupport.service(directory, clock)) {
            assertEquals(SagaExecutionState.DEAD_LETTERED, recovered.status().executions().getFirst().state());
            assertEquals(1, recovered.status().deadLetters().size());
        }
    }
}
