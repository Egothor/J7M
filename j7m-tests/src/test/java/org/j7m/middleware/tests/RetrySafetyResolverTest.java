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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.NonIdempotent;
import org.j7m.middleware.api.reliability.DeliverySemantics;
import org.j7m.middleware.api.reliability.IdempotencyKey;
import org.j7m.middleware.api.reliability.InvocationReliabilityOptions;
import org.j7m.middleware.reliability.RetrySafetyResolver;
import org.junit.jupiter.api.Test;

public final class RetrySafetyResolverTest {
    @Test
    public void idempotentAnnotationPermitsRetrySafeClassification() throws NoSuchMethodException {
        Method method = RetryApi.class.getMethod("safe");

        assertTrue(new RetrySafetyResolver().isRetrySafe(method, retryable(null)));
    }

    @Test
    public void nonIdempotentAnnotationPreventsAutomaticRetry() throws NoSuchMethodException {
        Method method = RetryApi.class.getMethod("unsafe");

        assertFalse(new RetrySafetyResolver().isRetrySafe(method, retryable(new IdempotencyKey("idem-a"))));
    }

    @Test
    public void explicitOptionsCanTightenButNotUnsafelyLoosenDefaults() throws NoSuchMethodException {
        RetrySafetyResolver resolver = new RetrySafetyResolver();

        assertFalse(resolver.isRetrySafe(RetryApi.class.getMethod("plain"), InvocationReliabilityOptions.defaults()));
        assertTrue(resolver.isRetrySafe(RetryApi.class.getMethod("plain"), retryable(new IdempotencyKey("idem-b"))));
    }

    @Test
    public void deadlineRemainsInvocationOptionMetadataNotRetryPermission() throws NoSuchMethodException {
        assertFalse(new RetrySafetyResolver().isRetrySafe(RetryApi.class.getMethod("plain"), InvocationReliabilityOptions.defaults()));
    }

    private static InvocationReliabilityOptions retryable(final IdempotencyKey key) {
        InvocationReliabilityOptions defaults = InvocationReliabilityOptions.defaults();
        return new InvocationReliabilityOptions(DeliverySemantics.atLeastOnceRetryable(), key, null, null, null,
                null, null, null, null, defaults.replayPolicy(), defaults.duplicateDetectionPolicy(),
                defaults.backpressurePolicy(), false, 0L, 0L, null);
    }

    public interface RetryApi {
        @Idempotent
        void safe();

        @NonIdempotent
        void unsafe();

        void plain();
    }
}
