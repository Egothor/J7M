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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.j7m.middleware.adapter.spi.AdapterCapability;
import org.j7m.middleware.adapter.spi.AdapterCapabilityDescriptor;
import org.j7m.middleware.adapter.spi.AdapterCompatibilityLevel;
import org.j7m.middleware.adapter.spi.AdapterCompatibilityReport;
import org.j7m.middleware.adapter.spi.AdapterConformanceResult;
import org.j7m.middleware.adapter.spi.AdapterEvidence;
import org.j7m.middleware.adapter.spi.AdapterFailureMode;
import org.j7m.middleware.adapter.spi.AdapterFailureModeResult;
import org.j7m.middleware.adapter.spi.AdapterProvider;
import org.j7m.middleware.adapter.spi.AdapterProviderId;
import org.j7m.middleware.adapter.spi.AdapterReferenceOperation;
import org.j7m.middleware.adapter.spi.AdapterReferenceOutcome;
import org.j7m.middleware.adapter.spi.AdapterReferenceSession;
import org.j7m.middleware.adapter.spi.AdapterRedactionPolicy;
import org.j7m.middleware.adapter.spi.AdapterResourceProfile;
import org.j7m.middleware.adapter.spi.AdapterSecurityProfile;
import org.j7m.middleware.adapter.spi.AdapterSemanticMatrix;
import org.j7m.middleware.adapter.spi.AdapterSemanticProbe;
import org.j7m.middleware.adapter.spi.AdapterTckProfile;
import org.j7m.middleware.adapter.spi.AdapterTckReport;
import org.j7m.middleware.adapter.spi.AdapterTckResult;
import org.j7m.middleware.adapter.spi.AdapterTckScenario;

/**
 * Deterministic runner for adapter compatibility scenarios.
 *
 * <p>The runner is immutable and thread-safe when supplied with a thread-safe
 * {@link Clock}. It does not start external services, does not know vendor SDK
 * types, and does not weaken J7M-native semantics. Required unsupported
 * scenarios, security hook gaps, redaction failures, and stronger-than-proven
 * claims all produce rejected reports.</p>
 *
 * <p>The runner lifecycle is per compatibility run: construct a runner, execute
 * one or more profiles, and publish immutable reports. Scenario execution is
 * deterministic and CPU-local for the built-in fake probes; it must not block
 * on external brokers, container runtimes, cloud services, or vendor products.
 * Public failures are normalized as rejected TCK results rather than raw
 * adapter exceptions, and report metadata is redacted before it leaves the
 * runner.</p>
 */
public final class AdapterTckRunner {
    private static final String SENTINEL = "J7M_SENTINEL_SECRET_TOKEN";
    private static final String REFERENCE_TRANSCRIPT_KEY = "referenceTranscript";
    private static final String REFERENCE_OPERATION_PREFIX = "tck-ref";
    private static final int HARD_MAX_SCENARIOS = 512;
    private static final int HARD_MAX_FAILURE_MODES = 64;
    private static final int HARD_MAX_REPORT_ENTRIES = 1_024;
    private static final int HARD_MAX_TEXT_LENGTH = 512;
    private static final Duration HARD_MAX_OPERATION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration HARD_MAX_CLEANUP_TIMEOUT = Duration.ofSeconds(10);

    private final Clock clock;
    private final AdapterSemanticMatrix matrix;
    private final long deterministicSeed;
    private final ExecutionPolicy executionPolicy;

