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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.api.saga.SagaCommandResult;
import org.j7m.middleware.api.saga.SagaCompensationResult;
import org.j7m.middleware.api.saga.SagaDefinition;
import org.j7m.middleware.api.saga.SagaDefinitionId;
import org.j7m.middleware.api.saga.SagaExecution;
import org.j7m.middleware.api.saga.SagaExecutionState;
import org.j7m.middleware.api.saga.SagaRetryPolicy;
import org.j7m.middleware.api.saga.SagaStepDefinition;
import org.j7m.middleware.api.saga.SagaStepId;
import org.j7m.middleware.api.saga.SagaStepState;
import org.j7m.middleware.api.saga.SagaStepType;
import org.j7m.middleware.api.saga.SagaTimeoutPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SagaFailureCompensationTest {
    @Test
    void failedStepCompensatesCompletedStepsInReverseOrder(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        List<String> executionOrder = SagaTestSupport.newOrder();
        List<String> compensationOrder = SagaTestSupport.newOrder();
        SagaDefinitionId id = new SagaDefinitionId("compensate");
        SagaDefinition definition = new SagaDefinition(id, List.of(
                SagaTestSupport.step("reserve", executionOrder, compensationOrder),
                SagaTestSupport.step("ship", executionOrder, compensationOrder),
                SagaTestSupport.failingStep("charge", false)));

        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(definition);
            SagaExecution execution = service.runUntilIdle(service.start(SagaTestSupport.request(id)).executionId());

            assertEquals(SagaExecutionState.COMPENSATED, execution.state());
            assertEquals(List.of("ship", "reserve"), compensationOrder);
            assertEquals(1, service.status().metrics().failedSagas());
        }
    }

    @Test
    void retryableCompensationFailureRemainsCompensatingAndRetriesSuccessfully(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        AtomicInteger compensations = new AtomicInteger();
        SagaDefinitionId id = new SagaDefinitionId("compensation-retry-success");
        SagaDefinition definition = new SagaDefinition(id, List.of(
                compensationStep("reserve", compensations, true, 1, Duration.ofSeconds(5L)),
                SagaTestSupport.failingStep("charge", false)));

        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(definition);
            org.j7m.middleware.api.saga.SagaExecutionId executionId = service.start(SagaTestSupport.request(id)).executionId();
            SagaExecution first = service.runUntilIdle(executionId);
            assertEquals(SagaExecutionState.COMPENSATING, first.state());
            assertEquals(SagaStepState.COMPENSATION_FAILED, first.steps().getFirst().state());
            assertEquals(1, compensations.get());

            clock.advance(Duration.ofSeconds(5L));
            SagaExecution recovered = service.runUntilIdle(executionId);
            assertEquals(SagaExecutionState.COMPENSATED, recovered.state());
            assertEquals(SagaStepState.COMPENSATED, recovered.steps().getFirst().state());
            assertEquals(2, compensations.get());
        }
    }

    @Test
    void retryableCompensationExhaustionDeadLettersExecution(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        AtomicInteger compensations = new AtomicInteger();
        SagaDefinitionId id = new SagaDefinitionId("compensation-retry-deadletter");
        SagaDefinition definition = new SagaDefinition(id, List.of(
                compensationStep("reserve", compensations, false, 1, Duration.ZERO),
                SagaTestSupport.failingStep("charge", false)));

        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(definition);
            org.j7m.middleware.api.saga.SagaExecutionId executionId = service.start(SagaTestSupport.request(id)).executionId();
            assertEquals(SagaExecutionState.COMPENSATING, service.runUntilIdle(executionId).state());

            SagaExecution terminal = service.runUntilIdle(executionId);
            assertEquals(SagaExecutionState.DEAD_LETTERED, terminal.state());
            assertEquals(SagaStepState.DEAD_LETTERED, terminal.steps().getFirst().state());
            assertEquals(2, compensations.get());
            assertEquals(1, service.status().deadLetters().size());
        }
    }

    private static SagaStepDefinition compensationStep(
            final String id,
            final AtomicInteger compensations,
            final boolean succeedOnRetry,
            final int retryCount,
            final Duration retryDelay) {
        return new SagaStepDefinition(new SagaStepId(id), SagaStepType.LOCAL,
                (context, command) -> SagaCommandResult.succeeded(),
                (context, command) -> {
                    int attempt = compensations.incrementAndGet();
                    if (succeedOnRetry && attempt > 1) {
                        return SagaCompensationResult.succeeded();
                    }
                    return SagaCompensationResult.failed("COMPENSATION_RETRYABLE", true);
                },
                new SagaRetryPolicy(retryCount, retryDelay),
                SagaTimeoutPolicy.none());
    }
}
