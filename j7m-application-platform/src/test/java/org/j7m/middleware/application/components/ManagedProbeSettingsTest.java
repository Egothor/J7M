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

package org.j7m.middleware.application.components;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManagedProbeSettingsTest {
    @Test void hostSettingsCreateMonitorWithActualRegistrationLimitAndValidity() {
        var settings = new ManagedProbeSettings(1, 1, Duration.ofNanos(1), Duration.ofSeconds(1), Duration.ofMillis(50));
        try (var monitor = settings.newMonitor(); var first = monitor.register(List.of(), false, ignored -> { })) {
            assertEquals(Duration.ofMillis(50), monitor.freshness());
            assertThrows(IllegalStateException.class, () -> monitor.register(List.of(), false, ignored -> { }));
            assertEquals(ManagedProbeCoordinator.State.UNKNOWN, first.snapshot().state());
        }
    }

    @Test void rejectsEachInvalidBoundWithoutStartingApplicationCode() {
        var second = Duration.ofSeconds(1);
        for (int workers : List.of(0, 65)) assertThrows(IllegalArgumentException.class,
                () -> new ManagedProbeSettings(workers, 1, second, second, second));
        for (int registrations : List.of(0, 1025)) assertThrows(IllegalArgumentException.class,
                () -> new ManagedProbeSettings(1, registrations, second, second, second));
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofNanos(-1), Duration.ofMinutes(1).plusNanos(1))) {
            assertThrows(IllegalArgumentException.class, () -> new ManagedProbeSettings(1, 1, invalid, second, second));
            assertThrows(IllegalArgumentException.class, () -> new ManagedProbeSettings(1, 1, second, invalid, second));
            assertThrows(IllegalArgumentException.class, () -> new ManagedProbeSettings(1, 1, second, second, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new ManagedProbeSettings(1, 1, second, second, Duration.ofMillis(1).minusNanos(1)));
        assertEquals(Duration.ofMillis(1), new ManagedProbeSettings(1, 1, second, second, Duration.ofMillis(1)).freshness());
        assertThrows(NullPointerException.class, () -> new ManagedProbeSettings(1, 1, null, second, second));
    }

    @Test void acceptsMaximumProfileAndProvidesFiniteDevelopmentDefaults() {
        var maximum = new ManagedProbeSettings(64, 1024, Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1));
        assertEquals(64, maximum.maximumConcurrent()); assertEquals(1024, maximum.maximumRegistrations());
        var defaults = ManagedProbeSettings.defaults();
        assertTrue(defaults.maximumConcurrent() > 0); assertTrue(defaults.maximumRegistrations() > 0);
        assertEquals(Duration.ofMillis(100), defaults.interval()); assertEquals(Duration.ofMillis(100), defaults.budget());
        assertEquals(Duration.ofSeconds(2), defaults.freshness());
    }
}
