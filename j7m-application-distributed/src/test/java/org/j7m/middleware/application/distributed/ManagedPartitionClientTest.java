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

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.annotations.DataLocality;
import org.j7m.middleware.annotations.OrderedBy;
import org.j7m.middleware.annotations.PartitionHandler;
import org.j7m.middleware.annotations.ShardKey;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.distributed.PartitionEndpoint;
import org.j7m.middleware.distributed.PartitionReply;
import org.j7m.middleware.distributed.PartitionStateMachine;
import org.j7m.middleware.distributed.PartitionTopology;
import org.junit.jupiter.api.Test;

class ManagedPartitionClientTest {
    @PartitionHandler(resource = "counter") @ShardKey @OrderedBy("") @DataLocality("near")
    public static class ShardedCounter extends ManagedPartitionResourceTest.Counter { }
    private static final String FP = "a".repeat(64);
    private static final SchemaRegistry SCHEMAS = new SchemaRegistry(List.of());
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ManagedPartitionResourceTest.Definition definition() {
        return new ManagedPartitionResourceTest.Definition() {
            @Override public Class<ManagedPartitionResourceTest.Counter> type() { return (Class) ShardedCounter.class; }
        };
    }
    private static final class Endpoint implements PartitionEndpoint {
        final ManagedPartitionResource resource;
        final String group;
        long index;
        int calls;
        Endpoint(String group) {
            this.group = group;
            resource = new ManagedPartitionResource(definition(), new ShardedCounter(), new DefaultValueCodec(SCHEMAS), SCHEMAS, FP, group, "tenant", 2);
            resource.ownershipEpoch(1);
        }
        @Override public PartitionReply execute(long epoch, String request, byte[] command, byte[] resultQuery, Instant deadline) {
            calls++; resource.validate(command, "tenant", false);
            if (!resource.requestIdentity(command).equals(request) || resource.identityConflict(command)) throw new IllegalArgumentException("Conflict");
            resource.apply(++index, command); return new PartitionReply("OK", epoch, resource.query(resultQuery));
        }
        @Override public PartitionReply query(long epoch, byte[] query, Instant deadline) { calls++; resource.validateQuery(query, "tenant"); return new PartitionReply("OK", epoch, resource.query(query)); }
        @Override public PartitionStateMachine.Status status(Instant deadline) { return new PartitionStateMachine.Status("partition", group, 1, "ACTIVE", "", "", index); }
    }
    @Test void declaredShardAndDurableOrderingSurviveReroutingWithoutLettingLocalityOverrideAuthority() throws Exception {
        var first = new Endpoint("first"); var second = new Endpoint("second");
        var owner = new java.util.concurrent.atomic.AtomicReference<>("first");
        var preference = new java.util.concurrent.atomic.AtomicInteger();
        PartitionEndpoint unavailable = new PartitionEndpoint() {
            @Override public PartitionReply execute(long epoch, String request, byte[] command, byte[] query, Instant deadline) throws IOException { preference.incrementAndGet(); throw new IOException("Unavailable"); }
            @Override public PartitionReply query(long epoch, byte[] query, Instant deadline) throws IOException { preference.incrementAndGet(); throw new IOException("Unavailable"); }
            @Override public PartitionStateMachine.Status status(Instant deadline) throws IOException { throw new IOException("Unavailable"); }
        };
        String locality = ShardedCounter.class.getAnnotation(DataLocality.class).value();
        var client = new ManagedPartitionClient(
                (key, deadline) -> new PartitionTopology.Route("partition", owner.get(), owner.get().equals("first") ? 1 : 2),
                Map.of("first", new ManagedPartitionClient.Group(first, Map.of(locality, unavailable)),
                        "second", new ManagedPartitionClient.Group(second, Map.of())), definition(), FP, "tenant", new DefaultValueCodec(SCHEMAS), SCHEMAS);
        var deadline = Instant.now().plusSeconds(2);
        assertThrows(IllegalArgumentException.class, () -> client.query("query-key", false, String.class, deadline));
        assertEquals(0, first.calls); assertEquals(0, preference.get());
        assertTrue(client.routingKey(new byte[]{5}).matches("[0-9a-f]{64}"));
        assertNotEquals(client.routingKey(new byte[]{5}), client.routingKey(new byte[]{6}));
        var receipt = client.execute("one", new byte[]{5}, deadline); assertEquals(1, receipt.sequence());
        assertTrue(preference.get() > 0); assertArrayEquals(new byte[]{5}, client.query("query-key", false, byte[].class, deadline));
        assertThrows(IllegalArgumentException.class, () -> client.execute("bad", "forged-shard", new byte[]{8}, deadline));
        second.resource.restore(first.resource.snapshot()); second.resource.ownershipEpoch(2); second.index = first.index;
        owner.set("second"); int oldCalls = first.calls;
        assertEquals(receipt, client.execute("one", new byte[]{5}, deadline));
        assertEquals(oldCalls, first.calls); assertArrayEquals(new byte[]{5}, client.query("query-key", false, byte[].class, deadline));
        assertThrows(IllegalArgumentException.class, () -> client.execute("one", new byte[]{9}, deadline));
        var next = client.execute("two", new byte[]{2}, deadline); assertTrue(next.sequence() > receipt.sequence());
        assertEquals("OVERLOADED", client.execute("three", new byte[]{1}, deadline).status());
        assertArrayEquals(new byte[]{7}, client.query("query-key", false, byte[].class, deadline));
    }
}
