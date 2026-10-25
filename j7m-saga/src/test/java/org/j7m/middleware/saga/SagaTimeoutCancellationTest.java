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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.j7m.middleware.api.saga.SagaCancelRequest;
import org.j7m.middleware.api.saga.SagaCancelResult;
import org.j7m.middleware.api.saga.SagaCancellationPolicy;
import org.j7m.middleware.api.saga.SagaCommandResult;
import org.j7m.middleware.api.saga.SagaDefinition;
import org.j7m.middleware.api.saga.SagaDefinitionId;
import org.j7m.middleware.api.saga.SagaExecutionState;
import org.j7m.middleware.api.saga.SagaOptions;
import org.j7m.middleware.api.saga.SagaRetryPolicy;
import org.j7m.middleware.api.saga.SagaStartRequest;
import org.j7m.middleware.api.saga.SagaStartResult;
import org.j7m.middleware.api.saga.SagaStepDefinition;
import org.j7m.middleware.api.saga.SagaStepId;
import org.j7m.middleware.api.saga.SagaStepState;
import org.j7m.middleware.api.saga.SagaStepType;
import org.j7m.middleware.api.saga.SagaTimeoutPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SagaTimeoutCancellationTest {
    @Test
    void sagaTimeoutUsesMutableClock(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("timeout");
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(SagaTestSupport.twoStepDefinition(id, SagaTestSupport.newOrder(), SagaTestSupport.newOrder()));
            SagaStartResult result = service.start(new SagaStartRequest(id,
                    new SagaOptions(null, SagaRetryPolicy.none(), new SagaTimeoutPolicy(Duration.ofSeconds(1), Duration.ZERO), null),
                    null, SagaTestSupport.SECRET_PAYLOAD));
            clock.advance(Duration.ofSeconds(2));
            service.evaluateTimeouts();
            assertEquals(SagaExecutionState.TIMED_OUT, service.execution(result.executionId()).state());
        }
    }

    @Test
    void cancellationPreventsNewDispatchWithoutCompensation(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("cancel");
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(SagaTestSupport.twoStepDefinition(id, SagaTestSupport.newOrder(), SagaTestSupport.newOrder()));
            SagaStartResult result = service.start(new SagaStartRequest(id,
                    new SagaOptions(null, SagaRetryPolicy.none(), SagaTimeoutPolicy.none(), new SagaCancellationPolicy(false, false)),
                    null, SagaTestSupport.SECRET_PAYLOAD));
            SagaCancelResult cancel = service.cancel(new SagaCancelRequest(result.executionId(), "operator", false));
            assertEquals(SagaExecutionState.CANCELLED, cancel.state());
        }
    }

    @Test
    void stepTimeoutIsDistinctDurableAndCompensatesCompletedSteps(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("step-timeout");
        List<String> executionOrder = SagaTestSupport.newOrder();
        List<String> compensationOrder = SagaTestSupport.newOrder();
        SagaStepDefinition first = SagaTestSupport.step("reserve", executionOrder, compensationOrder);
        SagaStepDefinition timeout = new SagaStepDefinition(new SagaStepId("charge"), SagaStepType.LOCAL,
                (context, command) -> {
                    executionOrder.add("charge");
                    clock.advance(Duration.ofSeconds(3));
                    return SagaCommandResult.succeeded();
                },
                (context, command) -> {
                    compensationOrder.add("charge");
                    return org.j7m.middleware.api.saga.SagaCompensationResult.succeeded();
                },
                SagaRetryPolicy.none(),
                new SagaTimeoutPolicy(Duration.ZERO, Duration.ofSeconds(1)));
        SagaStepDefinition never = SagaTestSupport.step("ship", executionOrder, compensationOrder);

        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(new SagaDefinition(id, List.of(first, timeout, never)));
            SagaStartResult result = service.start(new SagaStartRequest(id,
                    new SagaOptions(null, SagaRetryPolicy.none(), new SagaTimeoutPolicy(Duration.ofHours(1), Duration.ZERO), null),
                    null, SagaTestSupport.SECRET_PAYLOAD));

            assertFalse(SagaTestSupport.hasStepState(service, result.executionId(), new SagaStepId("charge"), SagaStepState.TIMED_OUT));
            service.runUntilIdle(result.executionId());

            assertTrue(SagaTestSupport.hasStepState(service, result.executionId(), new SagaStepId("charge"), SagaStepState.TIMED_OUT));
            assertFalse(executionOrder.contains("ship:STEP"));
            assertEquals(List.of("reserve"), compensationOrder);
            assertTrue(service.status().evidence().stream().anyMatch(event ->
                    event.action().equals("SAGA_STEP_TIMED_OUT")
                            && event.decision().equals("failed")
                            && event.reasonCode().equals("STEP_TIMEOUT")));
        }
        try (LogBackedSagaService recovered = SagaTestSupport.service(directory, clock)) {
            assertTrue(SagaTestSupport.hasStepState(recovered, recovered.status().executions().getFirst().executionId(),
                    new SagaStepId("charge"), SagaStepState.TIMED_OUT));
        }
    }
}
