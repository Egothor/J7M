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
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.j7m.middleware.distributed.ExecutionEvents;
import org.junit.jupiter.api.Test;

/**
 * Immutable native CAS metadata must never become a schedulable business job.
 */
class DefinitionManifestsTest {
    private static Instant deadline() {return Instant.now().plusSeconds(2);}
    @Test void identicalConcurrentRegistrationAndRestartPreserveIdentityWithoutCreatingWork() throws Exception {
        var endpoint=new SagaEndpoint();var backend=ManagedExecutionBackend.group("execution","tenant",endpoint);String fingerprint="a".repeat(64);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CyclicBarrier(2);var tasks=new ArrayList<Future<?>>();
            for(int n=0;n<2;n++)tasks.add(pool.submit(()->{start.await();DefinitionManifests.verify(backend.open(deadline()),"saga","commerce","orders",1,fingerprint,deadline());return null;}));
            for(var task:tasks)task.get(2,TimeUnit.SECONDS);
        }
        assertTrue(new ExecutionEvents(endpoint.client()).snapshotAndTail(deadline()).runs().isEmpty());
        var stats=endpoint.client().statistics(deadline());assertEquals("0",stats.fields().get("jobs"));assertEquals("0",stats.fields().get("sagas"));
        endpoint.machine.restore(endpoint.machine.snapshot());
        DefinitionManifests.verify(backend.open(deadline()),"saga","commerce","orders",1,fingerprint,deadline());
        assertThrows(IllegalArgumentException.class,()->DefinitionManifests.verify(backend.open(deadline()),"saga","commerce","orders",1,"b".repeat(64),deadline()));
        DefinitionManifests.verify(backend.open(deadline()),"saga","other","orders",1,"b".repeat(64),deadline());
        DefinitionManifests.verify(backend.open(deadline()),"job","commerce","orders",1,"b".repeat(64),deadline());
        assertTrue(new ExecutionEvents(endpoint.client()).snapshotAndTail(deadline()).runs().isEmpty());
    }
    @Test void incompatibleConcurrentProgramsHaveOneWinnerAndCannotResetTheManifest() throws Exception {
        var endpoint=new SagaEndpoint();var backend=ManagedExecutionBackend.group("execution","tenant",endpoint);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CyclicBarrier(2);var tasks=new ArrayList<Future<Boolean>>();
            for(String fingerprint:List.of("a".repeat(64),"b".repeat(64)))tasks.add(pool.submit(()->{
                start.await();try{DefinitionManifests.verify(backend.open(deadline()),"saga","commerce","orders",1,fingerprint,deadline());return true;}catch(IllegalArgumentException conflict){return false;}
            }));
            int winners=0;for(var task:tasks)if(task.get(2,TimeUnit.SECONDS))winners++;assertEquals(1,winners);
        }
    }
}
