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

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.j7m.middleware.consensus.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueueSessionTest {
    @TempDir Path directory;
    private static Instant deadline(){return Instant.now().plusSeconds(5);}
    private static final class Receiver implements Flow.Subscriber<QueueDelivery> {
        final BlockingQueue<Object> signals=new ArrayBlockingQueue<>(8);Flow.Subscription subscription;
        public void onSubscribe(Flow.Subscription value){subscription=value;}
        public void onNext(QueueDelivery value){assertTrue(signals.offer(value));}
        public void onError(Throwable failure){assertTrue(signals.offer(failure));}
        public void onComplete(){}
        Object take()throws InterruptedException{var value=signals.poll(3,TimeUnit.SECONDS);assertNotNull(value);return value;}
    }
    @Test void demandIsBoundedDuplicatePhysicalMembersRejectAndInvalidWireCreditNeverClaims()throws Exception{
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var peers=Map.of("a",new InetSocketAddress("127.0.0.1",port));
        var config=new NetworkConsensusConfig("a",peers,peers.keySet(),directory,new byte[32],Duration.ofMillis(450),Duration.ofMillis(30),1048576,134217728);
        var state=new QueueStateMachine("queue");var partition=new PartitionStateMachine("queue","group",1,state,new byte[32]);
        var clock=new MeasuredClockHealth(System::currentTimeMillis,System::nanoTime,System::currentTimeMillis,()->1);clock.sample();clock.sample();
        try(var node=new NetworkConsensusNode(config,partition)){
            node.start();try(var server=new PartitionServer(new InetSocketAddress("127.0.0.1",0),node,partition,null,Map.of("app",new PartitionServer.Binding("tenant",new byte[32],false,false)),clock,null)){
                var endpoint=new PartitionClient(List.of(server.address()),"app",new byte[32]);endpoint.status(deadline());
                var queue=new ReplicatedQueue("queue","tenant",(p,d)->new ReplicatedQueue.Route(endpoint,1));queue.join("join","g","m",60000,deadline());
                for(int i=1;i<=4;i++){
                    queue.send("send"+i,"poison","id"+i,new byte[]{(byte)i},1,deadline());
                    String[] grant=queue.claim("claim"+i,"poison","g","m",1,10000,deadline()).split("\\t");
                    assertEquals("NACKED",queue.nack("nack"+i,"poison","g","m",1,grant[1],Long.parseLong(grant[4]),deadline()));
                }
                try(var session=endpoint.deadLetterSession(1,"poison","observer",deadline())){
                    assertThrows(IOException.class,()->endpoint.deadLetterSession(1,"poison","observer",deadline()));
                    var received=new Receiver();session.subscribe(received);received.subscription.request(1);
                    var first=(QueueDelivery)received.take();assertEquals(1,first.sequence());assertTrue(first.observational());
                    assertNull(received.signals.poll(100,TimeUnit.MILLISECONDS),"Server must not deliver beyond granted demand");
                    received.subscription.request(Long.MAX_VALUE);
                    for(int i=2;i<=4;i++)assertEquals(i,((QueueDelivery)received.take()).sequence());
                    assertThrows(IllegalStateException.class,()->session.subscribe(new Receiver()));
                    assertTrue(server.queuePushStatistics().reservedBytes()<=16*1024*1024);
                }
                try(var session=queue.observeDeadLetters("poison","invalid-demand",deadline())){
                    var received=new Receiver();session.subscribe(received);received.subscription.request(0);
                    assertInstanceOf(IllegalArgumentException.class,received.take());assertTrue(session.isClosed());
                }
                byte[] payload=ResourceWire.encode(out->{out.writeLong(1);out.writeUTF("ready");out.writeUTF("g");out.writeUTF("raw-member");out.writeLong(10000);out.writeBoolean(false);});
                var connection=PartitionSessionHandshake.open(List.of(server.address()),"app",new byte[32],null,"QUEUE_SESSION",payload,deadline());
                long claims=server.queuePushStatistics().claims();
                try(var socket=connection.socket()){
                    socket.setSoTimeout(1000);
                    QueueSessionWire.send(new DataOutputStream(socket.getOutputStream()),new byte[32],connection.nonce(),1,out->{out.writeUTF("CREDIT");out.writeInt(5);});
                    assertThrows(EOFException.class,()->new DataInputStream(socket.getInputStream()).readInt());
                }
                assertEquals(claims,server.queuePushStatistics().claims());
                assertEquals("SENT\t5",queue.send("healthy-producer","ready","healthy",new byte[0],1,deadline()));
            }
        }
    }
}
