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

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.Map;
import org.j7m.middleware.api.*;
import org.j7m.middleware.transport.api.ProtocolEnvelope;
import org.junit.jupiter.api.Test;

class ProtocolEnvelopeTest {
    private final Instant now = Instant.parse("2026-10-22T00:00:00Z");
    private ProtocolEnvelope envelope(int version, byte[] payload) {
        return new ProtocolEnvelope(version, "request", "schema", 1, now.plusSeconds(1), Map.of("traceparent", "trace"), false, "peer", payload);
    }
    @Test void acceptsMaximumPayloadAndMakesIndependentCopies() {
        byte[] payload = new byte[1024 * 1024];
        var envelope = envelope(1, payload);
        payload[0] = 1;
        assertEquals(0, envelope.payload()[0]);
        envelope.payload()[0] = 2;
        assertEquals(0, envelope.payload()[0]);
        envelope.validateForDispatch("peer", Clock.fixed(now, ZoneOffset.UTC));
    }
    @Test void rejectsForeignPeerExpiredDeadlineAndOversizedPayloadBeforeDispatch() {
        var envelope = envelope(1, new byte[0]);
        assertEquals(MiddlewareErrorCode.UNAUTHENTICATED, assertThrows(MiddlewareException.class,
                () -> envelope.validateForDispatch("foreign", Clock.fixed(now, ZoneOffset.UTC))).code());
        assertEquals(MiddlewareErrorCode.DEADLINE_EXCEEDED, assertThrows(MiddlewareException.class,
                () -> envelope.validateForDispatch("peer", Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC))).code());
        assertThrows(IllegalArgumentException.class, () -> envelope(2, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> envelope(1, new byte[1024 * 1024 + 1]));
    }
}
