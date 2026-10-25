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

package org.j7m.middleware.cluster;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanaryRouterTest {
    @Test void stableRealRoutingWeightsAndNodeIdentity() {
        int candidates = 0;
        for (int i = 0; i < 10000; i++) {
            String request = "request-" + i;
            int bucket = CanaryRouter.bucket("tenant", 1, request);
            assertEquals(bucket, CanaryRouter.bucket("tenant", 1, request));
            assertTrue(bucket >= 0 && bucket < 10000);
            if (bucket < 1000) candidates++;
            assertEquals(CanaryRouter.node("tenant", 1, request, List.of("a", "b")),
                    CanaryRouter.node("tenant", 1, request, List.of("a", "b")));
        }
        assertTrue(candidates >= 700 && candidates <= 1300, Integer.toString(candidates));
        assertEquals("a", CanaryRouter.node("tenant", 1, "one", List.of("a")));
    }
    @Test void boundariesAndInvalidInputs() {
        assertDoesNotThrow(() -> CanaryRouter.bucket("a", Long.MAX_VALUE, "x".repeat(512)));
        assertThrows(IllegalArgumentException.class, () -> CanaryRouter.bucket("a", 0, "id"));
        assertThrows(IllegalArgumentException.class, () -> CanaryRouter.bucket("a", 1, ""));
        assertThrows(IllegalArgumentException.class, () -> CanaryRouter.bucket("a", 1, "x".repeat(513)));
        assertThrows(IllegalArgumentException.class, () -> CanaryRouter.node("a", 1, "id", List.of()));
    }
}
