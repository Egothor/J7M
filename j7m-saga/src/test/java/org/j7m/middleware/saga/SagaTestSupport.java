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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.reliability.IdempotencyKey;
import org.j7m.middleware.api.saga.SagaCommandResult;
import org.j7m.middleware.api.saga.SagaCompensationResult;
import org.j7m.middleware.api.saga.SagaDefinition;
import org.j7m.middleware.api.saga.SagaDefinitionId;
import org.j7m.middleware.api.saga.SagaExecutionId;
import org.j7m.middleware.api.saga.SagaOptions;
import org.j7m.middleware.api.saga.SagaRetryPolicy;
import org.j7m.middleware.api.saga.SagaStartRequest;
import org.j7m.middleware.api.saga.SagaStepDefinition;
import org.j7m.middleware.api.saga.SagaStepId;
import org.j7m.middleware.api.saga.SagaStepState;
import org.j7m.middleware.api.saga.SagaStepType;
import org.j7m.middleware.api.saga.SagaTimeoutPolicy;
import org.j7m.middleware.api.saga.TransactionContext;

final class SagaTestSupport {
    static final byte[] SECRET_PAYLOAD = "secret-card=4111111111111111".getBytes(StandardCharsets.UTF_8);

    private SagaTestSupport() {
    }

    static MutableSagaClock clock() {
        return new MutableSagaClock(Instant.parse("2026-01-01T00:00:00Z"));
    }

    static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !java.nio.file.Files.exists(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new AssertionError("repository root not found");
        }
        return current;
    }

    static SagaConfig config(final Path directory, final MutableSagaClock clock) {
        return new SagaConfig(directory.resolve("events"), directory.resolve("messaging"), clock,
                32, 16, 3, 64, 1024, 64, 16);
    }

    static LogBackedSagaService service(final Path directory, final MutableSagaClock clock) {
        return new LogBackedSagaService(config(directory, clock));
    }

    static SagaDefinitionId definitionId(final String value) {
        return new SagaDefinitionId(value);
    }

    static SagaDefinition twoStepDefinition(final SagaDefinitionId id, final List<String> executionOrder, final List<String> compensationOrder) {
        return new SagaDefinition(id, List.of(
                step("reserve", executionOrder, compensationOrder),
                step("charge", executionOrder, compensationOrder)));
    }

    static SagaStepDefinition step(
            final String id,
            final List<String> executionOrder,
            final List<String> compensationOrder) {
        return new SagaStepDefinition(new SagaStepId(id), SagaStepType.LOCAL,
                (context, command) -> {
                    executionOrder.add(id + ":" + context.scope().name());
                    return SagaCommandResult.succeeded();
                },
                (context, command) -> {
                    compensationOrder.add(id);
                    return SagaCompensationResult.succeeded();
                },
                SagaRetryPolicy.none(),
                SagaTimeoutPolicy.none());
    }

    static SagaStepDefinition failingStep(final String id, final boolean retryable) {
        return new SagaStepDefinition(new SagaStepId(id), SagaStepType.LOCAL,
                (context, command) -> SagaCommandResult.failed("STEP_FAILED", retryable),
                (context, command) -> SagaCompensationResult.succeeded(),
                new SagaRetryPolicy(1, Duration.ofSeconds(5)),
                SagaTimeoutPolicy.none());
    }

    static SagaStepDefinition retryThenSucceedStep(final String id, final AtomicInteger attempts) {
        return new SagaStepDefinition(new SagaStepId(id), SagaStepType.LOCAL,
                (context, command) -> attempts.incrementAndGet() == 1
                        ? SagaCommandResult.failed("RETRY_ME", true)
                        : SagaCommandResult.succeeded(),
                (context, command) -> SagaCompensationResult.succeeded(),
                new SagaRetryPolicy(1, Duration.ofSeconds(5)),
                SagaTimeoutPolicy.none());
    }

    static SagaStepDefinition compensationDeadLetterStep(final String id) {
        return new SagaStepDefinition(new SagaStepId(id), SagaStepType.LOCAL,
                (context, command) -> SagaCommandResult.succeeded(),
                (context, command) -> SagaCompensationResult.failed("COMPENSATION_BROKEN", false),
                SagaRetryPolicy.none(),
                SagaTimeoutPolicy.none());
    }

    static SagaStartRequest request(final SagaDefinitionId id) {
        return new SagaStartRequest(id, SagaOptions.defaults(), new TransactionContext(null, null, null, null, null, null, null, false, null, null), SECRET_PAYLOAD);
    }

    static SagaStartRequest idempotentRequest(final SagaDefinitionId id, final String key) {
        return new SagaStartRequest(id,
                new SagaOptions(new IdempotencyKey(key), SagaRetryPolicy.none(), SagaTimeoutPolicy.none(), null),
                new TransactionContext(null, null, null, null, null, null, null, false, null, null),
                SECRET_PAYLOAD);
    }

    static boolean hasStepState(final LogBackedSagaService service, final SagaExecutionId executionId, final SagaStepId stepId, final SagaStepState state) {
        return service.execution(executionId).steps().stream()
                .anyMatch(step -> step.stepId().equals(stepId) && step.state() == state);
    }

    static List<String> newOrder() {
        return new ArrayList<>();
    }

    static MiddlewareErrorCode code(final Throwable throwable) {
        return ((org.j7m.middleware.api.saga.SagaException) throwable).remoteError().code();
    }
}
