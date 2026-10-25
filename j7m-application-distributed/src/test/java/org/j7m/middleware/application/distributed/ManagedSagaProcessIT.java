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
import java.util.function.BooleanSupplier;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Signed generated applications, real three-voter durability and external-outcome reconciliation.
 */
class ManagedSagaProcessIT {
    @TempDir Path directory;
    private static int port() throws IOException { try(var socket=new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static Instant deadline() { return Instant.now().plusSeconds(4); }
    private static String nativeId(String id) { return "saga."+ManagedSagaProgram.compactHash("commerce","orders","1")+"."+ManagedSagaProgram.compactHash(id); }
    private static void until(BooleanSupplier condition) {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(22);
        while(!condition.getAsBoolean()) { assertTrue(System.nanoTime()<end,"Actual saga recovery deadline");java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10)); }
    }
    final class Cluster implements AutoCloseable {
        final ManagedEventSubscriptionProcessIT fixture=new ManagedEventSubscriptionProcessIT();
        final List<ManagedEventSubscriptionProcessIT.Child> nodes=new ArrayList<>();
        final List<Integer> ports=new ArrayList<>();
        final DurableSagas sagas;
        Cluster() throws Exception {
            fixture.directory=directory;var peers=new TreeMap<String,Integer>();
            for(String id:List.of("one","two","three")) {peers.put(id,port());ports.add(port());}
            String roster=String.join(",",peers.entrySet().stream().map(entry->entry.getKey()+":"+entry.getValue()).toList());
            try {
                int index=0;for(String id:peers.keySet()) nodes.add(fixture.new Child(id,"org.j7m.middleware.distributed.ExecutionNodeProcess",id,directory.resolve(id).toString(),roster,ports.get(index++).toString()));
                var endpoint=new PartitionClient(ports.stream().map(value->new InetSocketAddress("127.0.0.1",value)).toList(),"app",new byte[32]);
                sagas=new DurableSagas(new ExecutionClient("execution","tenant",(partition,end)->new ReplicatedQueue.Route(endpoint,1)));
                ready();
            } catch(Exception|AssertionError failure) {close();throw failure;}
        }
        void ready() {until(()->{try{return sagas.status(nativeId("readiness"),deadline()).status().equals("NOT_FOUND");}catch(IOException unavailable){return false;}});}
        String ports() {return String.join(",",ports.stream().map(Object::toString).toList());}
        ManagedEventSubscriptionProcessIT.Child frontend(String id,Path bundle,Sink sink,boolean paused) throws Exception {
            return frontend(id,bundle,sink,paused,false);
        }
        ManagedEventSubscriptionProcessIT.Child frontend(String id,Path bundle,Sink sink,boolean paused,boolean modular) throws Exception {
            String[] args={bundle.toString(),bundle.getParent().resolve("operator.der").toString(),directory.resolve(id).toString(),ports(),Integer.toString(sink.socket.getLocalPort()),directory.resolve("common-constructed").toString(),Boolean.toString(paused)};
            if(!modular)return fixture.new Child(id,SagaFrontendProcess.class.getName(),args);
            var command=GeneratedSignedModule.hostCommand(directory.resolve(id+"-module"),Path.of("src/test/java/org/j7m/middleware/application/distributed/SagaFrontendProcess.java"),"org.j7m.middleware.application.distributed","SagaFrontendProcess",Set.of(),args);
            return fixture.new Child(id,command);
        }
        void terminal(String id,String expected) {until(()->{try{return sagas.status(nativeId(id),deadline()).status().equals(expected);}catch(IOException unavailable){return false;}});}
        void restart() throws Exception {for(var node:nodes)assertTrue(node.command("SNAPSHOT").startsWith("ACK"));for(var node:nodes)node.kill();for(var node:nodes)node.start();ready();}
        public void close() {nodes.forEach(ManagedEventSubscriptionProcessIT.Child::close);}
    }
    private static final class Sink implements AutoCloseable {
        final ServerSocket socket=new ServerSocket(0);
        final ExecutorService workers=Executors.newFixedThreadPool(4);
        final Map<String,String> results=new ConcurrentHashMap<>();
        final Map<String,Integer> calls=new ConcurrentHashMap<>();
        final List<String> order=new CopyOnWriteArrayList<>();
        final String hold;
        final CountDownLatch applied=new CountDownLatch(1),release=new CountDownLatch(1);
        final Thread acceptor;
        volatile boolean closed;
        Sink(String hold) throws IOException {
            this.hold=hold;acceptor=Thread.ofPlatform().name("saga-external-fixture").daemon().start(()->{
                while(!closed)try {var accepted=socket.accept();workers.execute(()->serve(accepted));}catch(IOException failure){if(!closed)throw new UncheckedIOException(failure);}
            });
        }
        void serve(Socket accepted) {
            try(accepted) {
                accepted.setSoTimeout(15000);var in=new DataInputStream(accepted.getInputStream());String kind=in.readUTF(),key=in.readUTF(),input=in.readUTF();String result;
                if(kind.equals("query")) result=results.get(key);
                else {
                    calls.merge(kind,1,Integer::sum);order.add(kind);
                    result=results.computeIfAbsent(key,ignored->kind.equals("forward")?input+"-done":"undone");
                    if(kind.equals(hold)) {applied.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));}
                }
                var out=new DataOutputStream(accepted.getOutputStream());out.writeBoolean(result!=null);if(result!=null)out.writeUTF(result);out.flush();
            } catch(IOException expectedKilledPeer) { /* The originating JVM is deliberately killed after its external effect. */ }
            catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
        }
        public void close() throws Exception {closed=true;release.countDown();socket.close();acceptor.join(1000);workers.shutdownNow();assertTrue(workers.awaitTermination(3,TimeUnit.SECONDS));}
    }
    @Test void lostForwardOutcomeIsReconciledAcrossApplicationKillAndFullVoterRestart() throws Exception { recover("forward"); }
    @Test void lostNonIdempotentCompensationOutcomeIsReconciledWithoutRepeatingTheInverse() throws Exception { recover("undo-last"); }
    @Test void signedNamedModuleUsesGeneralSagaAdaptersWithoutOpeningItsImplementationPackage() throws Exception {recover("forward",true);}
    private void recover(String held) throws Exception {recover(held,false);}
    private void recover(String held,boolean modular) throws Exception {
        Path bundle=GeneratedSagaService.compileBundle(directory.resolve("generated"));String id="order/a:1";
        if(modular) {
            String source=Files.readString(bundle.getParent().resolve("Flow.java"));
            bundle=GeneratedSignedModule.compile(directory.resolve("named"),Map.of("sagaprocess/Flow.java",source),"fixture:sagas:1","sagaprocess.Flow",Set.of("org.j7m.application.distributed"),Set.of()).bundle();
        }
        try(var cluster=new Cluster();var sink=new Sink(held);var first=cluster.frontend("first",bundle,sink,false,modular);var second=cluster.frontend("second",bundle,sink,true,modular)) {
            assertEquals(5,java.util.stream.Stream.concat(cluster.nodes.stream().map(node->node.process.pid()),java.util.stream.Stream.of(first.process.pid(),second.process.pid())).distinct().count());
            assertTrue(first.command("START "+id).startsWith("START "));
            if(held.equals("undo-last")) {cluster.terminal(id,"COMPLETED");assertTrue(first.command("COMPENSATE "+id).startsWith("DECISION "));}
            assertTrue(sink.applied.await(8,TimeUnit.SECONDS),"Actual external action must precede JVM loss");
            first.kill();sink.release.countDown();assertEquals("RESUMED",second.command("RESUME"));
            cluster.terminal(id,held.equals("forward")?"COMPLETED":"COMPENSATED");
            assertEquals(1,sink.calls.get("forward"));
            if(held.equals("undo-last")) {assertEquals(1,sink.calls.get("undo-last"));assertEquals(List.of("forward","undo-last","undo-first"),sink.order);}
            if(held.equals("forward")) assertEquals("RESULT input-first-done",second.command("RESULT "+id));
            cluster.restart();cluster.terminal(id,held.equals("forward")?"COMPLETED":"COMPENSATED");
            if(held.equals("forward")) assertEquals("RESULT input-first-done",second.command("RESULT "+id));
            String stopped=second.command("STOP");assertEquals("STOP true STOPPED",stopped);
        }
    }
}
