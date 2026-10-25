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
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class InvocationTraceContextTest {
    static final String PARENT = "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01";
    @Test void snapshotsContainOnlyFixedValidParentAndRestoreThreadContextWithoutInheritance() throws Exception {
        Map<String, String> source = new HashMap<>(Map.of("traceparent", PARENT, "tracestate", "secret=value", "principal", "administrator"));
        assertTrue(InvocationTraceContext.current().isEmpty());
        try (var scope = InvocationTraceContext.open(source); var worker = Executors.newSingleThreadExecutor()) {
            source.clear();
            assertEquals(Map.of("traceparent", PARENT), InvocationTraceContext.current());
            assertThrows(UnsupportedOperationException.class, () -> InvocationTraceContext.current().put("other", "secret"));
            assertTrue(worker.submit(InvocationTraceContext::current).get(2, TimeUnit.SECONDS).isEmpty());
            assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class,
                    () -> worker.submit(scope::close).get(2, TimeUnit.SECONDS)).getCause());
            try (var nested = InvocationTraceContext.open(Map.of())) { assertTrue(InvocationTraceContext.current().isEmpty()); }
            assertEquals(PARENT, InvocationTraceContext.current().get("traceparent"));
        }
        assertTrue(InvocationTraceContext.current().isEmpty());
    }
    @Test void malformedOversizedZeroAndUnsupportedVersionParentsAreDropped() {
        for (String invalid : List.of("", "x".repeat(10000), "00-" + "0".repeat(32) + "-1234567890abcdef-01",
                "00-1234567890abcdef1234567890abcdef-" + "0".repeat(16) + "-01", PARENT.replace("00-", "ff-"), PARENT.toUpperCase(Locale.ROOT))) {
            assertTrue(InvocationTraceContext.sanitized(Map.of("traceparent", invalid)).isEmpty());
        }
        assertTrue(InvocationTraceContext.sanitized(null).isEmpty());
        assertEquals(Map.of("traceparent", PARENT), InvocationTraceContext.sanitized(Map.of("traceparent", PARENT)));
    }
}
