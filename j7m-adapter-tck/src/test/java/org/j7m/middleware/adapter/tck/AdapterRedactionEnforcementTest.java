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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.j7m.middleware.adapter.spi.AdapterAuditEvent;
import org.j7m.middleware.adapter.spi.AdapterCompatibilityLevel;
import org.j7m.middleware.adapter.spi.AdapterCompatibilityReport;
import org.j7m.middleware.adapter.spi.AdapterEvidence;
import org.j7m.middleware.adapter.spi.AdapterFailureMode;
import org.j7m.middleware.adapter.spi.AdapterFailureModeResult;
import org.j7m.middleware.adapter.spi.AdapterHealth;
import org.j7m.middleware.adapter.spi.AdapterLifecycle;
import org.j7m.middleware.adapter.spi.AdapterProviderId;
import org.j7m.middleware.adapter.spi.AdapterRedactionPolicy;
import org.j7m.middleware.adapter.spi.AdapterSemanticProfile;
import org.j7m.middleware.adapter.spi.AdapterStatus;
import org.j7m.middleware.adapter.spi.AdapterTckReport;
import org.j7m.middleware.adapter.spi.AdapterTckResult;
import org.j7m.middleware.adapter.spi.AdapterTckScenario;
import org.junit.jupiter.api.Test;

final class AdapterRedactionEnforcementTest {
    private static final String SECRET = "J7M_SENTINEL_SECRET_TOKEN";
    private static final String PASSWORD = "J7M_SENTINEL_PASSWORD";
    private static final String API_KEY = "J7M_SENTINEL_API_KEY";

    @Test
    void metadataValuesUnderHarmlessKeysAreRedacted() {
        assertRedacted(new AdapterStatus(
                new AdapterProviderId("test.adapter"),
                AdapterLifecycle.RUNNING,
                AdapterCompatibilityLevel.NATIVE_EQUIVALENT,
                Map.of("note", SECRET)));
        assertRedacted(new AdapterHealth(
                new AdapterProviderId("test.adapter"),
                true,
                AdapterLifecycle.RUNNING,
                "OK",
                Map.of("description", "Bearer " + SECRET)));
        assertRedacted(new AdapterAuditEvent(
                Instant.EPOCH,
                new AdapterProviderId("test.adapter"),
                "ACTION",
                "RESULT",
                "OK",
                Map.of("message", "token=" + SECRET)));
        assertRedacted(new AdapterEvidence("evidence", "password=" + PASSWORD, Map.of("metadata", "apiKey=" + API_KEY)));
    }

    @Test
    void resultAndFailureModeMetadataCannotExposeSentinels() {
        final AdapterTckScenario scenario = AdapterTckProfiles.durableLog().scenarios().getFirst();
        final AdapterTckResult result = new AdapterTckResult(
                scenario.id(),
                true,
                false,
                "PASSED",
                AdapterCompatibilityLevel.NATIVE_EQUIVALENT,
                Map.of("note", SECRET, "description", "Bearer " + SECRET));
        final AdapterFailureModeResult failureModeResult = new AdapterFailureModeResult(
                AdapterFailureMode.TIMEOUT,
                true,
                "HANDLED",
                Map.of("message", "token=" + SECRET));

        assertRedacted(result);
        assertRedacted(failureModeResult);
    }

    @Test
    void reportTypesCannotExposeSentinelsInTextOrMetadata() {
        final AdapterCompatibilityReport summary = new AdapterCompatibilityReport(
                new AdapterProviderId("test.adapter"),
                "1.0.0",
                AdapterTckVersion.CURRENT,
                AdapterSemanticProfile.durableLog(),
                1,
                0,
                1,
                0,
                AdapterCompatibilityLevel.REJECTED,
                List.of("limitation " + SECRET),
                List.of("security " + PASSWORD),
                List.of("failure " + API_KEY),
                List.of("redaction " + SECRET),
                Instant.EPOCH,
                1L,
                Map.of("info", "metadata=[note=" + SECRET + "]"));
        final AdapterTckReport report = new AdapterTckReport(summary, List.of(), List.of());

        assertRedacted(summary);
        assertRedacted(report);
    }

