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

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Separate bounded-heap reproduction of accepted queue state exceeding the node snapshot limit.
 */
public final class QueueSnapshotBudgetProbe {
    private static long index;
    private static final QueueStateMachine QUEUE = new QueueStateMachine("queue");
    private static final PartitionStateMachine PARTITION = new PartitionStateMachine("queue", "broker", 1, QUEUE, new byte[32]);
    private QueueSnapshotBudgetProbe() { }
    private static String apply(String op, String id, String... values) {
        ResourceCommand command = new ResourceCommand(op, "tenant", id, List.of(values)); byte[] bytes = command.encode();
        try { QUEUE.validate(bytes, "tenant", true); }
        catch (IllegalStateException overloaded) { if (overloaded.getMessage().startsWith("OVERLOADED")) return "OVERLOADED"; throw overloaded; }
        PARTITION.apply(++index, PartitionStateMachine.mutation(1, bytes));
        return new String(QUEUE.query(new ResourceCommand("Q_RESULT", "tenant", "query", List.of(id)).encode()), StandardCharsets.UTF_8);
    }
    /**
     * Exercises actual native snapshots in a child JVM so the old oversized frame cannot OOM Gradle.
     * @param arguments unused
     */
    public static void main(String[] arguments) {
        apply("Q_JOIN", "join", "workers", "member", "86400000");
        String payload = Base64.getEncoder().encodeToString(new byte[32768]);
        for (int i = 0; i < 4; i++) apply("Q_SEND", "send" + i, "q", "m" + i, payload, "1000");
        int accepted = 0; String first = null;
        for (int i = 0; i < 3100; i++) {
            String result = apply("Q_CLAIM", "claim" + i, "q", "workers", "member", "1", "60000", "1");
            if (result.equals("OVERLOADED")) break;
            if (!result.startsWith("MESSAGE\t")) throw new AssertionError("Unexpected claim result " + result);
            if (first == null) first = result;
            String[] fields = result.split("\t");
            if (apply("Q_NACK", "nack" + i, "q", "workers", "member", "1", fields[1], fields[4]).equals("OVERLOADED")) break;
            accepted++;
        }
        byte[] snapshot = PARTITION.snapshot();
        System.out.println("accepted=" + accepted + " snapshotBytes=" + snapshot.length + " limit=" + (128 * 1024 * 1024));
        if (snapshot.length > 128 * 1024 * 1024) throw new AssertionError("Accepted state cannot fit the qualified node snapshot limit");
        PARTITION.restore(snapshot);
        String historical = new String(QUEUE.query(new ResourceCommand("Q_RESULT", "tenant", "query", List.of("claim0")).encode()), StandardCharsets.UTF_8);
        if (!Objects.equals(first, historical)) throw new AssertionError("Historical receipt changed across restore");
        if (!Arrays.equals(snapshot, PARTITION.snapshot())) throw new AssertionError("Snapshot restore changed state");
    }
}
