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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.api.saga.SagaDefinition;
import org.j7m.middleware.api.saga.SagaDefinitionId;
import org.j7m.middleware.api.saga.SagaExecutionId;
import org.j7m.middleware.api.saga.SagaExecutionState;
import org.j7m.middleware.api.saga.SagaStepDefinition;
import org.j7m.middleware.api.saga.SagaStepId;
import org.j7m.middleware.api.saga.SagaStepType;
import org.j7m.middleware.api.saga.SagaRetryPolicy;
import org.j7m.middleware.api.saga.SagaTimeoutPolicy;
import org.j7m.middleware.api.saga.TransactionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TransactionContextPropagationTest {
    @Test
    void propagatesCorrelationAndCreatesChildOperationIds(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        List<TransactionContext> contexts = new ArrayList<>();
        SagaDefinitionId id = new SagaDefinitionId("context");
        SagaStepDefinition step = new SagaStepDefinition(new SagaStepId("step"), SagaStepType.LOCAL,
                (context, command) -> {
                    contexts.add(context);
                    assertTrue(command.metadata().containsKey("correlationId"));
                    assertTrue(command.metadata().containsKey("operationId"));
                    return org.j7m.middleware.api.saga.SagaCommandResult.succeeded();
                },
                (context, command) -> org.j7m.middleware.api.saga.SagaCompensationResult.succeeded(),
                null,
                null);
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(new SagaDefinition(id, List.of(step)));
            service.runUntilIdle(service.start(SagaTestSupport.request(id)).executionId());
        }
        assertEquals(1, contexts.size());
        assertEquals(org.j7m.middleware.api.saga.TransactionScope.STEP, contexts.getFirst().scope());
        assertNotEquals(contexts.getFirst().correlation().operationId(), contexts.getFirst().correlation().parentOperationId());
    }

    @Test
    void compensationOperationIdIsStableAcrossRetry(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        List<String> contextOperationIds = new ArrayList<>();
        List<String> commandOperationIds = new ArrayList<>();
        AtomicInteger compensations = new AtomicInteger();
        SagaDefinitionId id = new SagaDefinitionId("compensation-operation-id-retry");
        SagaStepDefinition compensated = new SagaStepDefinition(new SagaStepId("reserve"), SagaStepType.LOCAL,
                (context, command) -> org.j7m.middleware.api.saga.SagaCommandResult.succeeded(),
                (context, command) -> {
                    contextOperationIds.add(context.metadata().get("compensationOperationId"));
                    commandOperationIds.add(command.metadata().get("compensationOperationId"));
                    return compensations.incrementAndGet() == 1
                            ? org.j7m.middleware.api.saga.SagaCompensationResult.failed("RETRY", true)
                            : org.j7m.middleware.api.saga.SagaCompensationResult.succeeded();
                },
                new SagaRetryPolicy(1, Duration.ofSeconds(5L)),
                SagaTimeoutPolicy.none());
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(new SagaDefinition(id, List.of(compensated, SagaTestSupport.failingStep("charge", false))));
            SagaExecutionId executionId = service.start(SagaTestSupport.request(id)).executionId();
            assertEquals(SagaExecutionState.COMPENSATING, service.runUntilIdle(executionId).state());
            clock.advance(Duration.ofSeconds(5L));
            assertEquals(SagaExecutionState.COMPENSATED, service.runUntilIdle(executionId).state());
        }

        assertEquals(2, contextOperationIds.size());
        assertEquals(contextOperationIds.getFirst(), contextOperationIds.get(1));
        assertEquals(contextOperationIds, commandOperationIds);
    }

    @Test
    void compensationOperationIdIsStableAcrossRestart(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        List<String> operationIds = new ArrayList<>();
        AtomicInteger firstCompensations = new AtomicInteger();
        SagaDefinitionId id = new SagaDefinitionId("compensation-operation-id-restart");
        SagaExecutionId executionId;
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(new SagaDefinition(id, List.of(new SagaStepDefinition(new SagaStepId("reserve"), SagaStepType.LOCAL,
                    (context, command) -> org.j7m.middleware.api.saga.SagaCommandResult.succeeded(),
                    (context, command) -> {
                        operationIds.add(command.metadata().get("compensationOperationId"));
                        firstCompensations.incrementAndGet();
                        return org.j7m.middleware.api.saga.SagaCompensationResult.failed("RETRY", true);
                    },
                    new SagaRetryPolicy(1, Duration.ofSeconds(5L)),
                    SagaTimeoutPolicy.none()), SagaTestSupport.failingStep("charge", false))));
            executionId = service.start(SagaTestSupport.request(id)).executionId();
            assertEquals(SagaExecutionState.COMPENSATING, service.runUntilIdle(executionId).state());
            assertEquals(1, firstCompensations.get());
        }

        clock.advance(Duration.ofSeconds(5L));
        try (LogBackedSagaService recovered = SagaTestSupport.service(directory, clock)) {
            recovered.register(new SagaDefinition(id, List.of(new SagaStepDefinition(new SagaStepId("reserve"), SagaStepType.LOCAL,
                    (context, command) -> org.j7m.middleware.api.saga.SagaCommandResult.succeeded(),
                    (context, command) -> {
                        operationIds.add(command.metadata().get("compensationOperationId"));
                        return org.j7m.middleware.api.saga.SagaCompensationResult.succeeded();
                    },
                    new SagaRetryPolicy(1, Duration.ofSeconds(5L)),
                    SagaTimeoutPolicy.none()), SagaTestSupport.failingStep("charge", false))));
            assertEquals(SagaExecutionState.COMPENSATED, recovered.runUntilIdle(executionId).state());
        }

        assertEquals(2, operationIds.size());
        assertEquals(operationIds.getFirst(), operationIds.get(1));
    }
}
