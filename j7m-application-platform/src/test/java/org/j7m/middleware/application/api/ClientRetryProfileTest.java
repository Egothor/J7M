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

package org.j7m.middleware.application.api;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClientRetryProfileTest {
    @Test void finiteMinimumAndMaximumProfilesAreAccepted() {
        assertEquals(0, new ClientRetryProfile(0, Duration.ZERO, Duration.ZERO, 0).maximumAdditionalAttempts());
        var maximum = new ClientRetryProfile(16, Duration.ofSeconds(30), Duration.ofSeconds(30), 1);
        assertEquals(Duration.ofSeconds(30), maximum.maximumBackoff());
        assertThrows(IllegalArgumentException.class, () -> new ClientRetryProfile(-1, Duration.ZERO, Duration.ZERO, 0));
        assertThrows(IllegalArgumentException.class, () -> new ClientRetryProfile(17, Duration.ZERO, Duration.ZERO, 0));
        assertThrows(IllegalArgumentException.class, () -> new ClientRetryProfile(1, Duration.ofMillis(-1), Duration.ZERO, 0));
        assertThrows(IllegalArgumentException.class, () -> new ClientRetryProfile(1, Duration.ofSeconds(2), Duration.ofSeconds(1), 0));
        assertThrows(IllegalArgumentException.class, () -> new ClientRetryProfile(1, Duration.ZERO, Duration.ofSeconds(31), 0));
        assertThrows(IllegalArgumentException.class, () -> new ClientRetryProfile(1, null, Duration.ZERO, 0));
    }
    @ParameterizedTest @ValueSource(doubles={-0.01,1.01,Double.NaN,Double.POSITIVE_INFINITY})
    void invalidJitterNeverCreatesUnboundedOrUndefinedWait(double jitter) {
        assertThrows(IllegalArgumentException.class, () -> new ClientRetryProfile(1, Duration.ZERO, Duration.ZERO, jitter));
    }
}
