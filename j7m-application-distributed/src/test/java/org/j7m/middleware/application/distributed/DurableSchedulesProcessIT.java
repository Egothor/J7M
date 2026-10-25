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
import java.nio.file.*;
import java.time.*;
import org.j7m.middleware.distributed.ExecutionEvents;
import org.j7m.middleware.distributed.ExecutionClient;
import org.j7m.middleware.distributed.PartitionClient;
import org.j7m.middleware.distributed.ReplicatedQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two independent scheduler JVMs bridge real three-voter metadata and execution stores.
 */
class DurableSchedulesProcessIT {
    @TempDir Path directory;
    @Test void crashesAfterPendingAndAfterSubmitRecoverTheSameOccurrenceAcrossFullStoreRestart() throws Exception {
        for(String phase:new String[]{"pending","submit"}) {
            var metadataFixture=new ManagedEventSubscriptionProcessIT();metadataFixture.directory=Files.createDirectories(directory.resolve(phase+"-metadata"));
            var executionFixture=new ManagedSagaProcessIT();executionFixture.directory=Files.createDirectories(directory.resolve(phase+"-execution"));
            try(var metadata=metadataFixture.new Backends(false);var execution=executionFixture.new Cluster()) {
                long start=Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
                try(var first=metadataFixture.new Child("scheduler-one",ScheduleFrontendProcess.class.getName(),metadata.ports(),execution.ports(),Long.toString(start),Long.toString(start),phase);
                        var second=metadataFixture.new Child("scheduler-two",ScheduleFrontendProcess.class.getName(),metadata.ports(),execution.ports(),Long.toString(start),Long.toString(start+100000),"none")) {
                    assertEquals("HOLD "+(phase.equals("pending")?"PENDING":"SUBMITTED"),first.command("TICK"));first.kill();
                    assertTrue(second.command("TICK").startsWith("ACK SUBMITTED "),"A confirmed pending occurrence survives even SKIP misfire recovery");
                    var endpoint=new PartitionClient(execution.ports.stream().map(port->new java.net.InetSocketAddress("127.0.0.1",port)).toList(),"app",new byte[32]);
                    var events=new ExecutionEvents(new ExecutionClient("execution","tenant",(partition,deadline)->new ReplicatedQueue.Route(endpoint,1)));
                    var before=events.snapshotAndTail(Instant.now().plusSeconds(3));assertEquals(1,before.runs().size());
                    metadata.restart();execution.restart();
                    assertEquals(before.runs(),events.snapshotAndTail(Instant.now().plusSeconds(3)).runs());
                    assertTrue(second.command("TICK").startsWith("ACK SKIPPED "));
                    assertEquals(1,events.snapshotAndTail(Instant.now().plusSeconds(3)).runs().size());
                }
            }
        }
    }
}
