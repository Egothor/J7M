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

package org.j7m.middleware.adapter.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

final class AdapterValuesBoundsTest {
    @Test
    void descriptorRejectsOversizedKnownLimitationsBeforeRetention() {
        final ArrayList<String> limitations = new ArrayList<>();
        for (int index = 0; index <= AdapterValues.MAX_COLLECTION_ENTRIES; index++) {
            limitations.add("limitation-" + index);
        }

        assertThrows(IllegalArgumentException.class, () -> descriptor(limitations));
    }

    @Test
    void resourceProfileRejectsOversizedMetadataBeforeRetention() {
        final Map<String, String> oversized = oversizedMetadata();

        assertThrows(IllegalArgumentException.class, () -> new AdapterResourceProfile(oversized));
    }

    @Test
    void reportRejectsOversizedMetadataValueBeforeRetention() {
        final String oversizedText = text(AdapterValues.MAX_TEXT_CHARS + 1);

        assertThrows(IllegalArgumentException.class, () -> new AdapterTckResult(
                "scenario",
                true,
                false,
                "PASSED",
                AdapterCompatibilityLevel.NATIVE_EQUIVALENT,
                Map.of("note", oversizedText)));
    }

    @Test
    void reportRejectsOversizedMetadataKeyBeforeRetention() {
        final String oversizedKey = text(AdapterValues.MAX_KEY_CHARS + 1);

        assertThrows(IllegalArgumentException.class, () -> new AdapterTckResult(
                "scenario",
                true,
                false,
                "PASSED",
                AdapterCompatibilityLevel.NATIVE_EQUIVALENT,
                Map.of(oversizedKey, "value")));
    }

    @Test
    void evidenceRejectsOversizedSummaryTextBeforeRetention() {
        final String oversizedText = text(AdapterValues.MAX_TEXT_CHARS + 1);

        assertThrows(IllegalArgumentException.class, () -> new AdapterEvidence("evidence", oversizedText, Map.of()));
    }

    @Test
    void compatibilityReportRejectsOversizedReportTextList() {
        final String oversizedText = text(AdapterValues.MAX_TEXT_CHARS + 1);

        assertThrows(IllegalArgumentException.class, () -> new AdapterCompatibilityReport(
                new AdapterProviderId("test.adapter"),
                "1.0.0",
                "adapter-tck-test",
                AdapterSemanticProfile.messaging(),
                1,
                1,
                0,
                0,
                AdapterCompatibilityLevel.NATIVE_EQUIVALENT,
                List.of(oversizedText),
                List.of(),
                List.of(),
                List.of(),
                Instant.EPOCH,
                7L,
                Map.of()));
    }

    @Test
    void tckReportRejectsOversizedResultCount() {
        final ArrayList<AdapterConformanceResult> results = new ArrayList<>();
        for (int index = 0; index <= AdapterValues.MAX_COLLECTION_ENTRIES; index++) {
            results.add(new AdapterConformanceResult(
                    "scenario-" + index,
                    true,
                    false,
                    AdapterCompatibilityLevel.NATIVE_EQUIVALENT,
                    List.of(),
                    List.of(),
                    Map.of()));
        }
        final AdapterCompatibilityReport summary = new AdapterCompatibilityReport(
                new AdapterProviderId("test.adapter"),
                "1.0.0",
                "adapter-tck-test",
                AdapterSemanticProfile.messaging(),
                results.size(),
                results.size(),
                0,
                0,
                AdapterCompatibilityLevel.NATIVE_EQUIVALENT,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Instant.EPOCH,
                7L,
                Map.of());

        assertThrows(IllegalArgumentException.class, () -> new AdapterTckReport(summary, results, List.of()));
    }

    @Test
    void publicReportMetadataIsImmutableAndRedacted() {
        final AdapterTckResult result = new AdapterTckResult(
                "scenario",
                true,
                false,
                "PASSED",
                AdapterCompatibilityLevel.NATIVE_EQUIVALENT,
                Map.of("token", "J7M_SENTINEL_SECRET_TOKEN", "note", "safe"));

        assertEquals(AdapterRedactionPolicy.REDACTED, result.safeMetadata().get("token"));
        assertThrows(UnsupportedOperationException.class, () -> result.safeMetadata().put("new", "value"));
    }

    private static AdapterCapabilityDescriptor descriptor(final List<String> limitations) {
        return new AdapterCapabilityDescriptor(
                new AdapterProviderId("test.adapter"),
                "1.0.0",
                AdapterType.MESSAGING,
                Set.of(AdapterSemanticProfile.messaging()),
                Map.of(AdapterCapability.REDACTION, AdapterCapabilityLevel.REQUIRED),
                Set.of(),
                AdapterSecurityProfile.strict(),
                new AdapterResourceProfile(Map.of("fixture", "test")),
                Set.of(AdapterFailureMode.TIMEOUT),
                limitations,
                "adapter-tck-test",
                AdapterCompatibilityLevel.NATIVE_EQUIVALENT);
    }

    private static Map<String, String> oversizedMetadata() {
        final LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        for (int index = 0; index <= AdapterValues.MAX_COLLECTION_ENTRIES; index++) {
            metadata.put("key-" + index, "value");
        }
        return metadata;
    }

    private static String text(final int length) {
        return "x".repeat(length);
    }
}
