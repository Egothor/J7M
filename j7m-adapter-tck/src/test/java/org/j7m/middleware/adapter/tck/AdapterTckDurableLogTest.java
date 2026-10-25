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

import org.j7m.middleware.adapter.spi.AdapterCapability;
import org.j7m.middleware.adapter.spi.AdapterCompatibilityLevel;
import org.j7m.middleware.adapter.spi.AdapterSemanticProfile;
import org.j7m.middleware.adapter.spi.AdapterTckReport;
import org.junit.jupiter.api.Test;

final class AdapterTckDurableLogTest {
    @Test
    void fakePassAdapterPassesDurableLogProfile() {
        final FakeAdapters.FakeAdapter adapter = FakeAdapters.pass(AdapterSemanticProfile.durableLog());
        final AdapterTckReport report = new AdapterTckRunner().run(adapter, adapter, AdapterTckProfiles.durableLog());

        assertEquals(AdapterCompatibilityLevel.NATIVE_EQUIVALENT, report.summary().compatibilityLevel());
        assertEquals(0, report.summary().failed());
    }

    @Test
    void partialAdapterReportsLimitation() {
        final FakeAdapters.FakeAdapter adapter = FakeAdapters.unsupported(
                AdapterSemanticProfile.durableLog(),
                AdapterCapability.BACKPRESSURE);
        final AdapterTckReport report = new AdapterTckRunner().run(adapter, adapter, AdapterTckProfiles.durableLog());

        assertEquals(AdapterCompatibilityLevel.COMPATIBLE_WITH_LIMITATIONS, report.summary().compatibilityLevel());
        assertTrue(report.summary().limitations().stream().anyMatch(text -> text.contains("BACKPRESSURE")));
    }

    @Test
    void corruptingAdapterFailsDurableLogProfile() {
        final FakeAdapters.FakeAdapter adapter = FakeAdapters.failing(
                AdapterSemanticProfile.durableLog(),
                AdapterCapability.LOG_CORRUPTION_DETECTION);

        assertEquals(
                AdapterCompatibilityLevel.REJECTED,
                new AdapterTckRunner().run(adapter, adapter, AdapterTckProfiles.durableLog()).summary().compatibilityLevel());
    }
}
