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

package org.j7m.middleware.distributed;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MeasuredClockHealthTest {
    @ParameterizedTest
    @ValueSource(longs = {-60000, 60000})
    void jumpFailsClosedAndRecoveryRequiresStableAlignedEvidence(long jump) {
        var wall = new AtomicLong(100000); var nano = new AtomicLong(0); var reference = new AtomicLong(100000);
        var uncertainty = new AtomicLong(0);
        var clock = new MeasuredClockHealth(wall::get, nano::get, reference::get, uncertainty::get);
        assertFalse(clock.sample().monotonicStable()); assertTrue(clock.sample().monotonicStable());
        wall.addAndGet(jump); assertFalse(clock.sample().monotonicStable());
        assertFalse(clock.sample().monotonicStable());
        wall.set(reference.get()); assertFalse(clock.sample().monotonicStable());
        assertFalse(clock.sample().monotonicStable()); assertTrue(clock.sample().monotonicStable());
        uncertainty.set(501); assertFalse(clock.sample().monotonicStable());
        uncertainty.set(500); assertFalse(clock.sample().monotonicStable()); assertTrue(clock.sample().monotonicStable());
        wall.addAndGet(60000); reference.addAndGet(60000); nano.addAndGet(60000000000L);
        assertTrue(clock.sample().monotonicStable());
    }
}
