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

package org.j7m.middleware.application.distributed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.junit.jupiter.api.Test;

class PayloadFrameEngineTest {
    @Test
    void internalInputHashSupportsLargerPartitionCommandsWithoutChangingDurableOutcomeFingerprint() {
        var schemas = new SchemaRegistry(List.of()); var values = new DefaultValueCodec(schemas);
        var internal = new PayloadFrameEngine(values, schemas, 131072);
        var durable = new ManagedPayloadCodec(values, schemas);
        var signature = new org.j7m.middleware.application.api.ManagedMethod("example.Program", "apply", List.of(byte[].class.getName()), "void");
        var contract = new org.j7m.middleware.application.api.ManagedServiceContract("partition", 1, signature);
        assertEquals(durable.inputHash(contract, List.of(byte[].class), new Object[]{new byte[]{1}}),
                internal.inputHash(contract, List.of(byte[].class), new Object[]{new byte[]{1}}));
        byte[] command = new byte[64000];
        String first = internal.inputHash(contract, List.of(byte[].class), new Object[]{command});
        command[63999] = 1;
        org.junit.jupiter.api.Assertions.assertNotEquals(first, internal.inputHash(contract, List.of(byte[].class), new Object[]{command}));
        assertThrows(MiddlewareException.class, () -> durable.inputHash(contract, List.of(byte[].class), new Object[]{command}));
        assertThrows(MiddlewareException.class, () -> internal.inputHash(contract, List.of(byte[].class), new Object[]{new byte[131072]}));
    }

    @Test
    void sharedEnginePreservesVersionOneBytesAndPublicDurableCap() {
        var schemas = new SchemaRegistry(List.of());
        var values = new DefaultValueCodec(schemas);
        var publicCodec = new ManagedPayloadCodec(values, schemas);
        var stateCodec = new PayloadFrameEngine(values, schemas, 131072);
        assertArrayEquals(publicCodec.encode("version-one", String.class), stateCodec.encode("version-one", String.class));
        byte[] data = new byte[64000]; data[63999] = 71;
        byte[] frame = stateCodec.encode(data, byte[].class);
        assertArrayEquals(data, (byte[]) stateCodec.decode(frame, byte[].class));
        assertThrows(MiddlewareException.class, () -> publicCodec.decode(frame, byte[].class));
        assertThrows(IllegalArgumentException.class, () -> new ManagedPayloadCodec(values, schemas, 131072));
    }

    @Test
    void internalFrameLimitIncludesHeadersAndRejectsOneExtraByte() {
        var schemas = new SchemaRegistry(List.of());
        var values = new DefaultValueCodec(schemas);
        var codec = new PayloadFrameEngine(values, schemas, 131072);
        int overhead = codec.encode(new byte[0], byte[].class).length;
        byte[] exact = codec.encode(new byte[131072 - overhead], byte[].class);
        assertEquals(131072, exact.length);
        assertThrows(MiddlewareException.class, () -> codec.encode(new byte[131073 - overhead], byte[].class));
        assertThrows(MiddlewareException.class, () -> codec.decode(java.util.Arrays.copyOf(exact, exact.length + 1), byte[].class));
        assertThrows(IllegalArgumentException.class, () -> new PayloadFrameEngine(values, schemas, 0));
        assertThrows(IllegalArgumentException.class, () -> new PayloadFrameEngine(values, schemas, 16777217));
    }
}