    /**
     * Runner-owned execution limits for a TCK run.
     *
     * <p>The policy is immutable and thread-safe. Values are clamped by hard
     * platform ceilings so custom profiles cannot turn the TCK into an
     * unbounded workload. Operation timeouts apply to provider inspection,
     * semantic probes, failure-mode probes, redaction probes, and provider
     * cleanup. Cleanup timeout bounds executor shutdown after cancellation has
     * been requested.</p>
     *
     * @param operationTimeout maximum time allowed for one provider or probe operation
     * @param cleanupTimeout maximum time spent waiting for cancelled TCK workers
     * @param maxScenarios maximum scenarios accepted in one profile
     * @param maxFailureModes maximum failure-mode checks accepted in one run
     * @param maxReportEntries maximum retained findings and report entries per list
     * @param maxTextLength maximum retained diagnostic text length
     */
    public record ExecutionPolicy(
            Duration operationTimeout,
            Duration cleanupTimeout,
            int maxScenarios,
            int maxFailureModes,
            int maxReportEntries,
            int maxTextLength) {
        /**
         * Creates a runner execution policy.
         *
         * @throws IllegalArgumentException if a limit is non-positive or exceeds the hard TCK ceiling
         */
        public ExecutionPolicy {
            operationTimeout = requireDuration(operationTimeout, "operation timeout", HARD_MAX_OPERATION_TIMEOUT);
            cleanupTimeout = requireDuration(cleanupTimeout, "cleanup timeout", HARD_MAX_CLEANUP_TIMEOUT);
            maxScenarios = requireBounded(maxScenarios, "maximum scenarios", HARD_MAX_SCENARIOS);
            maxFailureModes = requireBounded(maxFailureModes, "maximum failure modes", HARD_MAX_FAILURE_MODES);
            maxReportEntries = requireBounded(maxReportEntries, "maximum report entries", HARD_MAX_REPORT_ENTRIES);
            maxTextLength = requireBounded(maxTextLength, "maximum text length", HARD_MAX_TEXT_LENGTH);
        }

        /**
         * Returns the default bounded execution policy.
         *
         * @return default execution policy
         */
        public static ExecutionPolicy defaults() {
            return new ExecutionPolicy(Duration.ofSeconds(2), Duration.ofMillis(250), 256, 32, 512, 256);
        }
    }

    /**
     * Creates a TCK runner using the system UTC clock.
     */
    public AdapterTckRunner() {
        this(Clock.systemUTC(), 0x6_9L, ExecutionPolicy.defaults());
    }

    /**
     * Creates a TCK runner.
     *
     * @param clock clock used for report timestamps
     * @param deterministicSeed deterministic seed recorded in reports
     */
    public AdapterTckRunner(final Clock clock, final long deterministicSeed) {
        this(clock, deterministicSeed, ExecutionPolicy.defaults());
    }

