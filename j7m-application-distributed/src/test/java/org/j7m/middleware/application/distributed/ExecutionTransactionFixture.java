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

import java.time.*;
import java.util.*;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.distributed.*;

/**
 * Actual native execution machine behind the protocol edge; no fake deduplication decisions.
 */
final class ExecutionTransactionFixture implements PartitionEndpoint {
    final ExecutionStateMachine machine = new ExecutionStateMachine("execution");
    long index;
    final ExecutionClient client = new ExecutionClient("execution", "tenant", (partition, deadline) -> new ReplicatedQueue.Route(this, 1));
    final ManagedDeduplicationPolicy policy;
    ExecutionTransactionFixture() {
        machine.apply(++index, machine.timeCommand(100)); var schemas = new SchemaRegistry(List.of());
        policy = new ManagedDeduplicationPolicy(Map.of("default", new ManagedDeduplicationPolicy.Registration(new DeduplicatedRpc(client), new ManagedPayloadCodec(new DefaultValueCodec(schemas), schemas), Duration.ofMinutes(1))));
    }
    @Override public synchronized PartitionReply execute(long epoch, String id, byte[] command, byte[] query, Instant deadline) {
        machine.apply(++index, command); return new PartitionReply("OK", epoch, machine.query(query));
    }
    @Override public synchronized PartitionReply query(long epoch, byte[] query, Instant deadline) { return new PartitionReply("OK", epoch, machine.query(query)); }
    @Override public PartitionStateMachine.Status status(Instant deadline) { throw new UnsupportedOperationException(); }
}
