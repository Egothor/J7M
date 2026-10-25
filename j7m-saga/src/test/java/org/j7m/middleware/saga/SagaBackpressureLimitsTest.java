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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.messaging.EnqueueOptions;
import org.j7m.middleware.api.messaging.MessageHeader;
import org.j7m.middleware.api.messaging.QueueMessage;
import org.j7m.middleware.api.messaging.QueueName;
import org.j7m.middleware.api.saga.SagaDefinition;
import org.j7m.middleware.api.saga.SagaDefinitionId;
import org.j7m.middleware.api.saga.SagaException;
import org.j7m.middleware.api.saga.SagaStartRequest;
import org.j7m.middleware.api.saga.SagaStepDefinition;
import org.j7m.middleware.api.saga.SagaStepId;
import org.j7m.middleware.api.saga.SagaStepType;
import org.j7m.middleware.api.saga.SagaRetryPolicy;
import org.j7m.middleware.api.saga.SagaTimeoutPolicy;
import org.j7m.middleware.messaging.DurableQueueFactory;
import org.j7m.middleware.messaging.LogBackedDurableQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SagaBackpressureLimitsTest {
    @Test
    void enforcesMaxActiveSagasAndPayloadSize(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        try (LogBackedSagaService service = new LogBackedSagaService(new SagaConfig(
                directory.resolve("events"), directory.resolve("messaging"), clock,
                1, 16, 3, 64, 64, 64, 16))) {
            SagaDefinitionId id = new SagaDefinitionId("limits");
            SagaDefinition definition = new SagaDefinition(id, java.util.List.of(SagaTestSupport.retryThenSucceedStep("pending", new java.util.concurrent.atomic.AtomicInteger())));
            service.register(definition);

            SagaException payload = assertThrows(SagaException.class,
                    () -> service.start(new SagaStartRequest(id, null, null, new byte[65])));
            assertEquals(MiddlewareErrorCode.OVERLOADED, payload.remoteError().code());

            service.start(SagaTestSupport.request(id));
            SagaException active = assertThrows(SagaException.class, () -> service.start(SagaTestSupport.request(id)));
            assertEquals(MiddlewareErrorCode.OVERLOADED, active.remoteError().code());
        }
    }

    @Test
    void enforcesDefinitionStepAndRetryLimits(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        try (LogBackedSagaService service = new LogBackedSagaService(new SagaConfig(
                directory.resolve("events"), directory.resolve("messaging"), clock,
                4, 1, 1, 64, 1024, 64, 16))) {
            SagaDefinitionId tooManySteps = new SagaDefinitionId("too-many-steps");
            SagaException steps = assertThrows(SagaException.class,
                    () -> service.register(SagaTestSupport.twoStepDefinition(tooManySteps,
                            SagaTestSupport.newOrder(), SagaTestSupport.newOrder())));
            assertEquals(MiddlewareErrorCode.OVERLOADED, steps.remoteError().code());

            SagaStepDefinition retryHeavy = new SagaStepDefinition(new SagaStepId("retry-heavy"), SagaStepType.LOCAL,
                    (context, command) -> org.j7m.middleware.api.saga.SagaCommandResult.failed("RETRY", true),
                    (context, command) -> org.j7m.middleware.api.saga.SagaCompensationResult.succeeded(),
                    new SagaRetryPolicy(2, Duration.ZERO), SagaTimeoutPolicy.none());
            SagaException retries = assertThrows(SagaException.class,
                    () -> service.register(new SagaDefinition(new SagaDefinitionId("too-many-retries"), List.of(retryHeavy))));
            assertEquals(MiddlewareErrorCode.OVERLOADED, retries.remoteError().code());
        }
    }

    @Test
    void statusExposureLimitsEvidenceAndDeadLetters(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        try (LogBackedSagaService service = new LogBackedSagaService(new SagaConfig(
                directory.resolve("events"), directory.resolve("messaging"), clock,
                4, 16, 3, 64, 1024, 3, 1))) {
            List<String> order = new ArrayList<>();
            SagaDefinitionId id = new SagaDefinitionId("limited-status");
            service.register(new SagaDefinition(id, List.of(
                    SagaTestSupport.compensationDeadLetterStep("first"),
                    SagaTestSupport.failingStep("fail", false))));
            service.runUntilIdle(service.start(SagaTestSupport.request(id)).executionId());

            assertTrue(service.status().evidence().size() <= 3);
            assertTrue(service.status().deadLetters().size() <= 1);
            assertTrue(order.isEmpty());
        }
    }

    @Test
    void enforcesPendingOutboxLimitWithExactErrorCode(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        Path messaging = directory.resolve("messaging");
        try (LogBackedDurableQueue queue = DurableQueueFactory.open(messaging)) {
            queue.record(new QueueName("saga-commands"),
                    new QueueMessage(MessageHeader.empty(), "pending".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    EnqueueOptions.defaults());
            assertEquals(1, queue.outboxStatus().pending());
        }
        try (LogBackedSagaService service = new LogBackedSagaService(new SagaConfig(
                directory.resolve("events"), messaging, clock,
                4, 16, 3, 1, 1024, 64, 16))) {
            SagaDefinitionId id = new SagaDefinitionId("outbox-limit");
            service.register(new SagaDefinition(id,
                    List.of(SagaTestSupport.step("step", SagaTestSupport.newOrder(), SagaTestSupport.newOrder()))));
            SagaException failure = assertThrows(SagaException.class,
                    () -> service.runUntilIdle(service.start(SagaTestSupport.request(id)).executionId()));
            assertEquals(MiddlewareErrorCode.OVERLOADED, failure.remoteError().code());
        }
    }
}
