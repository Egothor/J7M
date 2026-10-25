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

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Signed named application using only public managed job/data contracts.
 */
final class GeneratedJobService {
    static GeneratedSignedModule.Artifact compile(Path root) throws Exception {
        return GeneratedSignedModule.compile(root,Map.of("jobprocess/Saved.java","""
                package jobprocess;
                import org.j7m.middleware.annotations.*;
                @SchemaType(id="jobprocess.Saved",version=1) public record Saved(@WireField(id=7) String value) {}
                ""","jobprocess/Worker.java","""
                package jobprocess;
                import org.j7m.middleware.annotations.*;
                import org.j7m.middleware.application.api.*;
                import org.j7m.middleware.application.distributed.*;
                import java.util.concurrent.*;
                @org.j7m.middleware.annotations.J7mApplication(group="fixture",name="jobs",version="1")
                public class Worker {
                    private final ManagedProvider<ManagedJobContext> context;private final int port;
                    private String state;
                    @Inject public Worker(@J7mResource("job") ManagedProvider<ManagedJobContext> context,
                            @ConfigValue(value="sink.port",required=true) int port,@ConfigValue(value="ctor.marker",required=true) String marker)throws Exception{
                        this.context=context;this.port=port;java.nio.file.Files.writeString(java.nio.file.Path.of(marker),"CTOR");
                    }
                    @Restore public void restore(Saved saved){state=saved==null?"":saved.value();}
                    @Checkpoint public Saved checkpoint(){return new Saved(state);}
                    @Progress public String progress(){return state+"-progress";}
                    @DurableJob("work") public CompletionStage<String> run(String input){
                        return J7mContext.currentInvocation().control().submit(()->{
                            if(!J7mContext.currentInvocation().security().principal().id().equals("worker"))throw new IllegalStateException("Wrong worker");
                            if(!InvocationTraceContext.current().equals(java.util.Map.of("traceparent","00-0123456789abcdef0123456789abcdef-0123456789abcdef-01")))throw new IllegalStateException("Lost durable trace");
                            var job=context.get();if(job.fence()<1||job.partitionEpoch()!=1)throw new IllegalStateException("Wrong claim");
                            if(state.isEmpty()){state="checkpointed:"+input;job.checkpoint();job.progress();exchange("checkpoint",job,state);}
                            else exchange("restored",job,state);
                            job.stageEffect("result."+job.jobId(),0,state);return state+":done";
                        });
                    }
                    private void exchange(String phase,ManagedJobContext job,String state)throws Exception{
                        try(var socket=new java.net.Socket("127.0.0.1",port)){
                            socket.setSoTimeout(10000);var out=new java.io.DataOutputStream(socket.getOutputStream());out.writeUTF(phase);out.writeUTF(job.jobId());out.writeLong(job.fence());out.writeUTF(state);out.flush();
                            if(!new java.io.DataInputStream(socket.getInputStream()).readBoolean())throw new IllegalStateException("Fixture action rejected");
                        }
                    }
                }
                """),"fixture:jobs:1","jobprocess.Worker",Set.of("org.j7m.application.distributed"),Set.of());
    }
}
