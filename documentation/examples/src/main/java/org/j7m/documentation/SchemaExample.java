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

package org.j7m.documentation;

import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.codec.api.SchemaDescriptor;
import org.j7m.middleware.codec.api.SchemaField;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;

/**
 * Compares a generated allowlisted record codec with an explicit schema descriptor.
 */
public final class SchemaExample {
    private SchemaExample() { }

    /**
     * Runs both schema registrations and verifies an unregistered record is rejected.
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        var annotated = new DefaultValueCodec(new SchemaRegistry(new Order__J7mSchema().schemas()));
        var order = new Order("order-42", 3);
        Object restored = annotated.decode(annotated.encode(order, Order.class, null), Order.class, null);
        require(order.equals(restored), "Generated schema lost record data");

        var descriptor = new SchemaDescriptor<>("documentation.explicit-order", 1, PlainOrder.class,
                List.of(new SchemaField("1", String.class, true, null), new SchemaField("2", Integer.class, true, null)),
                value -> Map.of("1", value.id(), "2", value.quantity()),
                fields -> new PlainOrder((String) fields.get("1"), (Integer) fields.get("2")));
        var explicit = new DefaultValueCodec(new SchemaRegistry(List.of(descriptor)));
        var plain = new PlainOrder("order-42", 3);
        require(plain.equals(explicit.decode(explicit.encode(plain, PlainOrder.class, null), PlainOrder.class, null)),
                "Explicit schema lost record data");
        try {
            new DefaultValueCodec(new SchemaRegistry(List.of())).encode(order, Order.class, null);
            throw new AssertionError("An unregistered record was accepted");
        } catch (MiddlewareException expected) {
            require(expected.remoteError() != null, "Codec failure was not normalized");
        }
        System.out.println("SchemaExample OK: generated and explicit schemas, roundtrip, allowlist rejection");
    }
    private record PlainOrder(String id, int quantity) { }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
