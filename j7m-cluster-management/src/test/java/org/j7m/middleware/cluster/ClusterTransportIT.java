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
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class ClusterTransportIT {
    @TempDir Path directory;
    @Test void mutualTlsRejectsAbsentClientCertificateAndWrongHostnameBeforeInspection()throws Exception{
        Path keyStore=directory.resolve("tls.p12");String password=UUID.randomUUID().toString();
        Process keytool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),"-genkeypair","-alias","cluster","-keyalg","RSA","-keysize","2048","-dname","CN=localhost","-ext","SAN=ip:127.0.0.1,dns:localhost","-validity","1","-storetype","PKCS12","-keystore",keyStore.toString(),"-storepass",password).redirectErrorStream(true).start();
        String diagnostic=new String(keytool.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertEquals(0,keytool.waitFor(),diagnostic);
        KeyStore store=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(keyStore)){store.load(input,password.toCharArray());}
        var keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(store,password.toCharArray());
        var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(keys.getKeyManagers(),trust.getTrustManagers(),new SecureRandom());
        SSLContext noClient=SSLContext.getInstance("TLS");noClient.init(new KeyManager[0],trust.getTrustManagers(),new SecureRandom());
        AtomicInteger effects=new AtomicInteger();ClusterLifecycle lifecycle=inspections(effects);
        try(var server=new ClusterServer(new InetSocketAddress("0.0.0.0",0),"cluster",Map.of("viewer",new ClusterServer.Principal(new byte[32],Set.of("INSPECT"))),lifecycle,null,tls)){
            var correct=new ClusterAdminClient(new InetSocketAddress("127.0.0.1",server.address().getPort()),"cluster","viewer",new byte[32],tls);
            assertEquals(0,correct.inspect(Instant.now().plusSeconds(3)).version());assertEquals(1,effects.get());
            assertThrows(IOException.class,()->new ClusterAdminClient(new InetSocketAddress("127.0.0.1",server.address().getPort()),"cluster","viewer",new byte[32],noClient).inspect(Instant.now().plusSeconds(1)));
            assertThrows(IOException.class,()->new ClusterAdminClient(new InetSocketAddress("127.0.0.2",server.address().getPort()),"cluster","viewer",new byte[32],tls).inspect(Instant.now().plusSeconds(1)));
            assertEquals(1,effects.get());
            long until=System.nanoTime()+java.time.Duration.ofSeconds(2).toNanos();
            while(server.rejectedRequests()<2&&System.nanoTime()<until)Thread.sleep(5);
            assertTrue(server.rejectedRequests()>=2);
        }
    }
    static ClusterLifecycle inspections(AtomicInteger effects){return (ClusterLifecycle)java.lang.reflect.Proxy.newProxyInstance(ClusterLifecycle.class.getClassLoader(),new Class<?>[]{ClusterLifecycle.class},(proxy,method,args)->{effects.incrementAndGet();return new ClusterView(0,Map.of(),"");});}
    @Test void authenticatedReplayTrailingDataOversizeAndStaleFenceNeverExecuteAnotherEffect()throws Exception{
        AtomicInteger effects=new AtomicInteger();
        try(var server=new ClusterServer(new InetSocketAddress("127.0.0.1",0),"cluster",Map.of("viewer",new ClusterServer.Principal(new byte[32],Set.of("INSPECT"))),inspections(effects),null,null)){
            String nonce=UUID.randomUUID().toString();byte[] body=request(nonce,false);
            assertEquals("OK",exchange(server.address(),body));assertEquals(1,effects.get());
            assertThrows(IOException.class,()->exchange(server.address(),body));assertEquals(1,effects.get());
            assertEquals("UNAVAILABLE_OR_UNKNOWN",exchange(server.address(),request(UUID.randomUUID().toString(),true)));assertEquals(1,effects.get());
            try(var socket=new Socket()){socket.connect(server.address());socket.setSoTimeout(1000);new DataOutputStream(socket.getOutputStream()).writeInt(ClusterWire.MAX+1);assertThrows(IOException.class,()->ClusterWire.receive(new DataInputStream(socket.getInputStream())));}
            assertTrue(server.rejectedRequests()>=3);
        }
        Path journal=directory.resolve("agent");try(var agent=ClusterAgentTest.agent(journal);
            var server=new ClusterServer(new InetSocketAddress("127.0.0.1",0),"cluster",Map.of("operator",new ClusterServer.Principal(new byte[32],Set.of("NODE_STEP","NODE_STATUS"))),null,agent,null)){
            var client=new ClusterNodeClient(server.address(),"cluster","operator",new byte[32],null);
            assertThrows(IOException.class,()->client.step("stale","DRAIN","v1","owner",2,1,Instant.now().plusSeconds(2)));
            assertFalse(Files.exists(journal));assertTrue(client.status(Instant.now().plusSeconds(2)).node().ready());
        }
    }
    static byte[] request(String nonce,boolean trailing)throws IOException{return ClusterWire.encode(out->{out.writeUTF("j7m.cluster/v1");out.writeUTF("cluster");out.writeUTF("viewer");out.writeUTF(nonce);out.writeLong(System.currentTimeMillis()+10000);out.writeUTF("INSPECT");if(trailing)out.writeInt(7);});}
    static String exchange(InetSocketAddress address,byte[] body)throws IOException{try(var socket=new Socket()){socket.connect(address);socket.setSoTimeout(2000);ClusterWire.send(new DataOutputStream(socket.getOutputStream()),body,new byte[32]);var input=new DataInputStream(socket.getInputStream());byte[] reply=ClusterWire.receive(input);ClusterWire.authenticate(input,reply,new byte[32]);try(var in=ClusterWire.input(reply)){assertEquals("cluster",in.readUTF());in.readUTF();return in.readUTF();}}}
}
