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
import java.util.*;
import org.junit.jupiter.api.Test;

class ExecutionResultTest {
    @Test void aggregateUnicodeAndExactWireBoundaryRoundTripOrRejectBeforeEncoding() {
        Map<String, String> unicode = new TreeMap<>();
        for (int i = 0; i < 21; i++) unicode.put("field-" + i, "漢".repeat(16384));
        var valid = new ExecutionResult("OK", unicode); assertEquals(valid, ExecutionResult.decode(valid.encode()));
        unicode.put("overflow", "漢".repeat(16384)); assertThrows(IllegalArgumentException.class, () -> new ExecutionResult("OK", unicode));
        Map<String, String> exact = new TreeMap<>(); for (int i = 0; i < 64; i++) exact.put(String.format("k%02d", i), "x".repeat(16384)); exact.put("k63", "x".repeat(16384 - 460));
        var maximum = new ExecutionResult("OK", exact); assertEquals(1048576, maximum.encode().length); assertEquals(maximum, ExecutionResult.decode(maximum.encode()));
        exact.put("k63", exact.get("k63") + "x"); assertThrows(IllegalArgumentException.class, () -> new ExecutionResult("OK", exact));
    }
    @Test void emptyAndModifiedUtfNullContextRemainImmutableAndMalformedEncodingFails() {
        Map<String, String> fields = new HashMap<>(); fields.put("null-character", "\0"); fields.put("context.traceparent", "carrier");
        var result = new ExecutionResult("OK", fields); fields.clear(); assertEquals(result, ExecutionResult.decode(result.encode())); assertEquals(Map.of("traceparent", "carrier"), result.context());
        assertThrows(UnsupportedOperationException.class, () -> result.fields().put("x", "y")); assertEquals(new ExecutionResult("OK", Map.of()), ExecutionResult.decode(new ExecutionResult("OK", Map.of()).encode()));
        assertThrows(IllegalArgumentException.class, () -> ExecutionResult.decode(Arrays.copyOf(result.encode(), result.encode().length - 1)));
    }
}
