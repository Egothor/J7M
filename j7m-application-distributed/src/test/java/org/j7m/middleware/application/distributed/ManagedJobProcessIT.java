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
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Signed JPMS workers recover typed checkpoints across actual JVM loss and durable voter restart.
 */
class ManagedJobProcessIT {
    @TempDir Path directory;
    private record Delivery(Socket socket,String phase,String logical,long fence,String state) implements AutoCloseable {
        void complete() throws IOException {var out=new DataOutputStream(socket.getOutputStream());out.writeBoolean(true);out.flush();}
        public void close() throws IOException {socket.close();}
    }
    private static Delivery receive(ServerSocket sink) throws IOException {
        var socket=sink.accept();socket.setSoTimeout(10000);var in=new DataInputStream(socket.getInputStream());
        try{return new Delivery(socket,in.readUTF(),in.readUTF(),in.readLong(),in.readUTF());}catch(IOException failure){socket.close();throw failure;}
    }
    private ManagedEventSubscriptionProcessIT.Child frontend(ManagedSagaProcessIT.Cluster cluster,GeneratedSignedModule.Artifact artifact,ServerSocket sink,String id,boolean paused,String mode) throws Exception {
        var command=GeneratedSignedModule.hostCommand(directory.resolve(id+"-module"),Path.of("src/test/java/org/j7m/middleware/application/distributed/JobFrontendProcess.java"),"org.j7m.middleware.application.distributed","JobFrontendProcess",Set.of(),
                artifact.bundle().toString(),artifact.publicKey().toString(),directory.resolve(id).toString(),cluster.ports(),Integer.toString(sink.getLocalPort()),directory.resolve("common-job-constructor").toString(),Boolean.toString(paused),mode);
        return cluster.fixture.new Child(id,command);
    }
    private static void stopped(ManagedEventSubscriptionProcessIT.Child child) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        for(;;){String result=child.command("STOP");if(result.equals("STOP true STOPPED"))return;assertEquals("STOP false DRAINING",result);assertTrue(System.nanoTime()<end);java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));}
    }
    @Test void checkpointKillRestoresActualNamedModuleRecordWithHigherFenceAndLostCompleteReplyNeverRepeatsTheBody() throws Exception {
        var artifact=GeneratedJobService.compile(directory.resolve("signed"));
        var fixture=new ManagedSagaProcessIT();fixture.directory=directory;
        try(var cluster=fixture.new Cluster();var sink=new ServerSocket(0)) {
            sink.setSoTimeout(12000);
            long originalFence;
            try(var first=frontend(cluster,artifact,sink,"first",false,"normal");var second=frontend(cluster,artifact,sink,"second",true,"after-complete")) {
                assertTrue(first.command("START primary").startsWith("START "));
                try(var checkpoint=receive(sink)) {
                    assertEquals("checkpoint",checkpoint.phase());assertEquals("primary",checkpoint.logical());assertEquals("checkpointed:input",checkpoint.state());originalFence=checkpoint.fence();
                    first.kill();
                }
                assertEquals("RESUMED",second.command("RESUME"));
                try(var restored=receive(sink)) {
                    assertEquals("restored",restored.phase());assertEquals("checkpointed:input",restored.state());assertTrue(restored.fence()>originalFence,"Native takeover issues a new fence while restoring the persisted actual schema record");restored.complete();
                }
                assertEquals("HOLD COMPLETE",second.read());second.kill();
            }
            try(var restarted=frontend(cluster,artifact,sink,"restarted",false,"normal")) {
                assertTrue(restarted.command("STATUS primary").startsWith("STATUS COMPLETED "));
                assertEquals("RESULT checkpointed:input:done",restarted.command("RESULT primary"));assertEquals("READ checkpointed:input",restarted.command("READ primary"));
                cluster.restart();
                assertEquals("RESULT checkpointed:input:done",restarted.command("RESULT primary"));assertEquals("READ checkpointed:input",restarted.command("READ primary"));
                sink.setSoTimeout(250);assertThrows(SocketTimeoutException.class,()->receive(sink),"A terminal native result cannot replay the application body");
                stopped(restarted);
            }
        }
    }
    @Test void allowAdmitsDistinctRunsOnTwoActualWorkersWhileBothOwnedChildrenRemainHeld() throws Exception {
        var artifact=GeneratedJobService.compile(directory.resolve("signed"));var fixture=new ManagedSagaProcessIT();fixture.directory=directory;
        try(var cluster=fixture.new Cluster();var sink=new ServerSocket(0);var first=frontend(cluster,artifact,sink,"first",false,"parallel");var second=frontend(cluster,artifact,sink,"second",false,"parallel")) {
            sink.setSoTimeout(12000);first.command("START one");first.command("START two");
            try(var a=receive(sink);var b=receive(sink)) {
                assertEquals(Set.of("one","two"),Set.of(a.logical(),b.logical()));assertEquals("checkpoint",a.phase());assertEquals("checkpoint",b.phase());
                assertNotEquals(a.fence(),b.fence());
                assertTrue(first.command("STATUS one").startsWith("STATUS RUNNING "));assertTrue(first.command("STATUS two").startsWith("STATUS RUNNING "));
                a.complete();b.complete();
            }
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            for(String id:List.of("one","two")) {
                while(!first.command("STATUS "+id).startsWith("STATUS COMPLETED ")) {assertTrue(System.nanoTime()<end);java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));}
                assertEquals("RESULT checkpointed:input:done",first.command("RESULT "+id));assertEquals("READ checkpointed:input",first.command("READ "+id));
            }
            stopped(first);stopped(second);
        }
    }
}
