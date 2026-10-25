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

package org.j7m.middleware.demo.saga;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.api.reliability.IdempotencyKey;
import org.j7m.middleware.api.saga.SagaCancelRequest;
import org.j7m.middleware.api.saga.SagaCancellationPolicy;
import org.j7m.middleware.api.saga.SagaCommandResult;
import org.j7m.middleware.api.saga.SagaCompensationResult;
import org.j7m.middleware.api.saga.SagaDefinition;
import org.j7m.middleware.api.saga.SagaDefinitionId;
import org.j7m.middleware.api.saga.SagaOptions;
import org.j7m.middleware.api.saga.SagaRetryPolicy;
import org.j7m.middleware.api.saga.SagaStartRequest;
import org.j7m.middleware.api.saga.SagaStartResult;
import org.j7m.middleware.api.saga.SagaStepDefinition;
import org.j7m.middleware.api.saga.SagaStepId;
import org.j7m.middleware.api.saga.SagaStepType;
import org.j7m.middleware.api.saga.SagaTimeoutPolicy;
import org.j7m.middleware.api.saga.TransactionContext;
import org.j7m.middleware.saga.LogBackedSagaService;
import org.j7m.middleware.saga.MutableSagaClock;
import org.j7m.middleware.saga.SagaServiceBuilder;

/**
 * Phase 6.7 local saga orchestration demo.
 */
public final class SagaDemo {
    // J7M teaching: this block demonstrates saga descriptor metadata without claiming distributed ACID or generic exactly-once behavior.
    private SagaDemo() {
    }

