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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.j7m.middleware.distributed.PartitionEndpoint;
import org.j7m.middleware.distributed.PartitionReply;
import org.j7m.middleware.distributed.PartitionStateMachine;
import org.j7m.middleware.distributed.QueueStateMachine;
import org.j7m.middleware.distributed.ReplicatedQueue;

/**
 * Shows queue delivery receipts and replay through the real reducer, without claiming a quorum deployment.
 */
public final class QueueExample {
    private QueueExample() { }

    private static final class InProcessEndpoint implements PartitionEndpoint {
        private final QueueStateMachine state = new QueueStateMachine();
        private long index;
        @Override public synchronized PartitionReply execute(long epoch, String request, byte[] command, byte[] query, Instant deadline) {
            state.validate(command, "orders-tenant", false);
            state.validateQuery(query, "orders-tenant");
            state.apply(++index, command);
            return new PartitionReply("OK", epoch, state.query(query));
        }
        @Override public synchronized PartitionReply query(long epoch, byte[] query, Instant deadline) {
            state.validateQuery(query, "orders-tenant");
            return new PartitionReply("OK", epoch, state.query(query));
        }
        @Override public PartitionStateMachine.Status status(Instant deadline) { throw new UnsupportedOperationException(); }
    }

    /**
     * Verifies stable send identity, fenced acknowledgment and snapshot replay in one process.
     * The adapter intentionally bypasses consensus hosting and measured-clock admission. Use the
     * documented generated/process integration tests to verify real replicated delivery.
     * @param args unused command-line arguments
     * @throws Exception if a queue operation or snapshot validation fails
     */
    public static void main(String[] args) throws Exception {
        var endpoint = new InProcessEndpoint();
        var queue = new ReplicatedQueue("orders-partition", "orders-tenant", (partition, deadline) -> new ReplicatedQueue.Route(endpoint, 1));
        Instant deadline = Instant.now().plusSeconds(10);
        require("OK\t1".equals(queue.join("join-worker", "workers", "worker-a", 60_000, deadline)), "Membership failed");
        byte[] payload = "order-42".getBytes(StandardCharsets.UTF_8);
        String first = queue.send("send-order-42", "orders", "order-42", payload, 3, deadline);
        require(first.equals(queue.send("send-order-42", "orders", "order-42", payload, 3, deadline)), "Stable retry changed result");
        try {
            queue.send("send-order-42", "orders", "another-order", payload, 3, deadline);
            throw new AssertionError("Conflicting identity was accepted");
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage().equals("Request identity reused"), "Wrong identity rejection");
        }
        require(queue.assignment("workers", "worker-a", deadline).equals(List.of("1", "orders")), "Assignment changed");
        String[] delivery = queue.claim("claim-order-42", "orders", "workers", "worker-a", 1, 10_000, deadline).split("\t");
        require(delivery[0].equals("MESSAGE") && delivery[1].equals("order-42"), "Claim did not contain the order");
        long receipt = Long.parseLong(delivery[4]);
        require(queue.ack("ack-order-42", "orders", "workers", "worker-a", 1, "order-42", receipt, deadline).equals("ACKED"),
                "Current receipt was not acknowledged");
        require(queue.claim("claim-empty", "orders", "workers", "worker-a", 1, 10_000, deadline).equals("EMPTY"), "Queue was not empty");
        byte[] snapshot = endpoint.state.snapshot();
        var restored = new QueueStateMachine();
        restored.restore(snapshot);
        require(java.util.Arrays.equals(snapshot, restored.snapshot()), "Reducer snapshot roundtrip changed state");
        System.out.println("QueueExample OK: stable send, conflict, claim/ack receipt, reducer snapshot (single process)");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
