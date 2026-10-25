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

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.j7m.middleware.distributed.*;

/**
 * Faithful native execution state machine with controlled time and a lost post-commit reply.
 */
final class SagaEndpoint implements PartitionEndpoint {
    final ExecutionStateMachine machine=new ExecutionStateMachine("execution");
    final AtomicBoolean loseCompletion=new AtomicBoolean();
    final AtomicBoolean expireVerification=new AtomicBoolean();
    long index;
    volatile long time;
    SagaEndpoint() { advance(System.currentTimeMillis()); }
    synchronized void advance(long now) { time=now;machine.apply(++index,machine.timeCommand(now)); }
    @Override public synchronized PartitionReply execute(long epoch,String request,byte[] command,byte[] query,Instant deadline) throws IOException {
        if(ResourceCommand.decode(command).operation().equals("S_VERIFY")&&expireVerification.compareAndSet(true,false)) advance(time+86400001);
        machine.validate(command,"tenant",true);machine.apply(++index,command);
        if(ResourceCommand.decode(command).operation().equals("S_STEP_COMPLETE")&&loseCompletion.compareAndSet(true,false)) throw new IOException("Controlled committed reply loss");
        return new PartitionReply("OK",1,machine.query(query));
    }
    @Override public synchronized PartitionReply query(long epoch,byte[] query,Instant deadline) {machine.validateQuery(query,"tenant");return new PartitionReply("OK",1,machine.query(query));}
    @Override public PartitionStateMachine.Status status(Instant deadline) {return new PartitionStateMachine.Status("execution","group",1,"ACTIVE","","",index);}
    ExecutionClient client() {return new ExecutionClient("execution","tenant",(partition,deadline)->new ReplicatedQueue.Route(this,1));}
    /**
     * Test-only clock-controlled native claim publisher; production uses authenticated TCP push.
     */
    ManagedSagaFactory.Connector connector() {
        return (session,prefix,owner,lease,deadline)->new ManagedSagaFactory.Source() {
            final java.util.concurrent.ScheduledExecutorService timer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            final Set<String> sent=new HashSet<>();
            java.util.concurrent.Flow.Subscriber<? super ExecutionDelivery> subscriber;
            int credits;
            boolean closed;
            public synchronized void subscribe(java.util.concurrent.Flow.Subscriber<? super ExecutionDelivery> value) {
                subscriber=value;
                value.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                    public void request(long n) { synchronized(SagaEndpoint.this) { credits+=Math.toIntExact(n); } }
                    public void cancel() { close(); }
                });
                timer.scheduleWithFixedDelay(this::deliver,0,5,java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            void deliver() {
                try {
                    synchronized(SagaEndpoint.this) {
                        if(closed||credits==0) return;
                        for(var entry:new ExecutionEvents(client()).runs(prefix,Instant.now().plusSeconds(1))) {
                            var state=entry.getValue();if(Set.of("COMPLETED","COMPENSATED","CANCELLED").contains(state.status())) continue;
                            if(!state.fields().getOrDefault("owner","").equals(owner)&&Long.parseLong(state.fields().getOrDefault("expires","0"))>time) continue;
                            String token=entry.getKey()+":"+state.fields().get("fence");if(sent.contains(token)) continue;
                            var claim=new DurableSagas(client()).claim(UUID.randomUUID().toString(),entry.getKey(),owner,lease,Instant.now().plusSeconds(1));
                            if(!Set.of("RUNNING","COMPLETING","COMPENSATING","UNKNOWN").contains(claim.status())) continue;
                            sent.add(entry.getKey()+":"+claim.fields().get("fence"));credits--;
                            subscriber.onNext(new ExecutionDelivery(entry.getKey(),"SAGA",1,claim));return;
                        }
                    }
                } catch(Exception failure) { subscriber.onError(failure);close(); }
            }
            public void close() { synchronized(SagaEndpoint.this) { closed=true; }timer.shutdownNow(); }
        };
    }
}
