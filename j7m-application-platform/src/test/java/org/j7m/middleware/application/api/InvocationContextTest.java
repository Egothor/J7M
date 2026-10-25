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
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class InvocationContextTest {
    @Test void legacyAndNullControlsStayFailClosedWithoutChangingContextNormalization() {
        var legacy = new InvocationContext(null, null, null);
        var explicit = new InvocationContext(null, null, null, null);
        assertEquals(legacy, explicit); assertEquals("", legacy.correlationId());
        assertNotNull(legacy.security()); assertEquals(InvocationApplicationContext.empty(), legacy.application());
        assertSame(InvocationControl.unavailable(), legacy.control());
        assertThrows(IllegalStateException.class, () -> legacy.control().submit(() -> "must not run"));
        assertSame(InvocationControl.unavailable(), InvocationContext.empty().control());
    }
    @Test void hostControlSurvivesScopedContextAndPreviousContextIsRestored() {
        var control = new InvocationControl() {
            @Override public Duration remainingBudget() { return Duration.ofMillis(7); }
            @Override public boolean cancellationRequested() { return true; }
            @Override public <T> CompletionStage<T> submit(Callable<T> work) { throw new IllegalStateException("host fixture closed"); }
            @Override public Executor contextualExecutor() { throw new IllegalStateException("host fixture closed"); }
        };
        var outer = new InvocationContext(null, null, "outer");
        var inner = new InvocationContext(null, null, "inner", control);
        try (var ignored = J7mContext.open(outer)) {
            try (var nested = J7mContext.open(inner)) {
                assertSame(inner, J7mContext.currentInvocation()); assertSame(control, J7mContext.currentInvocation().control());
                assertEquals(Duration.ofMillis(7), control.remainingBudget()); assertTrue(control.cancellationRequested());
            }
            assertSame(outer, J7mContext.currentInvocation());
        }
        assertEquals(InvocationContext.empty(), J7mContext.currentInvocation());
    }
}
