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
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;
class ClusterCapacityTest {
    static Instant deadline(){return Instant.now().plusSeconds(2);}
    static ClusterNodeEndpoint node(String id,int members,long epoch,boolean joint,boolean available){return new ClusterNodeEndpoint(){
        @Override public Status status(Instant deadline){return new Status(new NodeView(id,"v1",true,false,0,1),1,members,epoch,joint,available);}
        @Override public NodeView step(String operation,String phase,String release,String owner,long fence,long epoch,Instant deadline){throw new AssertionError("No physical effect allowed");}
        @Override public byte[] invoke(String request,String contract,byte[] payload,Instant deadline){throw new AssertionError("No invocation allowed");}
    };}
    static ResourceEndpoint resources(){return (ResourceEndpoint)Proxy.newProxyInstance(ResourceEndpoint.class.getClassLoader(),new Class<?>[]{ResourceEndpoint.class},(proxy,method,args)->{
        if(method.getName().equals("execute"))throw new AssertionError("Unsafe plan reached durable admission");return null;
    });}
    @Test void observedJointConfigurationOnAnotherNodeRejectsBeforePlanAdmission()throws Exception{
        try(var controller=new RollingClusterController(resources(),Map.of("n1",node("n1",3,1,false,true),"n2",node("n2",3,1,true,true),"n3",node("n3",3,1,false,true)),ClusterAgentTest.catalog())){
            assertThrows(IOException.class,()->controller.plan("joint",0,"v1",List.of("n1"),deadline()));
        }
    }
    @Test void mixedConfigurationEpochsAndInsufficientCapacityRejectBeforeAdmission()throws Exception{
        try(var controller=new RollingClusterController(resources(),Map.of("n1",node("n1",3,1,false,true),"n2",node("n2",3,2,false,true),"n3",node("n3",3,1,false,true)),ClusterAgentTest.catalog())){
            assertThrows(IOException.class,()->controller.plan("epoch",0,"v1",List.of("n1"),deadline()));
        }
        try(var controller=new RollingClusterController(resources(),Map.of("n1",node("n1",3,1,false,true),"n2",node("n2",3,1,false,true)),ClusterAgentTest.catalog())){
            assertThrows(IOException.class,()->controller.plan("capacity",0,"v1",List.of("n1"),deadline()));
        }
    }
}
