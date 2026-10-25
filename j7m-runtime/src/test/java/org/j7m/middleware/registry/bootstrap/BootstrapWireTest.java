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

package org.j7m.middleware.registry.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.registry.api.RegistryQuery;
import org.junit.jupiter.api.Test;

class BootstrapWireTest {
    @Test void packetRetainsEveryRecordFieldAndPeerRoutingMetadataWithoutSerialization() throws Exception {
        var record = BootstrapTestProcess.record("record", 123456789);
        var query = new RegistryQuery("service", List.of("example.Service"), Map.of("k", "v"), "tenant", 123, 2);
        var decoded = BootstrapWire.packet(BootstrapWire.packet(query, List.of(record)));
        assertEquals(record, decoded.records().getFirst());
        assertEquals(query, decoded.query());
        var peer = BootstrapTestProcess.peer("peer", "bootstrap+tls://localhost:4567");
        byte[] encoded = BootstrapWire.encode(output -> BootstrapWire.peers(output, List.of(peer)));
        var input = BootstrapWire.input(encoded);
        assertEquals(List.of(peer), BootstrapWire.peers(input));
        BootstrapWire.end(input);
    }

    @Test void frameAllocationAndEncoderHaveExactLimitsAndRejectTruncation() throws Exception {
        byte[] maximum = new byte[BootstrapWire.MAX_FRAME];
        assertArrayEquals(maximum, BootstrapWire.encode(output -> output.write(maximum)));
        assertThrows(IOException.class, () -> BootstrapWire.encode(output -> {
            output.write(maximum);
            output.writeByte(1);
        }));
        for (int length : List.of(-1, 0, BootstrapWire.MAX_FRAME + 1, Integer.MAX_VALUE)) {
            assertThrows(IOException.class, () -> BootstrapWire.receive(new DataInputStream(
                    new ByteArrayInputStream(ByteBuffer.allocate(4).putInt(length).array()))));
        }
        assertThrows(IOException.class, () -> BootstrapWire.receive(BootstrapWire.input(new byte[]{0, 0, 0, 1})));
        assertThrows(IOException.class, () -> BootstrapWire.bytes(BootstrapWire.input(new byte[]{0, 4, 0, 0})));
    }

    @Test void malformedCollectionsEnumAndTrailingDataAreRejected() throws Exception {
        assertThrows(IOException.class, () -> BootstrapWire.peers(BootstrapWire.input(new byte[]{127, -1, -1, -1})));
        assertThrows(IOException.class, () -> BootstrapWire.encode(output -> BootstrapWire.peers(output,
                java.util.Collections.nCopies(65, BootstrapTestProcess.peer("p", "bootstrap+tls://localhost:1")))));
        assertThrows(IOException.class, () -> BootstrapWire.encode(output -> BootstrapWire.string(output, "x".repeat(2049))));
        byte[] valid = BootstrapWire.packet(null, List.of(BootstrapTestProcess.record("r", 0)));
        assertThrows(RuntimeException.class, () -> BootstrapWire.packet(Arrays.copyOf(valid, valid.length - 1)));
        assertThrows(RuntimeException.class, () -> BootstrapWire.packet(Arrays.copyOf(valid, valid.length + 1)));
        assertThrows(IOException.class, () -> BootstrapWire.end(BootstrapWire.input(new byte[]{1})));
        byte[] invalidEnum = BootstrapWire.encode(output -> {
            output.writeInt(1); BootstrapWire.string(output, "peer"); output.writeBoolean(true);
            BootstrapWire.string(output, "UNKNOWN_ENUM");
        });
        assertThrows(IllegalArgumentException.class, () -> BootstrapWire.peers(BootstrapWire.input(invalidEnum)));
    }
}
