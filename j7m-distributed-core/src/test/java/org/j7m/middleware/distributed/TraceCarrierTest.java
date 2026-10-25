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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TraceCarrierTest {
    static final String VALID = "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01";
    @Test void roundTripsOnlyAllowedHeadersAndEmptyContext() {
        assertEquals(Map.of(), TraceCarrier.empty().asMap());
        var valid = new TraceCarrier(VALID, "vendor=value,other=value");
        assertEquals(valid, TraceCarrier.fromMap(valid.asMap()));
        assertThrows(IllegalArgumentException.class, () -> TraceCarrier.fromMap(Map.of("baggage", "secret")));
        assertThrows(IllegalArgumentException.class, () -> new TraceCarrier("", "vendor=value"));
    }
    @ParameterizedTest
    @ValueSource(strings = {"00-00000000000000000000000000000000-1234567890abcdef-01", "00-1234567890abcdef1234567890abcdef-0000000000000000-01", "00-1234567890ABCDEF1234567890abcdef-1234567890abcdef-01", "01-1234567890abcdef1234567890abcdef-1234567890abcdef-01", "bad", ""})
    void rejectsMalformedParentExceptExplicitEmpty(String parent) {
        if (parent.isEmpty()) assertEquals(TraceCarrier.empty(), new TraceCarrier(parent, ""));
        else assertThrows(IllegalArgumentException.class, () -> new TraceCarrier(parent, ""));
    }
    @ParameterizedTest
    @ValueSource(strings = {"vendor=value,", "vendor=one,vendor=two", "vendor=secret\r\nInjected=header", "=bad", "vendor=", "v=é"})
    void rejectsTracestateDuplicatesInjectionAndMalformedMembers(String state) { assertThrows(IllegalArgumentException.class, () -> new TraceCarrier(VALID, state)); }
    @Test void validatesBoundaries() {
        assertDoesNotThrow(() -> new TraceCarrier(VALID, "a=" + "v".repeat(256)));
        assertThrows(IllegalArgumentException.class, () -> new TraceCarrier(VALID, "a=" + "v".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> new TraceCarrier(VALID, "v".repeat(513)));
    }
}
