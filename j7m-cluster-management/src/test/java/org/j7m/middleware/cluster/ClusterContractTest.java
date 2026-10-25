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
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;
class ClusterContractTest {
    @Test void upgradeUsesBoundedStablePlanningIdentityAtMaximumOperationLengthAndPropagatesFailure()throws Exception{
        AtomicInteger effects=new AtomicInteger();AtomicReference<String> planningId=new AtomicReference<>();AtomicBoolean deny=new AtomicBoolean();
        ClusterLifecycle lifecycle=(ClusterLifecycle)Proxy.newProxyInstance(ClusterLifecycle.class.getClassLoader(),new Class<?>[]{ClusterLifecycle.class},(proxy,method,args)->{
            if(method.isDefault())return InvocationHandler.invokeDefault(proxy,method,args);
            if(method.getName().equals("plan")){if(deny.get())throw new IOException("Rejected before effect");planningId.set((String)args[0]);return new DeploymentPlan((String)args[0],0,"v2",List.of("n"),"fingerprint");}
            if(method.getName().equals("apply")){effects.incrementAndGet();return new OperationStatus((String)args[0],"RUNNING",1,(String)args[2],"n","DRAIN","");}
            throw new AssertionError("Unexpected operation");
        });
        String identity="x".repeat(96);assertEquals(identity,lifecycle.upgrade(identity,0,"v2",List.of("n"),Instant.now().plusSeconds(1)).operationId());
        assertTrue(planningId.get().length()<=96);String first=planningId.get();lifecycle.upgrade(identity,0,"v2",List.of("n"),Instant.now().plusSeconds(1));assertEquals(first,planningId.get());
        assertThrows(IllegalArgumentException.class,()->lifecycle.upgrade("x".repeat(97),0,"v2",List.of("n"),Instant.now().plusSeconds(1)));assertEquals(2,effects.get());
        deny.set(true);assertThrows(IOException.class,()->lifecycle.upgrade("failed",0,"v2",List.of("n"),Instant.now().plusSeconds(1)));assertEquals(2,effects.get());
    }
    @Test void compatibilityChecksAdjacentProtocolAndBothSchemaReadBoundaries(){
        var artifact=ClusterAgentTest.catalog().get("v1").artifact();var old=new ReleaseDescriptor("old",artifact,1,1,1,2);
        var next=new ReleaseDescriptor("next",artifact,2,2,1,2);assertTrue(next.compatibleWith(old,1));assertTrue(old.compatibleWith(next,2));
        assertFalse(new ReleaseDescriptor("far",artifact,3,1,1,2).compatibleWith(old,1));
        assertFalse(new ReleaseDescriptor("unreadable",artifact,2,2,2,2).compatibleWith(old,1));
        assertFalse(old.compatibleWith(next,3));
        assertThrows(IllegalArgumentException.class,()->new ReleaseDescriptor("invalid",artifact,0,1,1,1));
    }
    @Test void supervisedReconciliationDoesNothingWhenIdleAndReportsActualBackgroundAuthorityFailures()throws Exception{
        AtomicBoolean fail=new AtomicBoolean();ResourceEndpoint resources=(ResourceEndpoint)Proxy.newProxyInstance(ResourceEndpoint.class.getClassLoader(),new Class<?>[]{ResourceEndpoint.class},(proxy,method,args)->{if(fail.get())throw new IOException("Quorum unavailable");return null;});
        try(var controller=new RollingClusterController(resources,Map.of("n",ClusterCapacityTest.node("n",1,1,false,true)),ClusterAgentTest.catalog())){
            controller.reconcile(Instant.now().plusSeconds(1));assertEquals(0,controller.reconciliationFailures());fail.set(true);
            assertThrows(IOException.class,()->controller.reconcile(Instant.now().plusSeconds(1)));
            long until=System.nanoTime()+Duration.ofSeconds(2).toNanos();while(controller.reconciliationFailures()==0&&System.nanoTime()<until)Thread.sleep(5);
            assertTrue(controller.reconciliationFailures()>0);
        }
    }
}
