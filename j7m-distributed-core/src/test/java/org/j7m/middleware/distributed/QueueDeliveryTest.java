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
import org.junit.jupiter.api.Test;

class QueueDeliveryTest {
    @Test void receiptPayloadIsIndependentAndObservationNeverChangesCapturedAuthority() {
        byte[] bytes = new byte[32768]; bytes[0] = 4;
        var delivery = new QueueDelivery("q", "g", "m", 1, "message", bytes, 7, 9, 1000);
        bytes[0] = 8; byte[] read = delivery.payload(); read[0] = 2;
        assertEquals(4, delivery.payload()[0]); assertFalse(delivery.observational());
        var observation = new QueueDelivery("q", "g", "m", 1, "message", new byte[0], 7, 9, 1, true);
        assertTrue(observation.observational()); assertEquals(9, observation.receipt());
        assertEquals(7, observation.sequence()); assertFalse(observation.toString().contains("message"));
        assertThrows(IllegalArgumentException.class, () -> new QueueDelivery("q", "g", "m", 0, "message", bytes, 7, 9, 1));
        assertThrows(IllegalArgumentException.class, () -> new QueueDelivery("q", "g", "m", 1, "message", new byte[32769], 7, 9, 1));
        assertThrows(IllegalArgumentException.class, () -> new QueueDelivery("q", "g", "m", 1, "message", bytes, 0, 9, 1));
    }
}
