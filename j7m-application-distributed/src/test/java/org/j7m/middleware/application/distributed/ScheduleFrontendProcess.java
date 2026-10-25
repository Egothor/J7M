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

import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import org.j7m.middleware.distributed.*;

/**
 * Independent durable trigger qualification host with synthetic loopback credentials.
 */
public final class ScheduleFrontendProcess {
    private ScheduleFrontendProcess() { }
    private static void hold(String phase) throws InterruptedException {
        System.out.println("HOLD "+phase);System.out.flush();new CountDownLatch(1).await();
    }
    /**
     * Runs one real native metadata/execution trigger, permitting controlled crash-window cuts.
     * @param args metadata ports, execution ports, schedule start millis, trusted test reference millis, hold phase
     * @throws Exception on fixture setup, native I/O or control failure
     */
    public static void main(String[] args) throws Exception {
        var metadataAddresses=Arrays.stream(args[0].split(",")).map(value->new InetSocketAddress("127.0.0.1",Integer.parseInt(value))).toList();
        var metadata=new RemoteResourceClient(metadataAddresses,"app",new byte[32],null);
        var executionAddresses=Arrays.stream(args[1].split(",")).map(value->new InetSocketAddress("127.0.0.1",Integer.parseInt(value))).toList();
        var endpoint=new PartitionClient(executionAddresses,"app",new byte[32]);
        var jobs=new DurableJobs(new ExecutionClient("execution","tenant",(partition,deadline)->new ReplicatedQueue.Route(endpoint,1)));
        ResourceEndpoint watched=new ResourceEndpoint() {
            public ReplicatedResources.Result execute(String op,String request,List<String> values,Instant deadline) throws IOException {
                var result=metadata.execute(op,request,values,deadline);
                if(args[4].equals("pending")&&op.equals("BATCH")&&values.getLast().split("\\|",-1).length==4&&!values.getLast().split("\\|",-1)[2].isEmpty()) {
                    try{hold("PENDING");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}
                }
                return result;
            }
            public ReplicatedResources.Value read(String key,Instant deadline) throws IOException {return metadata.read(key,deadline);}
            public ReplicatedResources.Result transaction(String tx,Instant deadline) throws IOException {return metadata.transaction(tx,deadline);}
            public List<String> prepared(Instant deadline) throws IOException {return metadata.prepared(deadline);}
            public List<String> participants(String tx,Instant deadline) throws IOException {return metadata.participants(tx,deadline);}
        };
        long reference=Long.parseLong(args[3]);
        var profile=new ManagedScheduleProfile(watched,()->new ReplicatedResourceClient.ClockMeasurement(reference,reference,0,true),ManagedScheduleProfile.Pattern.FIXED_RATE,
                Instant.ofEpochMilli(Long.parseLong(args[2])),Duration.ofSeconds(10),null,ZoneOffset.UTC,ManagedScheduleProfile.Gap.SKIP,ManagedScheduleProfile.Fold.EARLIER,
                ManagedScheduleProfile.Misfire.SKIP,Duration.ofSeconds(1),1,ManagedScheduleProfile.Overlap.FORBID,"input");
        try(var engine=new DurableSchedules(profile,"tenant/orders/1","a".repeat(64),true,(id,input,deadline)->{
            var result=jobs.submit("submit."+OwnershipData.hash(id),id,(String)input,deadline);
            if(args[4].equals("submit"))try{hold("SUBMITTED");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}
            return result;
        });var input=new BufferedReader(new InputStreamReader(System.in))) {
            engine.prepare(Instant.now().plusSeconds(5));System.out.println("READY");System.out.flush();String line;
            while((line=input.readLine())!=null) {
                if(line.equals("STOP"))return;
                if(!line.equals("TICK"))throw new IllegalArgumentException("Unknown schedule fixture command");
                var result=engine.tick(Instant.now().plusSeconds(5));System.out.println("ACK "+result.status()+" "+result.nextDue());System.out.flush();
            }
        }
    }
}
