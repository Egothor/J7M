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
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.j7m.middleware.consensus.NetworkConsensusStatus;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class ClusterAgentTest {
    @TempDir Path directory;
    static ResourceEndpoint authority(){return (ResourceEndpoint)Proxy.newProxyInstance(ResourceEndpoint.class.getClassLoader(),new Class<?>[]{ResourceEndpoint.class},(proxy,method,args)->{
        if(method.getName().equals("lease"))return new ReplicatedResources.Lease("owner",1,Long.MAX_VALUE,1,1);
        return null;
    });}
    static HostingSupervisor host(){return new HostingSupervisor(){
        @Override public NodeView inspect(){return new NodeView("n","v1",true,false,0,1);}
        @Override public void drain(Instant deadline){}
        @Override public void replace(ReleaseDescriptor release,Instant deadline){}
        @Override public byte[] invoke(String id,String contract,byte[] payload,Instant deadline){return payload;}
    };}
    static Map<String,ReleaseDescriptor> catalog(){var manifest=new org.j7m.middleware.application.repository.ArtifactManifest("tenant","app:echo:1",1,"0".repeat(64),4096,List.of("0".repeat(64)),"signer",new byte[64]);return Map.of("v1",new ReleaseDescriptor("v1",manifest,1,1,1,1));}
    static ClusterNodeAgent agent(Path journal)throws IOException{return new ClusterNodeAgent(host(),authority(),()->new NetworkConsensusStatus("n",1,"n",1,1,1,Set.of("n"),Set.of()),catalog(),journal,1);}
    @Test void closedAgentCannotAuthorizeAnEffectAfterOwnershipTransfers()throws Exception{
        Path path=directory.resolve("agent");ClusterNodeAgent first=agent(path);first.close();
        try(var second=agent(path)){assertThrows(IOException.class,()->first.step("operation","DRAIN","v1","owner",1,1,Instant.now().plusSeconds(1)));}
    }
    @Test void malformedRecoveryReleasesOwnershipAndDuplicateOpenDoesNotReleaseLiveOwner()throws Exception{
        Path path=directory.resolve("agent");Files.write(path,new byte[]{1});assertThrows(IOException.class,()->agent(path));Files.delete(path);
        try(var first=agent(path)){assertThrows(IOException.class,()->agent(path));assertThrows(IOException.class,()->agent(path));}
        try(var next=agent(path)){assertNotNull(next.status(Instant.now().plusSeconds(1)));}
    }
    @Test void duplicateJvmOpenCannotReleaseOriginalOwnersCrossProcessLock()throws Exception{
        Path path=directory.resolve("agent");
        try(var first=agent(path)){
            assertThrows(IOException.class,()->agent(path));
            assertEquals(1,ownershipProbe(path));
            assertNotNull(first.status(Instant.now().plusSeconds(1)));
        }
        assertEquals(0,ownershipProbe(path));
    }
    private int ownershipProbe(Path path)throws Exception{
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
                System.getProperty("j7m.cluster.test.classpath"),ClusterOwnershipProcess.class.getName(),path.toString())
                .redirectError(directory.resolve("ownership.err").toFile()).redirectOutput(directory.resolve("ownership.out").toFile()).start();
        assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));return process.exitValue();
    }
    @Test void corruptedDurableEffectJournalFailsClosedAndReleasesOwnership()throws Exception{
        Path path=directory.resolve("agent");try(var node=agent(path)){node.step("effect","DRAIN","v1","owner",1,1,Instant.now().plusSeconds(1));}
        byte[] record=Files.readAllBytes(path);record[record.length-1]^=1;Files.write(path,record);
        assertThrows(IOException.class,()->agent(path));assertThrows(IOException.class,()->agent(path));
    }
    @Test void oversizedPhysicalOperationIdentityIsRejectedBeforeJournalPublication()throws Exception{
        Path path=directory.resolve("agent");try(var node=agent(path)){
            assertThrows(IOException.class,()->node.step("x".repeat(97),"DRAIN","v1","owner",1,1,Instant.now().plusSeconds(1)));
            assertFalse(Files.exists(path));
        }
    }
}