    /**
     * Creates a TCK runner with explicit execution bounds.
     *
     * @param clock clock used for report timestamps
     * @param deterministicSeed deterministic seed recorded in reports
     * @param executionPolicy runner-owned deadlines and report bounds
     */
    public AdapterTckRunner(
            final Clock clock,
            final long deterministicSeed,
            final ExecutionPolicy executionPolicy) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.matrix = new AdapterSemanticMatrix();
        this.deterministicSeed = deterministicSeed;
        this.executionPolicy = Objects.requireNonNull(executionPolicy, "execution policy");
    }

    /**
     * Runs a complete profile against an adapter provider and semantic probe.
     *
     * @param provider adapter provider under test
     * @param probe deterministic semantic probe
     * @param profile TCK profile to execute
     * @return safe immutable TCK report
     */
    public AdapterTckReport run(
            final AdapterProvider provider,
            final AdapterSemanticProbe probe,
            final AdapterTckProfile profile) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(profile, "profile");
        if (profile.scenarios().size() > this.executionPolicy.maxScenarios()) {
            throw new IllegalArgumentException("TCK profile scenario count exceeds runner limit");
        }
        final ExecutorService executor = newExecutor();
        final List<AdapterConformanceResult> results = new ArrayList<>();
        final List<String> limitations = new ArrayList<>();
        final List<String> securityFindings = new ArrayList<>();
        final List<String> failureModeFindings = new ArrayList<>();
        final List<String> redactionFindings = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        int unsupported = 0;
        AdapterCapabilityDescriptor descriptor = fallbackDescriptor(profile);
        try {
            final OperationResult<AdapterCapabilityDescriptor> descriptorResult = callWithDeadline(
                    executor,
                    "provider descriptor",
                    provider::descriptor);
            if (descriptorResult.success()) {
                descriptor = descriptorResult.value();
                final String descriptorBoundsFailure = validateDescriptorBounds(descriptor, this.executionPolicy);
                if (!descriptorBoundsFailure.isBlank()) {
                    boundedAddText(failureModeFindings, descriptorBoundsFailure);
                    descriptor = fallbackDescriptor(profile);
                    failed++;
                }
            } else {
                boundedAddText(failureModeFindings, descriptorResult.reason());
                failed++;
            }
            boundedAddAll(limitations, this.matrix.limitationsFor(descriptor, profile.semanticProfile()));

            scanProviderPublicOutput(provider, descriptor, executor, redactionFindings, failureModeFindings);
            for (AdapterTckScenario scenario : profile.scenarios()) {
                final AdapterTckResult result = runScenario(descriptor, probe, scenario, executor);
                scanTckOutput("scenario result " + scenario.id(), result, redactionFindings);
                if (result.passed()) {
                    passed++;
                } else if (result.unsupported()) {
                    unsupported++;
                    boundedAddText(limitations, "unsupported scenario: " + scenario.id());
                    if (scenario.required()) {
                        failed++;
                    }
                } else {
                    failed++;
                }
                boundedAddObject(results, toConformanceResult(result));
            }

            boundedAddAll(securityFindings, securityFindings(descriptor.securityProfile()));
            final OperationResult<Boolean> redactionResult = callWithDeadline(
                    executor,
                    "adapter redaction probe",
                    () -> Boolean.valueOf(probe.leaksSensitiveData(SENTINEL)));
            if (!redactionResult.success()) {
                boundedAddText(failureModeFindings, redactionResult.reason());
                failed++;
            } else if (Boolean.TRUE.equals(redactionResult.value())) {
                addRedactionFinding(redactionFindings, "adapter self-report");
            }
            final List<AdapterFailureModeResult> failureResults = runFailureModes(
                    executor,
                    probe,
                    EnumSet.allOf(AdapterFailureMode.class));
            for (AdapterFailureModeResult failureResult : failureResults) {
                scanTckOutput("failure-mode result " + failureResult.failureMode().name(), failureResult, redactionFindings);
                if (!failureResult.passed()) {
                    boundedAddText(failureModeFindings, failureResult.failureMode().name() + ":" + failureResult.reasonCode());
                    failed++;
                }
            }
            final int failedAfterCleanup = failed + closeProvider(provider, executor, failureModeFindings);

            final AdapterCompatibilityLevel matrixLevel = this.matrix.evaluate(descriptor, profile.semanticProfile());
            final AdapterCompatibilityLevel level = chooseLevel(
                    matrixLevel,
                    failedAfterCleanup,
                    unsupported,
                    securityFindings,
                    failureModeFindings,
                    redactionFindings,
                    limitations);
            final Instant timestamp = Instant.now(this.clock);
            final AdapterCompatibilityReport summary = new AdapterCompatibilityReport(
                    descriptor.adapterId(),
                    descriptor.adapterVersion(),
                    AdapterTckVersion.CURRENT,
                    profile.semanticProfile(),
                    profile.scenarios().size(),
                    passed,
                    failedAfterCleanup,
                    unsupported,
                    level,
                    List.copyOf(limitations),
                    List.copyOf(securityFindings),
                    List.copyOf(failureModeFindings),
                    List.copyOf(redactionFindings),
                    timestamp,
                    this.deterministicSeed,
                    Map.of("adapterType", descriptor.type().name()));
            final AdapterTckReport report = new AdapterTckReport(summary, List.copyOf(results), failureResults);
            if (AdapterRedactionPolicy.publicTextLeaksSensitiveValue(report)) {
                addRedactionFinding(redactionFindings, "final TCK report");
                final AdapterCompatibilityReport rejectedSummary = new AdapterCompatibilityReport(
                        descriptor.adapterId(),
                        descriptor.adapterVersion(),
                        AdapterTckVersion.CURRENT,
                        profile.semanticProfile(),
                        profile.scenarios().size(),
                        passed,
                        failedAfterCleanup + 1,
                        unsupported,
                        AdapterCompatibilityLevel.REJECTED,
                        List.copyOf(limitations),
                        List.copyOf(securityFindings),
                        List.copyOf(failureModeFindings),
                        List.copyOf(redactionFindings),
                        timestamp,
                        this.deterministicSeed,
                        Map.of("adapterType", descriptor.type().name()));
                return new AdapterTckReport(rejectedSummary, List.copyOf(results), failureResults);
            }
            return report;
        } finally {
            shutdownExecutor(executor);
        }
    }

    /**
     * Runs failure-mode checks without executing a full semantic profile.
     *
     * @param probe deterministic semantic probe
     * @param failureModes failure modes to execute
     * @return immutable failure-mode results
     */
    public List<AdapterFailureModeResult> runFailureModes(
            final AdapterSemanticProbe probe,
            final List<AdapterFailureMode> failureModes) {
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(failureModes, "failure modes");
        if (failureModes.size() > this.executionPolicy.maxFailureModes()) {
            throw new IllegalArgumentException("failure mode count exceeds runner limit");
        }
        final ExecutorService executor = newExecutor();
        try {
            return runFailureModes(executor, probe, Set.copyOf(failureModes));
        } finally {
            shutdownExecutor(executor);
        }
    }

    private AdapterTckResult runScenario(
            final AdapterCapabilityDescriptor descriptor,
            final AdapterSemanticProbe probe,
            final AdapterTckScenario scenario,
            final ExecutorService executor) {
        if (scenario.required() && !descriptor.claims(scenario.capability())) {
            return AdapterTckResult.failed(scenario, "REQUIRED_SEMANTIC_NOT_CLAIMED");
        }
        final OperationResult<AdapterTckResult> probeResult = callWithDeadline(
                executor,
                "scenario " + scenario.id(),
                () -> probe.runScenario(scenario));
        if (!probeResult.success()) {
            return AdapterTckResult.failed(scenario, probeResult.reasonCode());
        }
        final AdapterTckResult result = probeResult.value();
        final String resultBoundsFailure = validateMetadataBounds(result.safeMetadata(), this.executionPolicy, "scenario result metadata");
        if (!resultBoundsFailure.isBlank()) {
            return AdapterTckResult.failed(scenario, "RESULT_METADATA_BOUNDS_EXCEEDED");
        }
        if (!scenario.id().equals(result.scenarioId())) {
            return AdapterTckResult.failed(scenario, "SCENARIO_ID_MISMATCH");
        }
        if (scenario.required() && result.unsupported()) {
            return AdapterTckResult.failed(scenario, "REQUIRED_SEMANTIC_UNSUPPORTED");
        }
        if (descriptor.claims(scenario.capability()) && result.unsupported()) {
            return AdapterTckResult.failed(scenario, "CLAIMED_SEMANTIC_UNSUPPORTED");
        }
        if (result.passed()) {
            final ReferenceVerification verification = verifyReferenceOperations(probe, scenario, executor);
            if (!verification.passed()) {
                return AdapterTckResult.failed(scenario, verification.reasonCode());
            }
            return new AdapterTckResult(
                    result.scenarioId(),
                    true,
                    false,
                    result.reasonCode(),
                    result.level(),
                    mergeMetadata(result.safeMetadata(), Map.of(REFERENCE_TRANSCRIPT_KEY, verification.transcriptDigest())));
        }
        return result;
    }

    private void scanProviderPublicOutput(
            final AdapterProvider provider,
            final AdapterCapabilityDescriptor descriptor,
            final ExecutorService executor,
            final List<String> redactionFindings,
            final List<String> failureModeFindings) {
        scanTckOutput("provider descriptor", descriptor, redactionFindings);
        scanProviderCall("provider status", executor, provider::status, redactionFindings, failureModeFindings);
        scanProviderCall("provider health", executor, provider::health, redactionFindings, failureModeFindings);
        scanProviderCall("provider metrics", executor, provider::metrics, redactionFindings, failureModeFindings);
        scanProviderCall("provider audit events", executor, provider::auditEvents, redactionFindings, failureModeFindings);
    }

    private void scanProviderCall(
            final String area,
            final ExecutorService executor,
            final Callable<Object> operation,
            final List<String> redactionFindings,
            final List<String> failureModeFindings) {
        final OperationResult<Object> result = callWithDeadline(executor, area, operation);
        if (result.success()) {
            scanTckOutput(area, result.value(), redactionFindings);
        } else {
            boundedAddText(failureModeFindings, result.reason());
        }
    }

    private static void scanTckOutput(
            final String area,
            final Object value,
            final List<String> redactionFindings) {
        scanPublicOutput(area, value, redactionFindings);
        if (AdapterRedactionPolicy.containsRedactionMarker(value)) {
            addRedactionFinding(redactionFindings, area);
        }
    }

    private static void scanPublicOutput(
            final String area,
            final Object value,
            final List<String> redactionFindings) {
        if (AdapterRedactionPolicy.publicTextLeaksSensitiveValue(value)) {
            addRedactionFinding(redactionFindings, area);
        }
    }

    private static void addRedactionFinding(final List<String> redactionFindings, final String area) {
        final String finding = "redaction failure: sensitive adapter data appeared in " + area;
        if (!redactionFindings.contains(finding)) {
            redactionFindings.add(finding);
        }
    }

    private static AdapterConformanceResult toConformanceResult(final AdapterTckResult result) {
        final List<String> limitations = result.unsupported()
                ? List.of("unsupported: " + result.reasonCode())
                : List.of();
        final List<AdapterEvidence> evidence = List.of(new AdapterEvidence(
                result.scenarioId(),
                result.passed() ? "scenario passed" : "scenario did not pass: " + result.reasonCode(),
                result.safeMetadata()));
        return new AdapterConformanceResult(
                result.scenarioId(),
                result.passed(),
                result.unsupported(),
                result.level(),
                limitations,
                evidence,
                result.safeMetadata());
    }

    private static List<String> securityFindings(final AdapterSecurityProfile profile) {
        final List<String> findings = new ArrayList<>();
        if (!profile.authorizationHooks()) {
            findings.add("authorization hooks are not preserved");
        }
        if (!profile.auditHooks()) {
            findings.add("audit hooks are not preserved");
        }
        if (!profile.redaction()) {
            findings.add("redaction is not preserved");
        }
        if (!profile.objectReferenceProtection()) {
            findings.add("object reference authorization semantics are weakened");
        }
        if (!profile.digestNotBearerToken()) {
            findings.add("digest or reference values are treated as bearer authorization");
        }
        return findings;
    }

    private List<AdapterFailureModeResult> runFailureModes(
            final ExecutorService executor,
            final AdapterSemanticProbe probe,
            final Set<AdapterFailureMode> failureModes) {
        if (failureModes.size() > this.executionPolicy.maxFailureModes()) {
            throw new IllegalArgumentException("failure mode count exceeds runner limit");
        }
        final List<AdapterFailureModeResult> results = new ArrayList<>();
        for (AdapterFailureMode failureMode : failureModes) {
            final OperationResult<AdapterFailureModeResult> result = callWithDeadline(
                    executor,
                    "failure mode " + failureMode.name(),
                    () -> probe.runFailureMode(failureMode));
            if (result.success()) {
                final AdapterFailureModeResult value = result.value();
                final String boundsFailure = validateMetadataBounds(value.safeMetadata(), this.executionPolicy, "failure-mode metadata");
                if (boundsFailure.isBlank()) {
                    boundedAddObject(results, value);
                } else {
                    boundedAddObject(results, new AdapterFailureModeResult(failureMode, false, "RESULT_METADATA_BOUNDS_EXCEEDED", Map.of()));
                }
            } else {
                boundedAddObject(results, new AdapterFailureModeResult(failureMode, false, result.reasonCode(), Map.of()));
            }
        }
        return List.copyOf(results);
    }

    private int closeProvider(
            final AdapterProvider provider,
            final ExecutorService executor,
            final List<String> failureModeFindings) {
        final OperationResult<Void> result = callWithDeadline(executor, "provider close", () -> {
            provider.close();
            return null;
        });
        if (result.success()) {
            return 0;
        }
        boundedAddText(failureModeFindings, result.reason());
        return 1;
    }

    private <T> OperationResult<T> callWithDeadline(
            final ExecutorService executor,
            final String operationName,
            final Callable<T> operation) {
        final Future<T> future = executor.submit(operation);
        try {
            final T value = future.get(this.executionPolicy.operationTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return OperationResult.success(value);
        } catch (TimeoutException exception) {
            future.cancel(true);
            return OperationResult.failed(operationName + " timed out", "PROBE_TIMEOUT");
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return OperationResult.failed(operationName + " interrupted", "PROBE_INTERRUPTED");
        } catch (ExecutionException exception) {
            return OperationResult.failed(operationName + " failed", "PROBE_EXCEPTION");
        }
    }

    private ExecutorService newExecutor() {
        final ThreadFactory factory = Thread.ofVirtual()
                .name("j7m-adapter-tck-", 0)
                .factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }

    private void shutdownExecutor(final ExecutorService executor) {
        executor.shutdownNow();
        try {
            executor.awaitTermination(this.executionPolicy.cleanupTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static AdapterCapabilityDescriptor fallbackDescriptor(final AdapterTckProfile profile) {
        return new AdapterCapabilityDescriptor(
                new AdapterProviderId("unknown.adapter"),
                "unknown",
                profile.semanticProfile().type(),
                Set.of(profile.semanticProfile()),
                Map.of(),
                Set.of(),
                AdapterSecurityProfile.strict(),
                new AdapterResourceProfile(Map.of("tck", "descriptor-unavailable")),
                Set.of(),
                List.of("provider descriptor unavailable"),
                AdapterTckVersion.CURRENT,
                AdapterCompatibilityLevel.REJECTED);
    }

    private ReferenceVerification verifyReferenceOperations(
            final AdapterSemanticProbe probe,
            final AdapterTckScenario scenario,
            final ExecutorService executor) {
        final List<AdapterReferenceOperation> operations = referenceOperationsFor(scenario, this.deterministicSeed);
        final RecordingReferenceSession session = new RecordingReferenceSession(operations.size());
        final OperationResult<List<AdapterReferenceOutcome>> outcomeResult = callWithDeadline(
                executor,
                "reference operations " + scenario.id(),
                () -> probe.runReferenceOperations(scenario, operations, session));
        if (!outcomeResult.success()) {
            return new ReferenceVerification(false, outcomeResult.reasonCode(), "");
        }
        final List<AdapterReferenceOutcome> outcomes = outcomeResult.value();
        if (outcomes.size() != operations.size()) {
            return new ReferenceVerification(false, "REFERENCE_OPERATION_COUNT_MISMATCH", "");
        }
        final List<AdapterReferenceOutcome> recordedEffects = session.effects();
        if (recordedEffects.size() != operations.size()) {
            return new ReferenceVerification(false, "REFERENCE_EFFECT_COUNT_MISMATCH", "");
        }
        long transcript = this.deterministicSeed ^ 0xD6E8_FEB8_6659_FD93L;
        for (int index = 0; index < operations.size(); index++) {
            final AdapterReferenceOperation operation = operations.get(index);
            final AdapterReferenceOutcome outcome = outcomes.get(index);
            final AdapterReferenceOutcome recordedEffect = recordedEffects.get(index);
            if (!operation.operationId().equals(outcome.operationId())) {
                return new ReferenceVerification(false, "REFERENCE_OPERATION_ID_MISMATCH", "");
            }
            if (!operation.operationId().equals(recordedEffect.operationId())) {
                return new ReferenceVerification(false, "REFERENCE_EFFECT_ID_MISMATCH", "");
            }
            if (!recordedEffect.completed()) {
                return new ReferenceVerification(false, "REFERENCE_EFFECT_INCOMPLETE", "");
            }
            final String expectedDigest = referenceDigestFor(operation);
            if (!expectedDigest.equals(recordedEffect.observedDigest())) {
                return new ReferenceVerification(false, "REFERENCE_EFFECT_OBSERVABLE_MISMATCH", "");
            }
            if (!outcome.completed()) {
                return new ReferenceVerification(false, "REFERENCE_OPERATION_INCOMPLETE", "");
            }
            if (!expectedDigest.equals(outcome.observedDigest())) {
                return new ReferenceVerification(false, "REFERENCE_OPERATION_DIGEST_MISMATCH", "");
            }
            if (!outcome.observedDigest().equals(recordedEffect.observedDigest())) {
                return new ReferenceVerification(false, "REFERENCE_TRANSCRIPT_EFFECT_MISMATCH", "");
            }
            transcript = mix(transcript, outcome.operationId().hashCode());
            transcript = mix(transcript, outcome.observedDigest().hashCode());
            transcript = mix(transcript, recordedEffect.observedDigest().hashCode());
        }
        return new ReferenceVerification(true, "PASSED", "ref-transcript-" + Long.toUnsignedString(transcript, 16));
    }

    private static List<AdapterReferenceOperation> referenceOperationsFor(
            final AdapterTckScenario scenario,
            final long deterministicSeed) {
        final String base = referenceOutcomeFor(scenario, deterministicSeed);
        return List.of(
                new AdapterReferenceOperation(
                        REFERENCE_OPERATION_PREFIX + "-prepare-" + base.substring(4),
                        scenario.capability(),
                        scenario.profile().name() + ":" + scenario.capability().name() + ":prepare"),
                new AdapterReferenceOperation(
                        REFERENCE_OPERATION_PREFIX + "-observe-" + base.substring(4),
                        scenario.capability(),
                        scenario.id() + ":" + scenario.required() + ":observe"));
    }

    /**
     * Computes the deterministic runner-owned observable value for a reference operation.
     *
     * <p>The method is public so adapter TCK fixtures and external probes can
     * derive the same observable value from the runner-owned operation they
     * executed. The observable value is intentionally distinct from the
     * operation payload so a probe cannot pass by echoing runner input. A
     * scenario pass is still accepted only when the runner invokes
     * {@link AdapterSemanticProbe#runReferenceOperations(AdapterTckScenario, List)}
     * and verifies the returned ordered transcript.</p>
     *
     * @param operation reference operation issued by the runner
     * @return stable safe observable value for the operation's effect
     */
    public static String referenceObservedValueFor(final AdapterReferenceOperation operation) {
        Objects.requireNonNull(operation, "operation");
        long value = 0xE703_7ED1_A0B4_28DBL;
        value = mix(value, operation.operationId().hashCode());
        value = mix(value, operation.capability().name().hashCode());
        value = mix(value, operation.payload().hashCode());
        return "ref-observed-" + Long.toUnsignedString(value, 16);
    }

    /**
     * Computes the deterministic digest expected for a reference operation.
     *
     * <p>The digest is derived from {@link #referenceObservedValueFor(AdapterReferenceOperation)},
     * not from the operation payload. This keeps the compatibility decision
     * based on a runner-observable effect instead of an echoable input value.</p>
     *
     * @param operation reference operation issued by the runner
     * @return stable safe digest for the operation's observable result
     */
    public static String referenceDigestFor(final AdapterReferenceOperation operation) {
        Objects.requireNonNull(operation, "operation");
        long value = 0xA076_1D64_78BD_642FL;
        value = mix(value, operation.operationId().hashCode());
        value = mix(value, operation.capability().name().hashCode());
        value = mix(value, referenceObservedValueFor(operation).hashCode());
        return "ref-digest-" + Long.toUnsignedString(value, 16);
    }

    private static Map<String, String> mergeMetadata(
            final Map<String, String> first,
            final Map<String, String> second) {
        if (first.isEmpty()) {
            return second;
        }
        final LinkedHashMap<String, String> merged = new LinkedHashMap<>(first.size() + second.size());
        merged.putAll(first);
        merged.putAll(second);
        return Map.copyOf(merged);
    }

    private static String validateDescriptorBounds(
            final AdapterCapabilityDescriptor descriptor,
            final ExecutionPolicy policy) {
        if (descriptor.supportedProfiles().size() > policy.maxReportEntries()
                || descriptor.capabilities().size() > AdapterCapability.values().length
                || descriptor.unsupportedSemantics().size() > AdapterCapability.values().length
                || descriptor.failureModes().size() > AdapterFailureMode.values().length
                || descriptor.knownLimitations().size() > policy.maxReportEntries()) {
            return "provider descriptor bounds exceeded";
        }
        for (String limitation : descriptor.knownLimitations()) {
            if (limitation.length() > policy.maxTextLength()) {
                return "provider descriptor text bounds exceeded";
            }
        }
        return validateMetadataBounds(descriptor.resourceProfile().limits(), policy, "resource profile metadata");
    }

    private static String validateMetadataBounds(
            final Map<String, String> metadata,
            final ExecutionPolicy policy,
            final String area) {
        if (metadata.size() > policy.maxReportEntries()) {
            return area + " count exceeds runner limit";
        }
        long bytes = 0L;
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            final String key = entry.getKey() == null ? "" : entry.getKey();
            final String value = entry.getValue() == null ? "" : entry.getValue();
            if (key.length() > policy.maxTextLength() || value.length() > policy.maxTextLength()) {
                return area + " text exceeds runner limit";
            }
            bytes += key.length();
            bytes += value.length();
            if (bytes > (long) policy.maxReportEntries() * (long) policy.maxTextLength()) {
                return area + " bytes exceed runner limit";
            }
        }
        return "";
    }

    private static String referenceOutcomeFor(final AdapterTckScenario scenario, final long deterministicSeed) {
        long value = deterministicSeed ^ 0x9E37_79B9_7F4A_7C15L;
        value = mix(value, scenario.id().hashCode());
        value = mix(value, scenario.profile().name().hashCode());
        value = mix(value, scenario.capability().name().hashCode());
        value = mix(value, scenario.required() ? 1 : 0);
        return "ref-" + Long.toUnsignedString(value, 16);
    }

    private static long mix(final long current, final int value) {
        long mixed = current ^ Integer.toUnsignedLong(value);
        mixed *= 0xBF58_476D_1CE4_E5B9L;
        mixed ^= mixed >>> 31;
        return mixed;
    }

    private static Duration requireDuration(
            final Duration value,
            final String name,
            final Duration hardMaximum) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero() || value.compareTo(hardMaximum) > 0) {
            throw new IllegalArgumentException(name + " must be positive and within the TCK hard limit");
        }
        return value;
    }

    private static int requireBounded(final int value, final String name, final int hardMaximum) {
        if (value < 1 || value > hardMaximum) {
            throw new IllegalArgumentException(name + " must be positive and within the TCK hard limit");
        }
        return value;
    }

    private <E> void boundedAddObject(final List<E> values, final E value) {
        if (values.size() >= this.executionPolicy.maxReportEntries()) {
            return;
        }
        values.add(value);
    }

    private void boundedAddText(final List<String> values, final String value) {
        if (values.size() >= this.executionPolicy.maxReportEntries()) {
            return;
        }
        values.add(truncate(value));
    }

    private void boundedAddAll(final List<String> values, final List<String> additions) {
        for (String addition : additions) {
            boundedAddText(values, addition);
        }
    }

    private String truncate(final String value) {
        if (value.length() <= this.executionPolicy.maxTextLength()) {
            return value;
        }
        return value.substring(0, this.executionPolicy.maxTextLength());
    }

    private record OperationResult<T>(T value, boolean success, String reason, String reasonCode) {
        static <T> OperationResult<T> success(final T value) {
            return new OperationResult<>(value, true, "", "");
        }

        static <T> OperationResult<T> failed(final String reason, final String reasonCode) {
            return new OperationResult<>(null, false, reason, reasonCode);
        }
    }

    private record ReferenceVerification(boolean passed, String reasonCode, String transcriptDigest) {
    }

    private static final class RecordingReferenceSession implements AdapterReferenceSession {
        private final List<AdapterReferenceOutcome> effects;
        private final int maxEffects;

        RecordingReferenceSession(final int maxEffects) {
            this.effects = new ArrayList<>(maxEffects);
            this.maxEffects = maxEffects;
        }

        @Override
        public synchronized AdapterReferenceOutcome recordEffect(
                final AdapterReferenceOperation operation,
                final String observedValue) {
            Objects.requireNonNull(operation, "operation");
            if (observedValue == null || observedValue.isBlank()) {
                throw new IllegalArgumentException("observed value must not be blank");
            }
            final String safeObservedValue = AdapterRedactionPolicy.redactText(observedValue.trim());
            if (this.effects.size() >= this.maxEffects) {
                throw new IllegalArgumentException("reference effect count exceeds runner-issued operation count");
            }
            final AdapterReferenceOutcome outcome = new AdapterReferenceOutcome(
                    operation.operationId(),
                    true,
                    observedDigestFor(operation, safeObservedValue),
                    Map.of("observed", safeObservedValue));
            this.effects.add(outcome);
            return outcome;
        }

        synchronized List<AdapterReferenceOutcome> effects() {
            return List.copyOf(this.effects);
        }
    }

    private static String observedDigestFor(
            final AdapterReferenceOperation operation,
            final String observedValue) {
        Objects.requireNonNull(operation, "operation");
        long value = 0xA076_1D64_78BD_642FL;
        value = mix(value, operation.operationId().hashCode());
        value = mix(value, operation.capability().name().hashCode());
        value = mix(value, observedValue.hashCode());
        return "ref-digest-" + Long.toUnsignedString(value, 16);
    }

    private static AdapterCompatibilityLevel chooseLevel(
            final AdapterCompatibilityLevel matrixLevel,
            final int failed,
            final int unsupported,
            final List<String> securityFindings,
            final List<String> failureModeFindings,
            final List<String> redactionFindings,
            final List<String> limitations) {
        if (failed > 0 || !securityFindings.isEmpty() || !failureModeFindings.isEmpty() || !redactionFindings.isEmpty()) {
            return AdapterCompatibilityLevel.REJECTED;
        }
        if (matrixLevel == AdapterCompatibilityLevel.REJECTED) {
            return AdapterCompatibilityLevel.REJECTED;
        }
        if (unsupported > 0 || !limitations.isEmpty() || matrixLevel == AdapterCompatibilityLevel.COMPATIBLE_WITH_LIMITATIONS) {
            return AdapterCompatibilityLevel.COMPATIBLE_WITH_LIMITATIONS;
        }
        return AdapterCompatibilityLevel.NATIVE_EQUIVALENT;
    }
}
