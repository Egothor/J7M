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

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PartitionEndpointTest {
    @Test void legacyEndpointNeverEmulatesReactiveSessionsWithQueriesOrDurableClaims() {
        PartitionEndpoint legacy=new PartitionEndpoint(){
            public PartitionReply execute(long epoch,String id,byte[] command,byte[] query,Instant end){throw new AssertionError("No fallback mutation");}
            public PartitionReply query(long epoch,byte[] query,Instant end){throw new AssertionError("No polling fallback");}
            public PartitionStateMachine.Status status(Instant end){throw new AssertionError("No discovery fallback");}
        };
        assertThrows(IOException.class,()->legacy.subscribeQueue(1,"q","g","m",1000,false,Instant.MAX));
        assertThrows(IOException.class,()->legacy.subscribeQueue(1,"q","g","m",1000,true,Instant.EPOCH));
        assertThrows(IOException.class,()->legacy.subscribeExecution(1,"","JOB","worker",1000,Instant.MAX));
        assertThrows(IOException.class,()->legacy.subscribeExecution(1,"","SAGA","worker",1000,Instant.EPOCH));
    }
}