    /**
     * Runs the local deterministic saga demo.
     *
     * @param args ignored command-line arguments
     * @throws Exception if temporary storage, durable-log replay, or saga execution fails
     */
    public static void main(final String[] args) throws Exception {
        Path root = Files.createTempDirectory("j7m-saga-demo-");
        MutableSagaClock clock = new MutableSagaClock(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        try (LogBackedSagaService service = new SagaServiceBuilder()
                .directory(root.resolve("service"))
                .clock(clock)
                .build()) {
            // J7M teaching: the success saga definition gives the baseline transaction
            // context and ordered step flow; each step is local and durable-log-backed,
            // not a distributed ACID or XA transaction.
            SagaDefinitionId success = new SagaDefinitionId("demo-success");
            service.register(new SagaDefinition(success, List.of(
                    step("reserve"),
                    step("charge"),
                    step("ship"))));
            SagaStartResult successStart = service.start(new SagaStartRequest(success, SagaOptions.defaults(), context(), payload()));
            service.runUntilIdle(successStart.executionId());

            // J7M teaching: the failing definition proves reverse compensation; a
            // failed charge triggers compensation for already-completed steps instead
            // of rolling back arbitrary external systems.
            SagaDefinitionId failing = new SagaDefinitionId("demo-failing");
            service.register(new SagaDefinition(failing, List.of(step("reserve"), failStep("charge"))));
            SagaStartResult failedStart = service.start(new SagaStartRequest(failing, SagaOptions.defaults(), context(), payload()));
            service.runUntilIdle(failedStart.executionId());

            // J7M teaching: repeated compensation failure produces dead-letter
            // evidence so operators can repair work explicitly rather than hiding
            // unrecoverable side effects.
            SagaDefinitionId deadLetter = new SagaDefinitionId("demo-dead-letter");
            service.register(new SagaDefinition(deadLetter, List.of(deadLetterStep("reserve"), failStep("charge"))));
            SagaStartResult deadLetterStart = service.start(new SagaStartRequest(deadLetter, SagaOptions.defaults(), context(), payload()));
            service.runUntilIdle(deadLetterStart.executionId());

            // J7M teaching: idempotency metadata deduplicates saga starts for this
            // local service; it is evidence for retry discipline, not a generic
            // exactly-once guarantee.
            SagaDefinitionId duplicate = new SagaDefinitionId("demo-idempotent");
            service.register(new SagaDefinition(duplicate, List.of(step("reserve"))));
            SagaOptions idempotent = new SagaOptions(new IdempotencyKey("demo-idempotency"), SagaRetryPolicy.none(), SagaTimeoutPolicy.none(), null);
            SagaStartResult first = service.start(new SagaStartRequest(duplicate, idempotent, context(), payload()));
            SagaStartResult second = service.start(new SagaStartRequest(duplicate, idempotent, context(), payload()));
            service.runUntilIdle(first.executionId());

            // J7M teaching: the deterministic clock drives timeout behavior without
            // wall-clock sleeps, making retry and timeout evidence reproducible.
            SagaDefinitionId timed = new SagaDefinitionId("demo-timeout");
            service.register(new SagaDefinition(timed, List.of(step("hold"))));
            SagaStartResult timedStart = service.start(new SagaStartRequest(timed,
                    new SagaOptions(null, SagaRetryPolicy.none(), new SagaTimeoutPolicy(Duration.ofSeconds(1), Duration.ZERO), null),
                    context(),
                    payload()));
            clock.advance(Duration.ofSeconds(2));
            service.evaluateTimeouts();

            // J7M teaching: cancellation is explicit operator intent and is reported
            // separately from compensation, dead-letter, and timeout states.
            SagaDefinitionId cancelled = new SagaDefinitionId("demo-cancel");
            service.register(new SagaDefinition(cancelled, List.of(step("prepare"), step("commit"))));
            SagaStartResult cancelStart = service.start(new SagaStartRequest(cancelled,
                    new SagaOptions(null, SagaRetryPolicy.none(), SagaTimeoutPolicy.none(), new SagaCancellationPolicy(false, false)),
                    context(),
                    payload()));
            service.cancel(new SagaCancelRequest(cancelStart.executionId(), "operator-cancelled", false));

            System.out.println("phase6.7 saga demo: success=" + service.execution(successStart.executionId()).state());
            System.out.println("phase6.7 saga demo: compensation=" + service.execution(failedStart.executionId()).state());
            System.out.println("phase6.7 saga demo: deadLetters=" + service.status().deadLetters().size());
            System.out.println("phase6.7 saga demo: duplicateStart=" + second.duplicate());
            System.out.println("phase6.7 saga demo: timeout=" + service.execution(timedStart.executionId()).state());
            System.out.println("phase6.7 saga demo: cancellation=" + service.execution(cancelStart.executionId()).state());
            System.out.println("phase6.7 saga demo: outboxPending=" + service.status().metrics().outboxPending());
            System.out.println("phase6.7 saga demo: evidenceEntries=" + service.status().evidence().size());
        }
        try (LogBackedSagaService recovered = new SagaServiceBuilder()
                .directory(root.resolve("service"))
                .clock(clock)
                .build()) {
            // J7M teaching: reopening the service verifies durable recovery and
            // outbox evidence without requiring an external workflow engine.
            System.out.println("phase6.7 saga demo: recoveryEvents=" + recovered.status().recoveryReport().eventsReplayed());
            System.out.println("phase6.7 saga demo: recoveredExecutions=" + recovered.status().executions().size());
        }
    }

    private static SagaStepDefinition step(final String id) {
        return new SagaStepDefinition(new SagaStepId(id), SagaStepType.LOCAL,
                (context, command) -> SagaCommandResult.succeeded(),
                (context, command) -> SagaCompensationResult.succeeded(),
                SagaRetryPolicy.none(),
                SagaTimeoutPolicy.none());
    }

    private static SagaStepDefinition failStep(final String id) {
        return new SagaStepDefinition(new SagaStepId(id), SagaStepType.LOCAL,
                (context, command) -> SagaCommandResult.failed("DEMO_STEP_FAILED", false),
                (context, command) -> SagaCompensationResult.succeeded(),
                SagaRetryPolicy.none(),
                SagaTimeoutPolicy.none());
    }

    private static SagaStepDefinition deadLetterStep(final String id) {
        AtomicInteger attempts = new AtomicInteger();
        return new SagaStepDefinition(new SagaStepId(id), SagaStepType.LOCAL,
                (context, command) -> SagaCommandResult.succeeded(),
                (context, command) -> {
                    attempts.incrementAndGet();
                    return SagaCompensationResult.failed("DEMO_COMPENSATION_FAILED", false);
                },
                SagaRetryPolicy.none(),
                SagaTimeoutPolicy.none());
    }

    private static TransactionContext context() {
        return new TransactionContext(null, null, null, null, null, null, null, false, null, null);
    }

    private static byte[] payload() {
        return "payload-redacted-in-status".getBytes(StandardCharsets.UTF_8);
    }
}
