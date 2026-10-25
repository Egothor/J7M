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
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Three actual JVM groups transfer the same execution resource and its original claim journal.
 */
class ManagedExecutionMigrationIT {
    @TempDir Path directory;
    private static Instant deadline(){return Instant.now().plusSeconds(10);}
    private ManagedEventSubscriptionProcessIT.Child child(ManagedEventSubscriptionProcessIT fixture,String id,String group,long epoch,ServerSocket reservation)throws Exception{
        int port=reservation.getLocalPort();reservation.close();return fixture.new Child(id,"org.j7m.middleware.distributed.ExecutionNodeProcess",id,directory.resolve(id).toString(),id+":"+port,"0",group,Long.toString(epoch));
    }
    private static PartitionClient endpoint(ManagedEventSubscriptionProcessIT.Child child,boolean admin)throws Exception{
        int port=Integer.parseInt(child.command("ADDRESS").substring(4));byte[] key=new byte[32];if(admin)Arrays.fill(key,(byte)1);
        return new PartitionClient(List.of(new InetSocketAddress("127.0.0.1",port)),admin?"admin":"app",key);
    }
    @Test void liveClaimSessionDoesNotFollowMigrationEvenWhenItsOriginalGroupOwnsTheResourceAgain()throws Exception{
        var fixture=new ManagedEventSubscriptionProcessIT();fixture.directory=directory;
        try(var topologyPort=new ServerSocket(0);var leftPort=new ServerSocket(0);var rightPort=new ServerSocket(0);
                var topology=child(fixture,"topology","topology",0,topologyPort);
                var left=child(fixture,"left","left",1,leftPort);
                var right=child(fixture,"right","right",0,rightPort)){
            var authority=new PartitionAdminClient(endpoint(topology,true));var leftAdmin=new PartitionAdminClient(endpoint(left,true));var rightAdmin=new PartitionAdminClient(endpoint(right,true));
            authority.register("execution","left",deadline());
            var backend=ManagedExecutionBackend.partition("execution","tenant",authority,Map.of("left",endpoint(left,false),"right",endpoint(right,false)));
            var original=backend.open(deadline());assertEquals(1,original.epoch());assertEquals("left",original.group());
            original.jobs().submit("submit","run","input",deadline());var claim=original.jobs().claim("claim","run","owner",60000,deadline());long fence=Long.parseLong(claim.fields().get("fence"));
            original.jobs().checkpoint("checkpoint","run","owner",fence,"saved",deadline());
            var migration=new PartitionMigration(authority,Map.of("left",leftAdmin,"right",rightAdmin));migration.migrate("forward","execution","right",1,deadline());
            assertThrows(IOException.class,()->original.jobs().renew("stale-forward","run","owner",fence,60000,deadline()));
            assertEquals("saved",new DurableJobs(backend.client()).status("run",deadline()).fields().get("checkpoint"));
            migration.migrate("back","execution","left",2,deadline());var fresh=backend.open(deadline());assertEquals(3,fresh.epoch());
            assertThrows(IOException.class,()->original.jobs().complete("stale-complete","run","owner",fence,"bad",List.of("protected","0","bad"),deadline()));
            assertEquals("NOT_FOUND",fresh.client().query("E_VALUE","protected",deadline()).status());
            // Positive control: the migrated native lease still exists; only the old route is denied.
            assertEquals("COMPLETED",fresh.jobs().complete("fresh-complete","run","owner",fence,"good",List.of("protected","0","once"),deadline()).status());
            assertEquals("once",fresh.client().query("E_VALUE","protected",deadline()).fields().get("value"));assertThrows(IOException.class,()->original.verify(deadline()));
        }
    }
}
