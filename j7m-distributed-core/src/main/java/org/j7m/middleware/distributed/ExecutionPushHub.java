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

package org.j7m.middleware.distributed;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.consensus.NetworkConsensusNode;

/**
 * Bounded native execution claim dispatcher; commits only signal and never perform socket I/O.
 */
final class ExecutionPushHub implements AutoCloseable {
    private static final System.Logger LOG=System.getLogger(ExecutionPushHub.class.getName());
    private final NetworkConsensusNode node;
    private final PartitionStateMachine partition;
    private final ExecutionStateMachine state;
    private final ReplicatedResourceClient.ClockHealth clock;
    private final ConcurrentHashMap<String,Session> sessions=new ConcurrentHashMap<>();
    private final AtomicInteger reservedBytes=new AtomicInteger();
    private final Semaphore wake=new Semaphore(0);
    private final Runnable change=this::signal;
    private final Thread dispatcher;
    private volatile boolean closed;
    ExecutionPushHub(NetworkConsensusNode node,PartitionStateMachine partition,ExecutionStateMachine state,
            ReplicatedResourceClient.ClockHealth clock) {
        this.node=node;this.partition=partition;this.state=state;this.clock=clock;
        partition.addChangeListener(change);dispatcher=worker("execution-push-dispatch",this::run);dispatcher.start();
    }
    private static Thread worker(String name,Runnable action) {
        Thread thread=Thread.ofVirtual().name(name).inheritInheritableThreadLocals(false).unstarted(action);
        thread.setContextClassLoader(ExecutionPushHub.class.getClassLoader());return thread;
    }
    private void signal(){if(wake.availablePermits()==0)wake.release();}
    void open(Socket socket,String nonce,PartitionServer.Binding binding,byte[] payload,Instant deadline)throws IOException {
        var in=PartitionStateMachine.input(payload);long epoch=in.readLong();String prefix=in.readUTF(),kind=in.readUTF(),owner=in.readUTF();long lease=in.readLong();PartitionStateMachine.end(in);
        if(prefix.length()>128||prefix.indexOf('\0')>=0||!Set.of("JOB","SAGA").contains(kind))throw new IOException("Execution session identity");
        ExecutionStateMachine.validate(new ResourceCommand(kind.equals("JOB")?"J_CLAIM":"S_CLAIM",binding.scope(),"validation",List.of("validation",owner,Long.toString(lease))));
        node.readIndex(deadline);var status=partition.status();
        if(closed||!Set.of("ACTIVE","COPYING").contains(status.phase())||status.epoch()!=epoch)throw new IOException("Execution ownership unavailable");
        String identity=binding.scope()+'\0'+kind+'\0'+prefix+'\0'+owner;
        Session session=new Session(identity,socket,nonce,binding,epoch,prefix,kind,owner,lease);
        synchronized(sessions){if(closed||sessions.size()>=64||sessions.putIfAbsent(identity,session)!=null)throw new IOException("Execution session capacity/duplicate owner");}
        try {
            ResourceWire.send(session.output,ResourceWire.encode(out->{out.writeUTF(nonce);out.writeInt(0);PartitionStateMachine.writeBytes(out,new byte[0]);}),binding.key());
            socket.setSoTimeout(0);session.reader.start();session.writer.start();signal();
        }catch(IOException|RuntimeException failure){session.close();throw failure;}
    }
    private void run(){
        try{
            long wait=Long.MAX_VALUE;
            while(!closed){
                if(wait==Long.MAX_VALUE)wake.acquire();else wake.tryAcquire(Math.max(1,wait),TimeUnit.MILLISECONDS);
                wait=Long.MAX_VALUE;if(closed)break;
                for(Session session:sessions.values()){
                    if(session.closed)continue;var status=partition.status();
                    if(status.epoch()!=session.epoch||!Set.of("ACTIVE","COPYING").contains(status.phase())){session.close();continue;}
                    if(!session.hasCredit())continue;
                    long now;try{now=measuredNow();}catch(IOException unhealthy){session.close();continue;}
                    try{deliver(session,now);}catch(IOException|RuntimeException unavailable){session.close();}
                    long expiry=state.pushNextExpiry(session.binding.scope(),session.prefix,session.kind,now);
                    if(expiry>now&&expiry!=Long.MAX_VALUE)wait=Math.min(wait,expiry-now);
                }
            }
        }catch(InterruptedException stopped){Thread.currentThread().interrupt();}
    }
    private long measuredNow()throws IOException {
        var sample=clock.sample();if(!sample.monotonicStable()||sample.uncertainty()<0||sample.uncertainty()>500||Math.abs((double)sample.time()-sample.reference())>500)throw new IOException("Unhealthy execution clock");return sample.time();
    }
    private void deliver(Session session,long now)throws IOException{
        session.prune(now);
        var candidate=state.pushCandidate(session.binding.scope(),session.prefix,session.kind,session.owner,now,session.tokens());
        if(candidate==null||!session.reserve())return;
        boolean queued=false;
        try{
            Instant deadline=Instant.now().plusSeconds(5);
            var endpoint=new LocalPartitionEndpoint(node,partition,session.binding.scope(),session.binding.maintenance(),clock);
            var client=new ExecutionClient(partition.status().partition(),session.binding.scope(),(p,d)->new ReplicatedQueue.Route(endpoint,session.epoch));
            ExecutionResult claim;
            if(candidate.existing())claim=client.query("E_RUN",candidate.id(),deadline);
            else claim=client.execute(session.kind.equals("JOB")?"J_CLAIM":"S_CLAIM",UUID.randomUUID().toString(),List.of(candidate.id(),session.owner,Long.toString(session.lease)),deadline);
            if(!Set.of("RUNNING","UNKNOWN","COMPENSATING","COMPLETING").contains(claim.status()))return;
            long fence=Long.parseLong(claim.fields().get("fence"));
            if(!session.owner.equals(claim.fields().get("owner"))||!state.pushClaimActive(session.binding.scope(),candidate.id(),session.owner,fence,measuredNow()))return;
            queued=session.offer(new ExecutionDelivery(candidate.id(),session.kind,session.epoch,claim));
        }finally{if(!queued)session.release(true);}
    }
    @Override public void close(){closed=true;partition.removeChangeListener(change);signal();dispatcher.interrupt();for(Session session:sessions.values())session.close();}
    private final class Session implements AutoCloseable {
        final String identity,nonce,prefix,kind,owner;final long epoch,lease;
        final Socket socket;final PartitionServer.Binding binding;
        final DataInputStream input;final DataOutputStream output;
        final ArrayBlockingQueue<ExecutionDelivery> backlog=new ArrayBlockingQueue<>(4);
        record Token(long fence,long version) { }
        final Map<String,Token> sent=new java.util.HashMap<>();
        final Thread reader,writer;int credits,reserved;volatile boolean closed;
        Session(String identity,Socket socket,String nonce,PartitionServer.Binding binding,long epoch,String prefix,String kind,String owner,long lease)throws IOException{
            this.identity=identity;this.socket=socket;this.nonce=nonce;this.binding=binding;this.epoch=epoch;this.prefix=prefix;this.kind=kind;this.owner=owner;this.lease=lease;
            input=new DataInputStream(socket.getInputStream());output=new DataOutputStream(socket.getOutputStream());reader=worker("execution-push-credit",this::read);writer=worker("execution-push-write",this::write);
        }
        synchronized boolean hasCredit(){return !closed&&credits>0;}
        synchronized Set<String> tokens(){return sent.entrySet().stream().map(entry->entry.getKey()+'\0'+entry.getValue().fence()+'\0'+entry.getValue().version()).collect(java.util.stream.Collectors.toUnmodifiableSet());}
        synchronized void prune(long now){sent.entrySet().removeIf(entry->!state.pushClaimActive(binding.scope(),entry.getKey(),owner,entry.getValue().fence(),now));}
        synchronized boolean reserve(){
            if(closed||credits<=0)return false;
            for(;;){int current=reservedBytes.get();if(current>16*1024*1024-ExecutionDelivery.MAX_FRAME)return false;if(reservedBytes.compareAndSet(current,current+ExecutionDelivery.MAX_FRAME))break;}
            credits--;reserved++;return true;
        }
        synchronized void release(boolean restore){reserved--;reservedBytes.addAndGet(-ExecutionDelivery.MAX_FRAME);if(restore&&!closed)credits++;notifyAll();if(!restore)signal();}
        synchronized boolean offer(ExecutionDelivery delivery){if(closed||!sent.containsKey(delivery.run())&&sent.size()>=4||!backlog.offer(delivery))return false;sent.put(delivery.run(),new Token(Long.parseLong(delivery.claim().fields().get("fence")),Long.parseLong(delivery.claim().fields().get("version"))));return true;}
        private void read(){
            try{long sequence=1;while(!closed){var in=PartitionStateMachine.input(QueueSessionWire.receive(input,binding.key(),nonce,sequence++,ExecutionDelivery.MAX_FRAME));if(!in.readUTF().equals("CREDIT"))throw new IOException("Execution session operation");int n=in.readInt();PartitionStateMachine.end(in);
                synchronized(this){if(n<1||n>4)throw new IOException("Execution credit bounds");while(!closed&&credits+reserved+n>4&&reserved>0)wait();if(closed)return;if(credits+reserved+n>4)throw new IOException("Execution credit bounds");credits+=n;}signal();}}
            catch(InterruptedException stopped){Thread.currentThread().interrupt();close();}catch(IOException|RuntimeException failure){close();}
        }
        private void write(){
            try{long sequence=1;while(!closed){ExecutionDelivery delivery=backlog.take();try{
                if(partition.status().epoch()!=epoch||!Set.of("ACTIVE","COPYING").contains(partition.status().phase())||!state.pushClaimActive(binding.scope(),delivery.run(),owner,Long.parseLong(delivery.claim().fields().get("fence")),measuredNow()))throw new IOException("Execution claim expired before delivery");
                QueueSessionWire.send(output,binding.key(),nonce,sequence++,ExecutionDelivery.MAX_FRAME,out->{out.writeUTF("DELIVERY");ExecutionDelivery.write(out,delivery);});
            }finally{release(false);}}}catch(InterruptedException stopped){Thread.currentThread().interrupt();close();}catch(IOException|RuntimeException failure){close();}
        }
        @Override public void close(){synchronized(this){if(closed)return;closed=true;notifyAll();while(backlog.poll()!=null)release(false);sent.clear();}sessions.remove(identity,this);reader.interrupt();writer.interrupt();try{socket.close();}catch(IOException terminal){LOG.log(System.Logger.Level.DEBUG,"Terminal execution push socket close failed ({0})",terminal.getClass().getName());}signal();}
    }
}
