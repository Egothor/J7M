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

package org.j7m.middleware.transport.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.grpc.StatusRuntimeException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.codec.api.EncodedValue;
import org.j7m.middleware.transport.api.MethodDescriptor;
import org.junit.jupiter.api.Test;

/**
 * Focused gRPC wire codec regressions for Wave 1 transport bounds.
 */
public final class GrpcWireCodecTest {
    @Test
    public void registeredSchemaPreservesNestedFieldsAndContracts() {
        EncodedValue encoded = new EncodedValue("registered-schema", "customer", Map.of("name",
                new EncodedValue("scalar", String.class.getName(), "Ada",
                        Map.of("schemaRequired", "true", "schemaFieldType", String.class.getName()))),
                Map.of("schemaVersion", "2"));
        assertEquals(encoded, GrpcWireCodec.decodeValue(GrpcWireCodec.encodeValue(encoded)));
    }

    @Test
    public void encodedValueMetadataRoundTrips() {
        EncodedValue encoded = new EncodedValue("scalar", String.class.getName(), "value",
                Map.of("codec.schema", "trusted", "j7m.marker", "preserved"));

        EncodedValue decoded = GrpcWireCodec.decodeValue(GrpcWireCodec.encodeValue(encoded));

        assertEquals(encoded.metadata(), decoded.metadata());
        assertEquals(encoded.kind(), decoded.kind());
        assertEquals(encoded.typeName(), decoded.typeName());
        assertEquals(encoded.value(), decoded.value());
    }

    @Test
    public void encodedValueMetadataEntryLimitIsEnforced() {
        Map<String, String> metadata = new LinkedHashMap<>();
        for (int index = 0; index < 33; index++) {
            metadata.put("key-" + index, "value");
        }

        assertThrows(IllegalArgumentException.class,
                () -> GrpcWireCodec.encodeValue(new EncodedValue("scalar", String.class.getName(), "value", metadata)));
    }

    @Test
    public void streamRequestDecodeRejectsOversizedItemList() {
        List<EncodedValue> items = new ArrayList<>(GrpcTransportProvider.MAX_STREAM_ITEMS + 1);
        for (int index = 0; index <= GrpcTransportProvider.MAX_STREAM_ITEMS; index++) {
            items.add(new EncodedValue("scalar", Integer.class.getName(), Integer.valueOf(index), Map.of()));
        }
        GrpcWire.StreamRequest request = new GrpcWire.StreamRequest(ref(), method(), items,
                new InvocationOptions("oversized-stream", Duration.ofSeconds(1L), Map.of()), false);

        assertThrows(UncheckedIOException.class,
                () -> GrpcWireCodec.decodeStreamRequest(GrpcWireCodec.inputStream(GrpcWireCodec.encodeStreamRequest(request))));
    }

    @Test
    public void clientStreamCumulativeItemsRejectMultiFrameOverflow() {
        int count = GrpcTransportProvider.checkedInboundStreamItemCount(0, requestWithItems(4095), false);

        assertEquals(4095, count);
        assertThrows(StatusRuntimeException.class,
                () -> GrpcTransportProvider.checkedInboundStreamItemCount(count, requestWithItems(2), false));
    }

    @Test
    public void bidiStreamCumulativeItemsRejectMultiFrameOverflow() {
        int count = GrpcTransportProvider.checkedInboundStreamItemCount(0, requestWithItems(2048), true);
        count = GrpcTransportProvider.checkedInboundStreamItemCount(count, requestWithItems(2048), true);

        assertEquals(GrpcTransportProvider.MAX_STREAM_ITEMS, count);
        int fullCount = count;
        assertThrows(StatusRuntimeException.class,
                () -> GrpcTransportProvider.checkedInboundStreamItemCount(fullCount, requestWithItems(1), true));
    }

    @Test
    public void bidiStreamCountsEmptyFramesAsBoundedMessages() {
        int count = 0;
        GrpcWire.StreamRequest empty = requestWithItems(0);
        for (int index = 0; index < GrpcTransportProvider.MAX_STREAM_ITEMS; index++) {
            count = GrpcTransportProvider.checkedInboundStreamItemCount(count, empty, true);
        }

        assertEquals(GrpcTransportProvider.MAX_STREAM_ITEMS, count);
        int fullCount = count;
        assertThrows(StatusRuntimeException.class,
                () -> GrpcTransportProvider.checkedInboundStreamItemCount(fullCount, empty, true));
    }

    private static GrpcWire.StreamRequest requestWithItems(final int itemCount) {
        List<EncodedValue> items = new ArrayList<>(itemCount);
        for (int index = 0; index < itemCount; index++) {
            items.add(new EncodedValue("scalar", Integer.class.getName(), Integer.valueOf(index), Map.of()));
        }
        return new GrpcWire.StreamRequest(ref(), method(), items,
                new InvocationOptions("stream-bound", Duration.ofSeconds(1L), Map.of()), false);
    }

    private static ObjectRef ref() {
        return new ObjectRef("object", "svc/object", "svc", "runtime", "default", List.of("api"),
                "grpc", "grpc://127.0.0.1:1", System.currentTimeMillis() + 30_000L, 1L, Map.of());
    }

    private static MethodDescriptor method() {
        return new MethodDescriptor("api", "call", List.of(Integer.class.getName()), Integer.class.getName(), "hash");
    }
}
