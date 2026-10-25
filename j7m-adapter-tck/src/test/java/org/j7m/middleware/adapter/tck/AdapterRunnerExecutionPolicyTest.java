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

package org.j7m.middleware.adapter.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.j7m.middleware.adapter.spi.AdapterCompatibilityLevel;
import org.j7m.middleware.adapter.spi.AdapterFailureMode;
import org.j7m.middleware.adapter.spi.AdapterFailureModeResult;
import org.j7m.middleware.adapter.spi.AdapterReferenceOperation;
import org.j7m.middleware.adapter.spi.AdapterReferenceOutcome;
import org.j7m.middleware.adapter.spi.AdapterReferenceSession;
import org.j7m.middleware.adapter.spi.AdapterSemanticProbe;
import org.j7m.middleware.adapter.spi.AdapterSemanticProfile;
import org.j7m.middleware.adapter.spi.AdapterTckProfile;
import org.j7m.middleware.adapter.spi.AdapterTckReport;
import org.j7m.middleware.adapter.spi.AdapterTckResult;
import org.j7m.middleware.adapter.spi.AdapterTckScenario;
import org.junit.jupiter.api.Test;

final class AdapterRunnerExecutionPolicyTest {
    @Test
    void selfReportedPassWithoutReferenceEvidenceIsRejected() {
        final FakeAdapters.FakeAdapter adapter = FakeAdapters.pass(AdapterSemanticProfile.messaging());
        final AdapterTckReport report = new AdapterTckRunner().run(
                adapter,
                new LyingProbe(),
                AdapterTckProfiles.messaging());

        assertEquals(AdapterCompatibilityLevel.REJECTED, report.summary().compatibilityLevel());
        assertTrue(report.results().stream()
                .anyMatch(result -> result.evidence().toString().contains("REFERENCE_OPERATION_COUNT_MISMATCH")));
    }

    @Test
    void echoingLiarWithoutReferenceOperationsIsRejected() {
        final FakeAdapters.FakeAdapter adapter = FakeAdapters.pass(AdapterSemanticProfile.messaging());
        final AdapterTckReport report = new AdapterTckRunner().run(
                adapter,
                new EchoingLiarProbe(),
                AdapterTckProfiles.messaging());

        assertEquals(AdapterCompatibilityLevel.REJECTED, report.summary().compatibilityLevel());
        assertTrue(report.results().stream()
                .anyMatch(result -> result.evidence().toString().contains("REFERENCE_OPERATION_COUNT_MISMATCH")));
    }

    @Test
    void fabricatedReferenceDigestsWithoutRecordedEffectsAreRejected() {
        final FakeAdapters.FakeAdapter adapter = FakeAdapters.pass(AdapterSemanticProfile.messaging());
        final AdapterTckReport report = new AdapterTckRunner().run(
                adapter,
                new FabricatingNoOpProbe(),
                AdapterTckProfiles.messaging());

        assertEquals(AdapterCompatibilityLevel.REJECTED, report.summary().compatibilityLevel());
        assertTrue(report.results().stream()
                .anyMatch(result -> result.evidence().toString().contains("REFERENCE_EFFECT_COUNT_MISMATCH")));
    }

    @Test
    void fabricatedRecordedReferenceEffectsAreRejected() {
        final FakeAdapters.FakeAdapter adapter = FakeAdapters.pass(AdapterSemanticProfile.messaging());
        final AdapterTckReport report = new AdapterTckRunner().run(
                adapter,
                new FabricatingEffectProbe(),
                AdapterTckProfiles.messaging());

        assertEquals(AdapterCompatibilityLevel.REJECTED, report.summary().compatibilityLevel());
        assertTrue(report.results().stream()
                .anyMatch(result -> result.evidence().toString().contains("REFERENCE_EFFECT_OBSERVABLE_MISMATCH")));
    }

    @Test
    void payloadEchoReferenceEffectsAreRejected() {
        final FakeAdapters.FakeAdapter adapter = FakeAdapters.pass(AdapterSemanticProfile.messaging());
        final AdapterTckReport report = new AdapterTckRunner().run(
                adapter,
                new PayloadEchoProbe(),
                AdapterTckProfiles.messaging());

        assertEquals(AdapterCompatibilityLevel.REJECTED, report.summary().compatibilityLevel());
        assertTrue(report.results().stream()
                .anyMatch(result -> result.evidence().toString().contains("REFERENCE_EFFECT_OBSERVABLE_MISMATCH")));
    }

    @Test
    void hungProbeTimesOutAndReceivesCancellation() throws InterruptedException {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final AdapterTckRunner.ExecutionPolicy policy = new AdapterTckRunner.ExecutionPolicy(
                Duration.ofMillis(25),
                Duration.ofMillis(250),
                4,
                32,
                64,
                128);
        final AdapterTckRunner runner = new AdapterTckRunner(Clock.systemUTC(), 11L, policy);
        final AdapterTckScenario scenario = AdapterTckProfiles.durableLog().scenarios().getFirst();
        final AdapterTckProfile profile = new AdapterTckProfile(
                "single-timeout-tck",
                AdapterSemanticProfile.durableLog(),
                List.of(scenario));

        final AdapterTckReport report = runner.run(
                FakeAdapters.pass(AdapterSemanticProfile.durableLog()),
                new BlockingProbe(started, interrupted),
                profile);

        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        assertEquals(AdapterCompatibilityLevel.REJECTED, report.summary().compatibilityLevel());
        assertTrue(report.results().stream()
                .anyMatch(result -> result.evidence().toString().contains("PROBE_TIMEOUT")));
    }

    private static final class LyingProbe implements AdapterSemanticProbe {
        @Override
        public AdapterTckResult runScenario(final AdapterTckScenario scenario) {
            return AdapterTckResult.passed(scenario);
        }

