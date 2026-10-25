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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.reliability.DeliveryMode;
import org.j7m.middleware.api.reliability.DeliverySemantics;
import org.j7m.middleware.api.reliability.InvocationReliabilityOptions;
import org.j7m.middleware.api.reliability.ReliabilityDecision;
import org.j7m.middleware.reliability.ReliabilityPolicyEvaluator;
import org.junit.jupiter.api.Test;

public final class DeliverySemanticsTest {
    @Test
    public void atMostOnceSemanticsAreBestEffortAndNotRetrySafe() {
        DeliverySemantics semantics = DeliverySemantics.atMostOnceBestEffort();

        assertEquals(DeliveryMode.AT_MOST_ONCE_BEST_EFFORT, semantics.mode());
        assertFalse(semantics.retrySafe());
        assertFalse(semantics.effectivelyOncePattern());
    }

    @Test
    public void atLeastOnceSemanticsAreRetryable() {
        DeliverySemantics semantics = DeliverySemantics.atLeastOnceRetryable();

        assertEquals(DeliveryMode.AT_LEAST_ONCE_RETRYABLE, semantics.mode());
        assertTrue(semantics.retrySafe());
    }

    @Test
    public void effectivelyOnceIsPatternMetadata() {
        DeliverySemantics semantics = DeliverySemantics.effectivelyOncePatternSupport();

        assertEquals(DeliveryMode.EFFECTIVELY_ONCE_PATTERN, semantics.mode());
        assertTrue(semantics.effectivelyOncePattern());
    }

    @Test
    public void genericExactlyOnceIsRepresentedAsUnsupported() {
        DeliverySemantics semantics = DeliverySemantics.unsupportedExactlyOnce();

        assertEquals(DeliveryMode.UNSUPPORTED_EXACTLY_ONCE, semantics.mode());
        assertTrue(semantics.unsupportedExactlyOnceRequested());
        assertFalse(semantics.retrySafe());
    }

    @Test
    public void genericExactlyOnceRequestIsRejectedByPolicyEvaluator() {
        InvocationReliabilityOptions defaults = InvocationReliabilityOptions.defaults();
        InvocationReliabilityOptions options = new InvocationReliabilityOptions(DeliverySemantics.unsupportedExactlyOnce(),
                null, null, null, null, null, null, null, null, defaults.replayPolicy(),
                defaults.duplicateDetectionPolicy(), defaults.backpressurePolicy(), false, 0L, 0L, null);

        ReliabilityDecision decision = new ReliabilityPolicyEvaluator().evaluateBeforeDispatch(null, options, 0L);

        assertFalse(decision.accepted());
        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, decision.errorCode());
    }
}
