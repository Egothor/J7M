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

package org.j7m.middleware.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.j7m.middleware.api.reliability.FencingToken;
import org.j7m.middleware.api.reliability.ReliabilityDecision;
import org.j7m.middleware.api.reliability.ReliabilityStatus;
import org.j7m.middleware.reliability.FencingTokenValidator;
import org.junit.jupiter.api.Test;

public final class FencingTokenTest {
    @Test
    public void monotonicTokensCompareWithinScope() {
        FencingToken first = new FencingToken("scheduler", "task-1", 1L);
        FencingToken second = new FencingToken("scheduler", "task-1", 2L);

        assertTrue(second.compareTo(first) > 0);
    }

    @Test
    public void staleTokenIsRejected() {
        FencingTokenValidator validator = new FencingTokenValidator();

        ReliabilityDecision decision = validator.validate(
                new FencingToken("scheduler", "task-1", 5L),
                new FencingToken("scheduler", "task-1", 4L),
                false);

        assertFalse(decision.accepted());
        assertEquals(ReliabilityStatus.STALE_FENCING_TOKEN, decision.status());
    }

    @Test
    public void equalTokenPolicyIsExplicit() {
        FencingTokenValidator validator = new FencingTokenValidator();
        FencingToken token = new FencingToken("scheduler", "task-1", 5L);

        assertFalse(validator.validate(token, token, false).accepted());
        assertTrue(validator.validate(token, token, true).accepted());
    }

    @Test
    public void scopeMismatchIsRejectedAndCrossScopeComparisonThrows() {
        FencingToken current = new FencingToken("scheduler", "task-1", 5L);
        FencingToken other = new FencingToken("scheduler", "task-2", 6L);

        assertEquals(ReliabilityStatus.FENCING_SCOPE_MISMATCH, new FencingTokenValidator().validate(current, other, false).status());
        assertThrows(IllegalArgumentException.class, () -> current.compareTo(other));
    }
}
