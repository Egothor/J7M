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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.distributed.ExecutionResult;
import org.j7m.middleware.distributed.PartitionEndpoint;
import org.j7m.middleware.distributed.PartitionReply;
import org.j7m.middleware.distributed.PartitionStateMachine;
import org.junit.jupiter.api.Test;

class ManagedExecutionBackendTest {
    private static Instant deadline(){return Instant.now().plusSeconds(2);}
    @Test void samePhysicalGroupAtANewEpochCannotRetargetAnAlreadyOpenedSession()throws Exception {
        var endpoint=new Endpoint();var backend=ManagedExecutionBackend.group("execution","tenant",endpoint);
        var old=backend.open(deadline());assertEquals("group",old.group());assertEquals(1,old.epoch());
        endpoint.epoch=3;var fresh=backend.open(deadline());assertEquals(3,fresh.epoch());
        assertThrows(IOException.class,()->old.verify(deadline()));
        assertThrows(IOException.class,()->old.jobs().renew("stale","job","worker",1,1000,deadline()));
        assertEquals(0,endpoint.effects);assertEquals("NOT_FOUND",fresh.jobs().status("absent",deadline()).status());
        assertTrue(old.toString().contains("pinned"));assertTrue(backend.toString().contains("host-owned"));
    }
    @Test void moveDuringActualMutationIsNotRetriedAndKnownCapacityRepliesRemainExplicit()throws Exception {
        var endpoint=new Endpoint();var backend=ManagedExecutionBackend.group("execution","tenant",endpoint);
        var session=backend.open(deadline());endpoint.reply="MOVED";
        assertThrows(IOException.class,()->session.jobs().submit("move","job","input",deadline()));assertEquals(1,endpoint.effects);
        endpoint.reply="OVERLOADED";assertEquals("OVERLOADED",session.jobs().submit("overload","job","input",deadline()).status());
        endpoint.reply="REQUEST_CONFLICT";assertEquals("REQUEST_CONFLICT",session.jobs().submit("conflict","job","input",deadline()).status());
        endpoint.phase="FROZEN";assertThrows(IOException.class,()->backend.open(deadline()));
    }
    private static final class Endpoint implements PartitionEndpoint {
        long epoch=1;String phase="ACTIVE",reply="OK";int effects;
        @Override public PartitionStateMachine.Status status(Instant deadline){return new PartitionStateMachine.Status("execution","group",epoch,phase,"","",0);}
        @Override public PartitionReply execute(long epoch,String request,byte[] command,byte[] resultQuery,Instant deadline){effects++;return new PartitionReply(reply,this.epoch,new ExecutionResult("OK",Map.of()).encode());}
        @Override public PartitionReply query(long epoch,byte[] query,Instant deadline){return new PartitionReply(reply,this.epoch,new ExecutionResult("NOT_FOUND",Map.of()).encode());}
    }
}