        @Override
        public AdapterFailureModeResult runFailureMode(final AdapterFailureMode failureMode) {
            return new AdapterFailureModeResult(failureMode, true, "HANDLED", Map.of());
        }

        @Override
        public boolean leaksSensitiveData(final String sentinel) {
            return false;
        }
    }

    private static final class EchoingLiarProbe implements AdapterSemanticProbe {
        @Override
        public AdapterTckResult runScenario(final AdapterTckScenario scenario) {
            return new AdapterTckResult(
                    scenario.id(),
                    true,
                    false,
                    "PASSED",
                    AdapterCompatibilityLevel.NATIVE_EQUIVALENT,
                    Map.of("referenceOutcome", "ref-echo", "referenceTranscript", "ref-transcript-echo"));
        }

        @Override
        public AdapterFailureModeResult runFailureMode(final AdapterFailureMode failureMode) {
            return new AdapterFailureModeResult(failureMode, true, "HANDLED", Map.of());
        }

        @Override
        public boolean leaksSensitiveData(final String sentinel) {
            return false;
        }
    }

    private static final class FabricatingNoOpProbe implements AdapterSemanticProbe {
        @Override
        public AdapterTckResult runScenario(final AdapterTckScenario scenario) {
            return new AdapterTckResult(
                    scenario.id(),
                    true,
                    false,
                    "PASSED",
                    AdapterCompatibilityLevel.NATIVE_EQUIVALENT,
                    Map.of("observable", "fabricated"));
        }

        @Override
        public List<AdapterReferenceOutcome> runReferenceOperations(
                final AdapterTckScenario scenario,
                final List<AdapterReferenceOperation> operations,
                final AdapterReferenceSession session) {
            final ArrayList<AdapterReferenceOutcome> outcomes = new ArrayList<>(operations.size());
            for (AdapterReferenceOperation operation : operations) {
                outcomes.add(new AdapterReferenceOutcome(
                        operation.operationId(),
                        true,
                        AdapterTckRunner.referenceDigestFor(operation),
                        Map.of("operation", "fabricated")));
            }
            return List.copyOf(outcomes);
        }

        @Override
        public AdapterFailureModeResult runFailureMode(final AdapterFailureMode failureMode) {
            return new AdapterFailureModeResult(failureMode, true, "HANDLED", Map.of());
        }

        @Override
        public boolean leaksSensitiveData(final String sentinel) {
            return false;
        }
    }

    private static final class FabricatingEffectProbe implements AdapterSemanticProbe {
        @Override
        public AdapterTckResult runScenario(final AdapterTckScenario scenario) {
            return new AdapterTckResult(
                    scenario.id(),
                    true,
                    false,
                    "PASSED",
                    AdapterCompatibilityLevel.NATIVE_EQUIVALENT,
                    Map.of("observable", "fabricated"));
        }

        @Override
        public List<AdapterReferenceOutcome> runReferenceOperations(
                final AdapterTckScenario scenario,
                final List<AdapterReferenceOperation> operations,
                final AdapterReferenceSession session) {
            final ArrayList<AdapterReferenceOutcome> outcomes = new ArrayList<>(operations.size());
            for (AdapterReferenceOperation operation : operations) {
                outcomes.add(session.recordEffect(operation, "fabricated-observation"));
            }
            return List.copyOf(outcomes);
        }

        @Override
        public AdapterFailureModeResult runFailureMode(final AdapterFailureMode failureMode) {
            return new AdapterFailureModeResult(failureMode, true, "HANDLED", Map.of());
        }

        @Override
        public boolean leaksSensitiveData(final String sentinel) {
            return false;
        }
    }

    private static final class PayloadEchoProbe implements AdapterSemanticProbe {
        @Override
        public AdapterTckResult runScenario(final AdapterTckScenario scenario) {
            return new AdapterTckResult(
                    scenario.id(),
                    true,
                    false,
                    "PASSED",
                    AdapterCompatibilityLevel.NATIVE_EQUIVALENT,
                    Map.of("observable", "payload-echo"));
        }

        @Override
        public List<AdapterReferenceOutcome> runReferenceOperations(
                final AdapterTckScenario scenario,
                final List<AdapterReferenceOperation> operations,
                final AdapterReferenceSession session) {
            final ArrayList<AdapterReferenceOutcome> outcomes = new ArrayList<>(operations.size());
            for (AdapterReferenceOperation operation : operations) {
                outcomes.add(session.recordEffect(operation, operation.payload()));
            }
            return List.copyOf(outcomes);
        }

        @Override
        public AdapterFailureModeResult runFailureMode(final AdapterFailureMode failureMode) {
            return new AdapterFailureModeResult(failureMode, true, "HANDLED", Map.of());
        }

        @Override
        public boolean leaksSensitiveData(final String sentinel) {
            return false;
        }
    }

    private static final class BlockingProbe implements AdapterSemanticProbe {
        private final CountDownLatch started;
        private final CountDownLatch interrupted;

        BlockingProbe(final CountDownLatch started, final CountDownLatch interrupted) {
            this.started = started;
            this.interrupted = interrupted;
        }

        @Override
        public AdapterTckResult runScenario(final AdapterTckScenario scenario) {
            this.started.countDown();
            try {
                this.interrupted.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                this.interrupted.countDown();
            }
            return AdapterTckResult.failed(scenario, "INTERRUPTED");
        }

        @Override
        public AdapterFailureModeResult runFailureMode(final AdapterFailureMode failureMode) {
            return new AdapterFailureModeResult(failureMode, true, "HANDLED", Map.of());
        }

        @Override
        public boolean leaksSensitiveData(final String sentinel) {
            return false;
        }
    }
}