    @Test
    void maliciousProbeCannotPassBySelfReportingNoLeak() {
        final FakeAdapters.FakeAdapter adapter = FakeAdapters.leakingScenarioMetadata(
                AdapterSemanticProfile.durableLog(),
                "note",
                SECRET);
        final AdapterTckReport report = new AdapterTckRunner().run(adapter, adapter, AdapterTckProfiles.durableLog());

        assertEquals(AdapterCompatibilityLevel.REJECTED, report.summary().compatibilityLevel());
        assertFalse(report.toString().contains(SECRET));
        assertTrue(report.summary().redactionFindings().stream().allMatch(finding -> !finding.contains(SECRET)));
        assertFalse(report.summary().redactionFindings().isEmpty());
    }

    @Test
    void providerPublicOutputRedactionMarkersRejectReportWithoutLeakingSentinel() {
        final FakeAdapters.FakeAdapter adapter = FakeAdapters.leakingProviderOutput(
                AdapterSemanticProfile.durableLog(),
                SECRET);
        final AdapterTckReport report = new AdapterTckRunner().run(adapter, adapter, AdapterTckProfiles.durableLog());

        assertEquals(AdapterCompatibilityLevel.REJECTED, report.summary().compatibilityLevel());
        assertFalse(report.toString().contains(SECRET));
        assertTrue(report.summary().redactionFindings().stream().allMatch(finding -> !finding.contains(SECRET)));
        assertTrue(report.summary().redactionFindings().stream()
                .anyMatch(finding -> finding.contains("provider status")
                        || finding.contains("provider health")
                        || finding.contains("provider audit events")));
    }

    @Test
    void existingFixtureOutcomesRemainStable() {
        final AdapterTckRunner runner = new AdapterTckRunner();
        final AdapterTckReport pass = runner.run(
                FakeAdapters.pass(AdapterSemanticProfile.durableLog()),
                FakeAdapters.pass(AdapterSemanticProfile.durableLog()),
                AdapterTckProfiles.durableLog());
        final AdapterTckReport partial = runner.run(
                FakeAdapters.unsupported(AdapterSemanticProfile.coordination(), org.j7m.middleware.adapter.spi.AdapterCapability.WATCH_NOTIFY),
                FakeAdapters.unsupported(AdapterSemanticProfile.coordination(), org.j7m.middleware.adapter.spi.AdapterCapability.WATCH_NOTIFY),
                AdapterTckProfiles.coordination());
        final AdapterTckReport failing = runner.run(
                FakeAdapters.failing(AdapterSemanticProfile.repositoryReplication(), org.j7m.middleware.adapter.spi.AdapterCapability.EXACT_BYTE_PRESERVATION),
                FakeAdapters.failing(AdapterSemanticProfile.repositoryReplication(), org.j7m.middleware.adapter.spi.AdapterCapability.EXACT_BYTE_PRESERVATION),
                AdapterTckProfiles.repositoryReplication());

        assertEquals(AdapterCompatibilityLevel.NATIVE_EQUIVALENT, pass.summary().compatibilityLevel());
        assertEquals(AdapterCompatibilityLevel.COMPATIBLE_WITH_LIMITATIONS, partial.summary().compatibilityLevel());
        assertEquals(AdapterCompatibilityLevel.REJECTED, failing.summary().compatibilityLevel());
    }

    private static void assertRedacted(final Object value) {
        final String text = String.valueOf(value);
        assertFalse(text.contains(SECRET), text);
        assertFalse(text.contains(PASSWORD), text);
        assertFalse(text.contains(API_KEY), text);
        assertTrue(AdapterRedactionPolicy.containsRedactionMarker(text), text);
    }
}
