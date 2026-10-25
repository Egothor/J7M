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

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.j7m.middleware.annotations.PartitionHandler;
import org.j7m.middleware.annotations.ShardKey;
import org.j7m.middleware.annotations.OrderedBy;
import org.j7m.middleware.codec.api.SchemaDescriptor;
import org.j7m.middleware.codec.api.SchemaField;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.junit.jupiter.api.Test;

class PartitionRoutingTest {
    public record Command(String account, int delta) { }
    @PartitionHandler(resource = "counter") @ShardKey("7") @OrderedBy("7")
    public static class Handler extends ManagedPartitionResourceTest.Counter { }
    @PartitionHandler(resource = "counter") @ShardKey("404")
    public static class MissingField extends ManagedPartitionResourceTest.Counter { }
    @PartitionHandler(resource = "counter") @ShardKey("7") @OrderedBy("8")
    public static class ConflictingOrder extends ManagedPartitionResourceTest.Counter { }
    @PartitionHandler(resource = "counter")
    public static class WrongMethod extends ManagedPartitionResourceTest.Counter {
        @ShardKey @Override public byte[] query(byte[] state, boolean mutate) { return state; }
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ManagedPartitionResourceTest.Definition definition(Class<?> type) {
        return new ManagedPartitionResourceTest.Definition() {
            @Override public Class<ManagedPartitionResourceTest.Counter> type() { return (Class) type; }
            @Override public Class<?> commandType() { return Command.class; }
        };
    }
    private static SchemaRegistry schemas(boolean required) {
        return new SchemaRegistry(List.of(new SchemaDescriptor<>("command", 1, Command.class,
                List.of(new SchemaField("7", String.class, required, null), new SchemaField("8", Integer.class, true, null)),
                value -> Map.of("7", value.account(), "8", value.delta()),
                fields -> new Command((String) fields.get("7"), (Integer) fields.get("8")))));
    }
    @Test void numericWireIdentityExtractsOnlyDeclaredRequiredScalarAndIgnoresBusinessMutationFields() {
        var routing = new PartitionRouting(definition(Handler.class), schemas(true));
        assertTrue(routing.explicit()); assertEquals("", routing.locality());
        assertEquals(routing.key(new Command("account", 1)), routing.key(new Command("account", 2)));
        assertNotEquals(routing.key(new Command("account", 1)), routing.key(new Command("another", 1)));
        assertThrows(IllegalArgumentException.class, () -> new PartitionRouting(definition(MissingField.class), schemas(true)));
        assertThrows(IllegalArgumentException.class, () -> new PartitionRouting(definition(ConflictingOrder.class), schemas(true)));
        assertThrows(IllegalArgumentException.class, () -> new PartitionRouting(definition(WrongMethod.class), schemas(true)));
        assertThrows(IllegalArgumentException.class, () -> new PartitionRouting(definition(Handler.class), schemas(false)));
        assertThrows(IllegalArgumentException.class, () -> routing.key(new Command("x".repeat(65537), 1)));
    }
}
