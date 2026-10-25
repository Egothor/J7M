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

package org.j7m.middleware.cluster;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class RollingClusterProcessIT {
    @TempDir Path directory;
    private static Instant deadline(){return Instant.now().plusSeconds(10);}
    @Test void realSignedApplicationRollingUpgradeResumesAfterControllerKillAndPreservesQuorumData()throws Exception{
        try(var cluster=new ClusterProcessFixture(directory,0)){
            ClusterLifecycle admin=cluster.lifecycle();long version=admin.inspect(deadline()).version();
            var plan=admin.plan("roll-plan",version,"v2",cluster.nodeIds(),deadline());
            assertEquals(plan,admin.plan("roll-plan",version,"v2",cluster.nodeIds(),deadline()));
            assertThrows(SecurityException.class,()->cluster.viewer().apply("denied",version,plan.planId(),deadline()));
            assertThrows(IOException.class,()->cluster.lifecycle().plan("bad-protocol",version,"incompatible",cluster.nodeIds(),deadline()));
            assertThrows(IOException.class,()->cluster.lifecycle().rollback("bad-rollback",version,"incompatible",cluster.nodeIds(),deadline()));
            assertTrue(admin.inspect(deadline()).nodes().values().stream().allMatch(n->n.releaseId().equals("v1")&&n.ready()));
            AtomicBoolean running=new AtomicBoolean(true);AtomicInteger successful=new AtomicInteger(),traffic=new AtomicInteger(),drainedCalls=new AtomicInteger();
            try(var workload=Executors.newFixedThreadPool(2)){
                Future<?> requests=workload.submit(()->{int sequence=0;var endpoints=new ArrayList<>(cluster.invocations().values());
                    while(running.get()){String id="live-"+sequence;var endpoint=endpoints.get(sequence++%endpoints.size());
                        try{String result=new String(endpoint.invoke(id,"echo","compatible".getBytes(StandardCharsets.UTF_8),deadline()),StandardCharsets.UTF_8);
                            assertTrue(result.equals("v1:"+id+":compatible")||result.equals("v2:"+id+":compatible"));traffic.incrementAndGet();}
                        catch(IOException drained){drainedCalls.incrementAndGet();}
                    }
                });
                Future<?> writes=workload.submit(()->{int sequence=0;while(running.get()){
                    String id="data-"+sequence++;try{
                        var result=cluster.coordination().execute("BATCH",id,List.of(id,"0","value-"+id),deadline());
                        if(result.status().equals("OK")){assertEquals("value-"+id,cluster.coordination().read(id,deadline()).value());successful.incrementAndGet();}
                    }catch(IOException unavailable){throw new AssertionError("Quorum lost during application rolling upgrade",unavailable);}
                }});
                admin.apply("roll",version,plan.planId(),deadline());cluster.killController();cluster.restartController();
                admin=cluster.lifecycle();long end=System.nanoTime()+Duration.ofSeconds(50).toNanos();
                while(!admin.operationStatus("roll",deadline()).state().equals("COMPLETED")){
                    if(System.nanoTime()>end)fail("Resume timed out: "+admin.operationStatus("roll",deadline()));Thread.sleep(30);
                }
                running.set(false);writes.get(10,TimeUnit.SECONDS);requests.get(10,TimeUnit.SECONDS);
                assertTrue(successful.get()>10,"actual committed/read-back writes="+successful);
                assertTrue(traffic.get()>10,"actual hosted application responses during rolling="+traffic);
                System.out.println("ROLLING_WORKLOAD committed_readbacks="+successful+" hosted_responses="+traffic);
            }
            for(var invocation:cluster.invocations().values())assertEquals("v2:confirmed:payload",new String(invocation.invokeRevision("v2","confirmed","echo","payload".getBytes(StandardCharsets.UTF_8),deadline()),StandardCharsets.UTF_8));
            List<AuditEvent> events=admin.audit("roll",deadline());assertTrue(events.size()>=10);String previous="";long last=0;
            for(var event:events){assertEquals(previous,event.previousHash());assertTrue(event.version()>last);assertEquals(RollingClusterController.hash(previous+"|"+event.version()+"|"+event.operationId()+"|"+event.nodeId()+"|"+event.phase()),event.hash());previous=event.hash();last=event.version();}
            assertEquals("COMPLETED",admin.apply("roll",version,plan.planId(),deadline()).state());
            assertThrows(IOException.class,()->cluster.lifecycle().rollback("stale",version,"v1",cluster.nodeIds(),deadline()));
        }
    }
}
